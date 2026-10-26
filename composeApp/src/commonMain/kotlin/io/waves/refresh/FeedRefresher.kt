package io.waves.refresh

import io.waves.currentTimeMillis
import io.waves.data.FeedFetchLog
import io.waves.data.LibraryRepository
import io.waves.feed.FeedRepository
import io.waves.model.Episode
import io.waves.model.EpisodeId
import io.waves.model.Podcast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * Re-fetches subscribed feeds and stores their episodes, reporting which episodes are new.
 *
 * An episode is *new* when its guid was not in the library before this refresh AND it was published after the
 * feed's previous successful refresh (minus [lateArrivalSlack], since feeds often appear on the CDN a while after
 * their pubDate). On a feed's first refresh the cutoff is [firstRefreshWindow] ago, so subscribing to a podcast
 * doesn't announce its whole backlog. Episodes without a pubDate are never announced.
 *
 * Feeds are fetched with at most [concurrency] in flight; each has its own [perFeedTimeout] and failures are
 * isolated (reported in [RefreshResult.failures]). Calls are serialized: concurrent refreshes queue up.
 */
class FeedRefresher(
    private val feeds: FeedRepository,
    private val library: LibraryRepository,
    private val stateStore: RefreshStateStore = InMemoryRefreshStateStore(),
    /** Also told of each successful fetch, so show pages opened soon after don't fetch the feed again. */
    private val fetchLog: FeedFetchLog? = null,
    private val concurrency: Int = 4,
    private val perFeedTimeout: Duration = 30.seconds,
    private val firstRefreshWindow: Duration = 7.days,
    private val lateArrivalSlack: Duration = 12.hours,
    private val clock: () -> Long = ::currentTimeMillis,
) {
    init {
        require(concurrency >= 1) { "concurrency must be >= 1" }
    }

    private val _status = MutableStateFlow<RefreshStatus>(RefreshStatus.Idle())
    val status: StateFlow<RefreshStatus> = _status.asStateFlow()

    private val mutex = Mutex()

    /**
     * Episodes that were stored for a feed and are missing from a successful, non-empty fetch.
     * An empty fetch reports nothing, so a broken parse cannot wipe local audio.
     */
    @Volatile
    var onEpisodesAbsent: ((Set<EpisodeId>) -> Unit)? = null

    /** Refreshes every current subscription. */
    suspend fun refreshAll(): RefreshResult = mutex.withLock {
        run(library.subscriptions.first())
    }

    /** Refreshes one feed (e.g. right after subscribing or on pull-to-refresh in a podcast screen). */
    suspend fun refresh(feedUrl: String): RefreshResult = mutex.withLock {
        val podcast = library.subscriptions.first().firstOrNull { it.feedUrl == feedUrl }
            ?: Podcast(feedUrl = feedUrl, title = feedUrl)
        run(listOf(podcast))
    }

    private suspend fun run(podcasts: List<Podcast>): RefreshResult {
        val startedAt = clock()
        val previous = _status.value.lastResult
        _status.value = RefreshStatus.Refreshing(0, podcasts.size, previous)
        try {
            val semaphore = Semaphore(concurrency)
            val outcomes = coroutineScope {
                podcasts.map { podcast ->
                    async {
                        semaphore.withPermit { refreshOne(podcast) }.also {
                            _status.update { s ->
                                if (s is RefreshStatus.Refreshing) s.copy(done = s.done + 1) else s
                            }
                        }
                    }
                }.awaitAll()
            }
            val result = RefreshResult(
                startedAtEpochMs = startedAt,
                finishedAtEpochMs = clock(),
                total = podcasts.size,
                updates = outcomes.mapNotNull { (it as? Outcome.Ok)?.update },
                failures = outcomes.mapNotNull { (it as? Outcome.Failed)?.failure },
            )
            _status.value = RefreshStatus.Idle(result)
            return result
        } catch (e: Throwable) {
            _status.value = RefreshStatus.Idle(previous)
            throw e
        }
    }

    private sealed interface Outcome {
        data class Ok(val update: PodcastUpdate?) : Outcome
        data class Failed(val failure: FeedFailure) : Outcome
    }

    private suspend fun refreshOne(podcast: Podcast): Outcome {
        val feedUrl = podcast.feedUrl
        return try {
            val fetchedAt = clock()
            val feed = withTimeoutOrNull(perFeedTimeout) { feeds.fetch(feedUrl) }
                ?: return Outcome.Failed(FeedFailure(feedUrl, "Timed out after $perFeedTimeout"))
            val episodes = feed.episodes.map { if (it.podcastFeedUrl == feedUrl) it else it.copy(podcastFeedUrl = feedUrl) }
            val known = library.episodes(feedUrl).first().mapTo(HashSet()) { it.guid }
            val last = stateStore.lastRefreshedAt(feedUrl)
            val cutoff = if (last == null) fetchedAt - firstRefreshWindow.inWholeMilliseconds
            else last - lateArrivalSlack.inWholeMilliseconds
            val fresh = newEpisodes(episodes, known, cutoff)

            library.upsertEpisodes(feedUrl, episodes)
            onEpisodesAbsent?.invoke(absentEpisodeIds(feedUrl, known, episodes))
            stateStore.setLastRefreshedAt(feedUrl, fetchedAt)
            fetchLog?.record(feedUrl, fetchedAt)

            val display = mergeMetadata(podcast, feed.podcast)
            if (display != podcast) library.updatePodcastMetadata(display)
            Outcome.Ok(if (fresh.isEmpty()) null else PodcastUpdate(display, fresh))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Outcome.Failed(FeedFailure(feedUrl, e.message ?: e::class.simpleName ?: "error"))
        }
    }

    companion object {
        /**
         * [stored] updated with what the feed says now. Fields the feed omits keep their stored value, and a
         * title equal to the feed URL (the parser's fallback for a missing `<title>`) is ignored.
         */
        fun mergeMetadata(stored: Podcast, fromFeed: Podcast): Podcast = stored.copy(
            title = fromFeed.title.takeIf { it.isNotBlank() && it != stored.feedUrl } ?: stored.title,
            author = fromFeed.author?.takeIf { it.isNotBlank() } ?: stored.author,
            description = fromFeed.description?.takeIf { it.isNotBlank() } ?: stored.description,
            artworkUrl = fromFeed.artworkUrl?.takeIf { it.isNotBlank() } ?: stored.artworkUrl,
        )

        /**
         * Guids that were stored and are missing from [fetched]. An empty fetch returns nothing: a feed that
         * failed to list its episodes must not be treated as having removed them.
         */
        fun absentEpisodeIds(feedUrl: String, knownGuids: Set<String>, fetched: List<Episode>): Set<EpisodeId> {
            if (fetched.isEmpty()) return emptySet()
            val stillThere = fetched.mapTo(HashSet()) { it.guid }
            return (knownGuids - stillThere).mapTo(HashSet()) { EpisodeId(feedUrl, it) }
        }

        /** Pure "new episode" rule; [cutoffEpochMs] is exclusive. Result is newest first. */
        fun newEpisodes(fetched: List<Episode>, knownGuids: Set<String>, cutoffEpochMs: Long): List<Episode> =
            fetched
                .filter { it.guid !in knownGuids }
                .filter { (it.publishedAtEpochMs ?: Long.MIN_VALUE) > cutoffEpochMs }
                .distinctBy { it.guid }
                .sortedByDescending { it.publishedAtEpochMs }
    }
}
