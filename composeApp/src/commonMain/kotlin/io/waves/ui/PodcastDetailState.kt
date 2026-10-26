package io.waves.ui

import io.waves.currentTimeMillis
import io.waves.data.FeedFetchLog
import io.waves.data.InMemoryFeedFetchLog
import io.waves.data.LibraryRepository
import io.waves.feed.FeedRepository
import io.waves.model.Episode
import io.waves.model.EpisodeId
import io.waves.model.PlaybackProgress
import io.waves.model.Podcast
import io.waves.refresh.FeedRefresher
import io.waves.search.PodcastDirectory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

data class EpisodeRow(
    val episode: Episode,
    val progress: PlaybackProgress?,
) {
    val played: Boolean get() = progress?.completed == true
}

data class PodcastDetailUiState(
    val podcast: Podcast,
    val subscribed: Boolean = false,
    val episodes: List<EpisodeRow> = emptyList(),
    val refreshing: Boolean = false,
    val error: String? = null,
    /** While true, refresh is skipped so a feed fetch cannot drop local audio. */
    val offline: Boolean = false,
)

/**
 * Podcast page. Refresh = [FeedRepository.fetch] then [LibraryRepository.upsertEpisodes].
 * When [autoFetch] is true (opened from search/charts) or the library has no episodes yet, refreshes on open, unless
 * [recentFetches] shows the feed was fetched moments ago and its episodes are stored.
 * Chart entries without a feed URL are first resolved via [PodcastDirectory.lookup].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PodcastDetailState(
    initial: Podcast,
    private val feeds: FeedRepository,
    private val library: LibraryRepository,
    private val directory: PodcastDirectory,
    private val scope: CoroutineScope,
    autoFetch: Boolean,
    private val onLocalChange: () -> Unit = {},
    private val onEpisodesAbsent: (Set<EpisodeId>) -> Unit = {},
    private val offline: StateFlow<Boolean> = MutableStateFlow(false),
    /** Null lists every episode; a set (while offline) lists only those that can play without a connection. */
    private val playable: Flow<Set<EpisodeId>?> = flowOf(null),
    private val recentFetches: RecentFetches = RecentFetches(),
) {
    private val podcast = MutableStateFlow(initial)
    private val refreshing = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    /** Episodes from the last fetch; shown if the library has none stored (e.g. not subscribed). */
    private val fetched = MutableStateFlow<List<Episode>>(emptyList())

    private val feedUrl = podcast.map { it.feedUrl }

    private val subscribed = feedUrl.flatMapLatest { url ->
        library.subscriptions.map { subs -> subs.any { it.feedUrl == url } }
    }

    private val storedEpisodes = feedUrl.flatMapLatest { url ->
        if (url.isBlank()) flowOf(emptyList()) else library.episodes(url)
    }

    private val progressById = library.allProgress.map { list -> list.associateBy { it.episodeId } }

    private val episodes = combine(storedEpisodes, fetched, progressById, playable) { stored, fetchedList, progress, playable ->
        val list = stored.ifEmpty { fetchedList }.let { all -> if (playable == null) all else all.filter { it.id in playable } }
        list.sortedByDescending { it.publishedAtEpochMs ?: Long.MIN_VALUE }
            .map { EpisodeRow(it, progress[it.id]) }
    }

    val state: StateFlow<PodcastDetailUiState> =
        combine(
            combine(podcast, subscribed, episodes, refreshing, error) { p, sub, eps, r, e ->
                PodcastDetailUiState(p, sub, eps, r, e)
            },
            offline,
        ) { ui, off -> ui.copy(offline = off) }
            .stateIn(scope, SharingStarted.Eagerly, PodcastDetailUiState(initial, offline = offline.value))

    init {
        scope.launch {
            if (podcast.value.feedUrl.isBlank()) resolveFeedUrl()
            val url = podcast.value.feedUrl
            if (url.isBlank()) return@launch
            // Opened from the player or History the podcast may be just a title; the stored row fills the rest.
            library.podcast(url)?.let { stored -> podcast.update { mergePodcast(stored, it) } }
            library.rememberPodcast(podcast.value)
            if ((autoFetch && !recentFetches.isFresh(url)) || library.episodes(url).first().isEmpty()) refresh()
        }
    }

    private suspend fun resolveFeedUrl() {
        val id = podcast.value.appleId ?: run {
            error.value = "This podcast has no feed URL"
            return
        }
        try {
            val resolved = directory.lookup(id)
            if (resolved == null || resolved.feedUrl.isBlank()) {
                error.value = "Couldn't find this podcast's feed"
            } else {
                podcast.value = mergePodcast(podcast.value, resolved)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error.value = e.message ?: "Lookup failed"
        }
    }

    fun refresh() {
        if (offline.value || refreshing.value) return
        scope.launch { doRefresh() }
    }

    private suspend fun doRefresh() {
        val url = podcast.value.feedUrl
        if (url.isBlank()) return
        refreshing.value = true
        error.value = null
        try {
            val known = library.episodes(url).first().mapTo(HashSet()) { it.guid }
            val feed = feeds.fetch(url)
            recentFetches.record(url)
            podcast.update { mergePodcast(it, feed.podcast) }
            fetched.value = feed.episodes
            library.upsertEpisodes(url, feed.episodes)
            onEpisodesAbsent(FeedRefresher.absentEpisodeIds(url, known, feed.episodes))
            // rememberPodcast leaves subscriptions alone, so a followed show's new cover would only reach Home and
            // the player after the next background refresh.
            if (subscribed.first()) library.updatePodcastMetadata(podcast.value) else library.rememberPodcast(podcast.value)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error.value = e.message ?: "Couldn't refresh feed"
        } finally {
            refreshing.value = false
        }
    }

    fun toggleSubscribe() {
        scope.launch {
            val p = podcast.value
            if (p.feedUrl.isBlank()) return@launch
            if (state.value.subscribed) {
                library.unsubscribe(p.feedUrl)
            } else {
                library.subscribe(p)
                val eps = fetched.value
                if (eps.isNotEmpty()) library.upsertEpisodes(p.feedUrl, eps)
            }
            onLocalChange()
        }
    }

    fun clearError() { error.value = null }
}

/** Whether a feed was fetched within [freshFor] (here or by the background refresh), so a show page doesn't fetch it again. */
class RecentFetches(
    private val log: FeedFetchLog = InMemoryFeedFetchLog(),
    private val freshFor: Duration = 1.hours,
    private val clock: () -> Long = ::currentTimeMillis,
) {
    suspend fun isFresh(feedUrl: String): Boolean =
        log.fetchedAt(feedUrl)?.let { clock() - it in 0 until freshFor.inWholeMilliseconds } == true

    suspend fun record(feedUrl: String) = log.record(feedUrl, clock())
}

/** Keep the original feed URL; prefer fresh feed metadata while keeping directory-only fields (apple id, artwork) when the feed lacks them. */
internal fun mergePodcast(old: Podcast, new: Podcast): Podcast = Podcast(
    feedUrl = old.feedUrl.ifBlank { new.feedUrl }, // library rows are keyed by the original URL
    title = new.title.ifBlank { old.title },
    author = new.author ?: old.author,
    description = new.description ?: old.description,
    artworkUrl = new.artworkUrl ?: old.artworkUrl,
    appleId = new.appleId ?: old.appleId,
)
