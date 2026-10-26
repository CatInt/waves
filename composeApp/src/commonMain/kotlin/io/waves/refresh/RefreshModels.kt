package io.waves.refresh

import io.waves.model.Episode
import io.waves.model.Podcast
import kotlinx.coroutines.sync.withLock

/** New episodes found for one podcast during a refresh, newest first. */
data class PodcastUpdate(val podcast: Podcast, val newEpisodes: List<Episode>)

/** A feed that could not be refreshed (download/parse error or timeout). */
data class FeedFailure(val feedUrl: String, val message: String)

/** Outcome of [FeedRefresher.refreshAll] / [FeedRefresher.refresh]. */
data class RefreshResult(
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long,
    /** Feeds attempted. */
    val total: Int,
    /** Only podcasts that actually have new episodes. */
    val updates: List<PodcastUpdate>,
    val failures: List<FeedFailure>,
) {
    val succeeded: Int get() = total - failures.size
    val newEpisodeCount: Int get() = updates.sumOf { it.newEpisodes.size }
}

sealed interface RefreshStatus {
    /** Result of the most recent completed refresh, if any (survives while a new one is running). */
    val lastResult: RefreshResult?

    data class Idle(override val lastResult: RefreshResult? = null) : RefreshStatus

    /** [done] of [total] feeds finished so far. */
    data class Refreshing(val done: Int, val total: Int, override val lastResult: RefreshResult?) : RefreshStatus
}

/** Shows "new episodes" to the user. Called once per refresh with only the podcasts that have new episodes. */
fun interface RefreshNotifier {
    fun notifyNewEpisodes(updates: List<PodcastUpdate>)
}

/** Remembers when each feed was last refreshed successfully (epoch ms). Backs the "new episode" cutoff. */
interface RefreshStateStore {
    suspend fun lastRefreshedAt(feedUrl: String): Long?
    suspend fun setLastRefreshedAt(feedUrl: String, epochMs: Long)
}

class InMemoryRefreshStateStore(initial: Map<String, Long> = emptyMap()) : RefreshStateStore {
    private val map = initial.toMutableMap()
    private val lock = kotlinx.coroutines.sync.Mutex()
    override suspend fun lastRefreshedAt(feedUrl: String): Long? = lock.withLock { map[feedUrl] }
    override suspend fun setLastRefreshedAt(feedUrl: String, epochMs: Long) = lock.withLock {
        map[feedUrl] = epochMs
    }
}

/** Shared notification wording so Android and desktop say the same thing. */
object RefreshText {
    fun title(update: PodcastUpdate): String = update.podcast.title

    fun body(update: PodcastUpdate): String {
        val n = update.newEpisodes.size
        val first = update.newEpisodes.firstOrNull()?.title.orEmpty()
        return if (n == 1) first else "$n new episodes: $first"
    }

    fun summary(updates: List<PodcastUpdate>): String {
        val episodes = updates.sumOf { it.newEpisodes.size }
        return "$episodes new episode${if (episodes == 1) "" else "s"} from ${updates.size} podcast${if (updates.size == 1) "" else "s"}"
    }
}
