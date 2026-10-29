package io.waves.ui

import io.waves.currentTimeMillis
import io.waves.data.LibraryRepository
import io.waves.download.DownloadInfo
import io.waves.feed.FeedRepository
import io.waves.model.Episode
import io.waves.model.EpisodeId
import io.waves.model.Podcast
import io.waves.offline.PlaybackCacheEntry
import io.waves.offline.offlinePlayableIds
import io.waves.refresh.FeedRefresher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

data class HomeUiState(
    /** Alphabetical. */
    val subscriptions: List<Podcast> = emptyList(),
    /** Subscriptions in shelf order, see [UpNextRanking.orderShelf]. */
    val shelf: List<Podcast> = emptyList(),
    /** Personalised episode list, see [UpNextRanking.rank]. */
    val upNext: List<UpNextItem> = emptyList(),
    /** True once any subscribed show has stored episodes. */
    val hasEpisodes: Boolean = false,
    val loaded: Boolean = false,
    val refreshing: Boolean = false,
    /** Message summarising feeds that failed during the last refresh-all. */
    val error: String? = null,
    /** While true, [upNext] contains only downloaded and fully cached episodes. */
    val offline: Boolean = false,
)

/**
 * Home: a shelf of subscribed shows plus a personalised "Up next" list ranked by [UpNextRanking] (which also
 * carries every unplayed download), with
 * refresh-all (fetch every feed, upsert its episodes). Refreshes automatically once if subscriptions exist but
 * no episodes are stored yet (e.g. subscriptions that just arrived through sync).
 *
 * While [offline] is true, "Up next" keeps only episodes with a completed download or a fully cached file, and
 * refresh-all does nothing (feeds are fetched again once offline mode is off).
 *
 * The order on screen holds still while the user acts on it (playing, pausing, marking played): rows update in
 * place, new ones slot in at their ranked position and finished ones drop out. [releaseOrder] adopts the fresh
 * ranking; it is called while Home is off screen (another tab or screen, the app in the background) and after a
 * refresh.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeState(
    private val library: LibraryRepository,
    private val feeds: FeedRepository,
    private val scope: CoroutineScope,
    private val parallelism: Int = 4,
    private val onLocalChange: () -> Unit = {},
    private val clock: () -> Long = ::currentTimeMillis,
    private val upNextLimit: Int = 50,
    private val offline: StateFlow<Boolean> = MutableStateFlow(false),
    /** Every known download; episodes with one (in any state) are always listed in Up next until played. */
    private val downloads: Flow<List<DownloadInfo>> = flowOf(emptyList()),
    private val playbackCache: Flow<List<PlaybackCacheEntry>> = flowOf(emptyList()),
    /** Whole-file playback cache (listened for a minute), separate from explicit downloads. */
    private val cachedEpisodeIds: Flow<Set<EpisodeId>> = flowOf(emptySet()),
    private val onEpisodesAbsent: (Set<EpisodeId>) -> Unit = {},
    /**
     * Where ranking and episode bookkeeping run: off the main thread. A refresh lands one show at a time, and
     * re-ranking every episode on each would stutter the pull's spinner.
     */
    private val workContext: CoroutineContext = Dispatchers.Default,
) {
    private val refreshing = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    private val orderEpoch = MutableStateFlow(0)
    private var shownOrder: List<EpisodeId> = emptyList()
    private var shownEpoch = -1

    private class Ranked(
        val subscriptions: List<Podcast>,
        val shelf: List<Podcast>,
        val upNext: List<UpNextItem>,
        val hasEpisodes: Boolean,
    )

    private val episodesByFeed: Flow<Map<String, List<Episode>>> = library.subscriptions.flatMapLatest { podcasts ->
        if (podcasts.isEmpty()) flowOf(emptyMap())
        else combine(podcasts.map { p -> library.episodes(p.feedUrl).map { p.feedUrl to it } }) { it.toMap() }
    }

    private val downloadedIds = downloads.map { list -> list.map { it.episodeId }.toSet() }.distinctUntilChanged()

    /** Downloaded episodes of shows the user doesn't subscribe to, by show. */
    private val otherDownloads: Flow<Map<Podcast, List<Episode>>> =
        combine(library.subscriptions.map { subs -> subs.map { it.feedUrl }.toSet() }, downloadedIds) { subscribed, ids ->
            ids.filter { it.feedUrl !in subscribed }.groupBy { it.feedUrl }
        }.distinctUntilChanged().transformLatest { byFeed ->
            val episodes = byFeed.mapValues { (_, ids) -> ids.mapNotNull { library.episode(it) } }.filterValues { it.isNotEmpty() }
            val stored = episodes.keys.associateWith { library.podcast(it) }
            // List them right away; shows missing from the library get their name from the feed afterwards.
            emit(episodes.mapKeys { (url, _) -> stored[url] ?: placeholderShow(url) })
            if (stored.values.any { it == null }) {
                emit(episodes.mapKeys { (url, _) -> stored[url] ?: fetchShow(url) ?: placeholderShow(url) })
            }
        }

    private val sources = combine(library.subscriptions, episodesByFeed, otherDownloads, ::Triple)

    /** Null while online (every stored episode is eligible). A set while offline: only those ids are listed. */
    private val playable: Flow<Set<EpisodeId>?> =
        combine(offline, downloads, playbackCache, cachedEpisodeIds) { off, items, entries, cached ->
            if (!off) null else offlinePlayableIds(items, entries, cached)
        }

    private val ranked: StateFlow<Ranked?> =
        combine(sources, library.allProgress, library.history, downloadedIds, playable) { (subs, episodes, others), progress, history, downloaded, playable ->
            val now = clock()
            val byId = progress.associateBy { it.episodeId }
            val subscribed = episodes.filterKeys { url -> subs.any { it.feedUrl == url } }
            val listed = subscribed + others.mapKeys { it.key.feedUrl }
            val visible = if (playable == null) listed else listed.mapValues { (_, eps) -> eps.filter { it.id in playable } }
            Ranked(
                subscriptions = subs.sortedBy { it.title.lowercase() },
                shelf = UpNextRanking.orderShelf(subs, subscribed, byId, history, now),
                upNext = UpNextRanking.rank(subs + others.keys, visible, byId, history, now, limit = upNextLimit, downloaded = downloaded),
                hasEpisodes = subscribed.values.any { it.isNotEmpty() },
            )
        }.flowOn(workContext).stateIn(scope, SharingStarted.Eagerly, null)

    val state: StateFlow<HomeUiState> =
        combine(ranked, refreshing, error, offline, orderEpoch) { r, refreshing, e, off, epoch ->
            val fresh = r?.upNext.orEmpty()
            val upNext = if (r == null || epoch != shownEpoch) fresh else keepOrder(fresh, shownOrder)
            if (r != null) {
                shownOrder = upNext.map { it.row.episode.id }
                shownEpoch = epoch
            }
            HomeUiState(
                subscriptions = r?.subscriptions.orEmpty(),
                shelf = r?.shelf.orEmpty(),
                upNext = upNext,
                hasEpisodes = r?.hasEpisodes == true,
                loaded = r != null,
                refreshing = refreshing,
                error = e,
                offline = off,
            )
        }.stateIn(scope, SharingStarted.Eagerly, HomeUiState(offline = offline.value))

    init {
        scope.launch {
            val first = ranked.first { it != null }!!
            if (!offline.value && first.subscriptions.isNotEmpty() && !first.hasEpisodes) refreshAll()
        }
    }

    /** Re-sorts Up next by the current ranking. */
    fun releaseOrder() {
        orderEpoch.value++
    }

    fun refreshAll() {
        if (offline.value || refreshing.value) return
        refreshing.value = true
        error.value = null
        scope.launch {
            try {
                val podcasts = library.subscriptions.first()
                val gate = Semaphore(parallelism.coerceAtLeast(1))
                val failures = coroutineScope {
                    podcasts.map { podcast ->
                        async {
                            gate.withPermit {
                                try {
                                    val absent = withContext(workContext) {
                                        val known = library.episodes(podcast.feedUrl).first().mapTo(HashSet()) { it.guid }
                                        val feed = feeds.fetch(podcast.feedUrl)
                                        library.upsertEpisodes(podcast.feedUrl, feed.episodes)
                                        FeedRefresher.absentEpisodeIds(podcast.feedUrl, known, feed.episodes)
                                    }
                                    onEpisodesAbsent(absent)
                                    null
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    podcast.title
                                }
                            }
                        }
                    }.awaitAll().filterNotNull()
                }
                if (failures.isNotEmpty()) {
                    error.value = "Couldn't refresh: " + failures.joinToString(", ")
                }
            } finally {
                refreshing.value = false
                releaseOrder()
            }
        }
    }

    /** The show as its feed describes it, remembered in the library; null offline or when the feed can't be fetched. */
    private suspend fun fetchShow(feedUrl: String): Podcast? = if (offline.value) null else try {
        feeds.fetch(feedUrl).podcast.copy(feedUrl = feedUrl).also { library.rememberPodcast(it) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /** A show known only by its feed URL, named from listening history when it was played before. */
    private suspend fun placeholderShow(feedUrl: String): Podcast {
        val title = library.history.first().firstOrNull { it.episodeId.feedUrl == feedUrl }?.podcastTitle
        return Podcast(feedUrl, title.orEmpty())
    }

    fun clearError() { error.value = null }
}

/** [fresh] in the order of [shown]; episodes not shown before take their ranked index. */
internal fun keepOrder(fresh: List<UpNextItem>, shown: List<EpisodeId>): List<UpNextItem> {
    val byId = fresh.associateBy { it.row.episode.id }
    val result = shown.mapNotNullTo(ArrayList(fresh.size)) { byId[it] }
    val kept = shown.toSet()
    fresh.forEachIndexed { index, item ->
        if (item.row.episode.id !in kept) result.add(index.coerceAtMost(result.size), item)
    }
    return result
}
