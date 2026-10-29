package io.waves.sync

import io.waves.currentTimeMillis
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** At most [max] syncs start within any [windowMs]. */
data class SyncRateLimit(val max: Int, val windowMs: Long)

/**
 * Caps how often [delegate] reaches Google Drive, manual and scheduled syncs alike. A call that would go over any of
 * [limits] returns at once without syncing and leaves [status] as it was; changes it would have carried go with the
 * next sync that's allowed (the scheduler syncs again on the next change, resume or period).
 */
class RateLimitedSyncService(
    private val delegate: SyncService,
    private val limits: List<SyncRateLimit> = DEFAULT_LIMITS,
    private val clock: () -> Long = ::currentTimeMillis,
) : SyncService {
    override val status: StateFlow<SyncStatus> get() = delegate.status

    private val mutex = Mutex()
    /** Start times of syncs let through, oldest first, within the longest window. */
    private val starts = ArrayDeque<Long>()
    private val longestWindowMs = limits.maxOfOrNull { it.windowMs } ?: 0L

    override suspend fun syncNow() {
        if (tryStart()) delegate.syncNow()
    }

    private suspend fun tryStart(): Boolean = mutex.withLock {
        val now = clock()
        while (starts.isNotEmpty() && now - starts.first() >= longestWindowMs) starts.removeFirst()
        val allowed = limits.all { limit -> starts.count { now - it < limit.windowMs } < limit.max }
        if (allowed) starts.addLast(now)
        allowed
    }

    companion object {
        val DEFAULT_LIMITS = listOf(
            SyncRateLimit(max = 1, windowMs = 10_000),
            SyncRateLimit(max = 3, windowMs = 60_000),
            SyncRateLimit(max = 10, windowMs = 60 * 60_000),
        )
    }
}
