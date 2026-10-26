package io.waves.offline

import io.waves.currentTimeMillis
import io.waves.download.DownloadManager
import io.waves.model.EpisodeId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
            combine(offline, cache.entries, activeEpisode, pendingRemoved) { off, entries, active, removed ->
                Snapshot(off, entries, active, removed)
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
    )

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
    )
}
