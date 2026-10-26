package io.waves.offline

import io.waves.download.DownloadInfo
import io.waves.download.DownloadStatus
import io.waves.model.EpisodeId
import kotlinx.coroutines.flow.Flow

/** How much of one episode's audio the player has stored on disk. */
data class PlaybackCacheEntry(
    val episodeId: EpisodeId,
    val audioUrl: String,
    val bytesCached: Long,
    /** Whole file size once the player knows it; null until then. */
    val totalBytes: Long?,
    val updatedAtEpochMs: Long,
) {
    /** The whole enclosure is on disk, so it can play with no network. */
    val isFullyCached: Boolean
        get() = totalBytes != null && totalBytes > 0L && bytesCached >= totalBytes
}

/**
 * Playback cache: audio stored while streaming, separate from explicit downloads.
 * [remove] is the manual path and always deletes. Automatic eviction lives in [evictionPlan].
 */
interface PlaybackCache {
    val entries: Flow<List<PlaybackCacheEntry>>

    suspend fun upsert(entry: PlaybackCacheEntry)

    /** Drops the catalog row and asks the platform to delete the cached bytes. */
    suspend fun remove(episodeId: EpisodeId)
}

/** Upper bound on fully cached audio kept while online. Ignored while offline. */
const val DEFAULT_CACHE_BUDGET_BYTES = 512L * 1024 * 1024

/**
 * Partials younger than this are left alone, so a file that finishes caching as playback ends is not
 * deleted before the "fully cached" update lands.
 */
const val DEFAULT_PARTIAL_GRACE_MS = 60_000L

/** Episodes the home list can offer while offline: completed downloads plus fully cached audio. */
fun offlinePlayableIds(
    downloads: List<DownloadInfo>,
    cache: List<PlaybackCacheEntry>,
    fileCache: Set<EpisodeId> = emptySet(),
): Set<EpisodeId> {
    val ids = HashSet<EpisodeId>()
    for (info in downloads) if (info.status == DownloadStatus.Done) ids += info.episodeId
    for (entry in cache) if (entry.isFullyCached) ids += entry.episodeId
    ids += fileCache
    return ids
}

/** What automatic maintenance wants to delete. Empty while offline: only [PlaybackCache.remove] and download removal by the user run then. */
data class EvictionPlan(
    /** Explicit downloads of episodes that left the feed. */
    val downloadIds: Set<EpisodeId> = emptySet(),
    /** Playback-cache rows: partials, episodes that left the feed, and fully cached audio over the budget. */
    val cacheIds: Set<EpisodeId> = emptySet(),
)

/**
 * Online maintenance:
 * - partial cache entries older than [partialGraceMs], except the episode loaded in the player
 * - downloads and cache of episodes removed from a feed, except the one loaded in the player
 * - oldest fully cached audio once the fully cached bytes exceed [fullyCachedBudgetBytes]
 *
 * While [offline] is true the plan is empty, so cached audio and removed episodes stay until the user removes them.
 */
fun evictionPlan(
    offline: Boolean,
    removedIds: Set<EpisodeId>,
    cache: List<PlaybackCacheEntry>,
    activeEpisodeId: EpisodeId?,
    nowEpochMs: Long,
    fullyCachedBudgetBytes: Long = DEFAULT_CACHE_BUDGET_BYTES,
    partialGraceMs: Long = DEFAULT_PARTIAL_GRACE_MS,
): EvictionPlan {
    if (offline) return EvictionPlan()
    val protected = setOfNotNull(activeEpisodeId)
    val partial = cache.mapNotNullTo(HashSet()) { entry ->
        val age = nowEpochMs - entry.updatedAtEpochMs
        if (!entry.isFullyCached && entry.episodeId !in protected && age >= partialGraceMs) entry.episodeId else null
    }
    val removed = removedIds - protected
    val dropping = partial + removed
    val protectedBytes = cache.filter { it.episodeId in protected }.sumOf { it.bytesCached }
    val victims = cache
        .filter { it.isFullyCached && it.episodeId !in dropping && it.episodeId !in protected }
        .sortedBy { it.updatedAtEpochMs }
    var bytes = protectedBytes + victims.sumOf { it.bytesCached }
    val overflow = HashSet<EpisodeId>()
    for (entry in victims) {
        if (bytes <= fullyCachedBudgetBytes) break
        overflow += entry.episodeId
        bytes -= entry.bytesCached
    }
    return EvictionPlan(downloadIds = removed, cacheIds = dropping + overflow)
}

/**
 * How long until the youngest unprotected partial falls out of [partialGraceMs], or null when none are inside it.
 * The cleaner waits this long and then plans again, so a just-finished stream can be recorded as fully cached first.
 */
fun nextPartialCheckDelay(
    cache: List<PlaybackCacheEntry>,
    activeEpisodeId: EpisodeId?,
    nowEpochMs: Long,
    partialGraceMs: Long = DEFAULT_PARTIAL_GRACE_MS,
): Long? {
    var wait: Long? = null
    for (entry in cache) {
        if (entry.isFullyCached || entry.episodeId == activeEpisodeId) continue
        val age = nowEpochMs - entry.updatedAtEpochMs
        if (age >= partialGraceMs) continue
        val remaining = partialGraceMs - age
        wait = wait?.let { minOf(it, remaining) } ?: remaining
    }
    return wait
}
