package io.waves.offline

import io.waves.currentTimeMillis
import io.waves.download.DownloadManager
import io.waves.download.DownloadStatus
import io.waves.model.EpisodeId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Applies [evictionPlan] while online. While offline it keeps a queue of episodes that left their feeds and
 * does not delete cache rows; [PlaybackCache.remove] and [DownloadManager.delete] from the UI still run.
 *
 * Partials inside the grace window are checked again when the window ends, on a sibling job so the collector
 * keeps seeing newer cache updates (a stream crossing into fully cached) in the meantime.
 */
class LocalMediaCleaner(
    private val downloads: DownloadManager?,
    private val cache: PlaybackCache,
    private val offline: StateFlow<Boolean>,
    private val activeEpisode: StateFlow<EpisodeId?>,
    private val scope: CoroutineScope,
    private val budgetBytes: Long = DEFAULT_CACHE_BUDGET_BYTES,
    private val partialGraceMs: Long = DEFAULT_PARTIAL_GRACE_MS,
    private val clock: () -> Long = ::currentTimeMillis,
    /** Whether a downloaded file is still on disk. */
    private val fileExists: (String) -> Boolean = ::fileOnDisk,
) {
    private val pendingRemoved = MutableStateFlow<Set<EpisodeId>>(emptySet())
    private val applyLock = Mutex()
    private var job: Job? = null
    private var graceJob: Job? = null

    /** Episodes that were stored and are missing from a successful non-empty feed fetch. */
    fun noteRemoved(ids: Set<EpisodeId>) {
        if (ids.isEmpty()) return
        pendingRemoved.update { it + ids }
    }

    /** Starts applying plans; idempotent. */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            combine(offline, cache.entries, activeEpisode, pendingRemoved, localCopyIds()) { off, entries, active, removed, local ->
                Snapshot(off, entries, active, removed, local)
            }.collect { snap ->
                apply(plan(snap, clock()))
                graceJob?.cancel()
                val wait = if (snap.offline) null else nextPartialCheckDelay(snap.cache, snap.active, clock(), partialGraceMs)
                if (wait != null) {
                    graceJob = scope.launch {
                        delay(wait)
                        val entries = cache.entries.first()
                        val now = clock()
                        apply(
                            evictionPlan(
                                offline = offline.value,
                                removedIds = pendingRemoved.value,
                                cache = entries,
                                activeEpisodeId = activeEpisode.value,
                                nowEpochMs = now,
                                fullyCachedBudgetBytes = budgetBytes,
                                partialGraceMs = partialGraceMs,
                                localCopyIds = snap.localCopyIds,
                            ),
                        )
                    }
                }
            }
        }
    }

    fun stop() {
        graceJob?.cancel()
        job?.cancel()
        job = null
    }

    private fun plan(snap: Snapshot, now: Long) = evictionPlan(
        offline = snap.offline,
        removedIds = snap.removed,
        cache = snap.cache,
        activeEpisodeId = snap.active,
        nowEpochMs = now,
        fullyCachedBudgetBytes = budgetBytes,
        partialGraceMs = partialGraceMs,
        localCopyIds = snap.localCopyIds,
    )

    /**
     * Episodes with a completed download or a cached file: the player's copy of those only takes space. A download
     * whose file has gone (deleted outside the app) doesn't count, so the player's copy isn't dropped as well.
     */
    private fun localCopyIds(): Flow<Set<EpisodeId>> {
        val manager = downloads ?: return flowOf(emptySet())
        return combine(manager.downloads, manager.cachedEpisodeIds) { items, cached ->
            items.filter { it.status == DownloadStatus.Done && fileExists(it.filePath) }.mapTo(HashSet()) { it.episodeId } + cached
        }.distinctUntilChanged()
    }

    private suspend fun apply(plan: EvictionPlan) = applyLock.withLock {
        if (plan.downloadIds.isNotEmpty()) downloads?.deleteDownloads(plan.downloadIds)
        for (id in plan.cacheIds) cache.remove(id)
        if (plan.downloadIds.isNotEmpty()) pendingRemoved.update { it - plan.downloadIds }
    }

    private class Snapshot(
        val offline: Boolean,
        val cache: List<PlaybackCacheEntry>,
        val active: EpisodeId?,
        val removed: Set<EpisodeId>,
        val localCopyIds: Set<EpisodeId>,
    )
}

private fun fileOnDisk(path: String): Boolean =
    path.isNotBlank() && runCatching { SystemFileSystem.exists(Path(path)) }.getOrDefault(false)
