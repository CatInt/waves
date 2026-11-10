package io.waves.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Decides who writes a cache key: the player while streaming, or a background fill.
 *
 * A writer holds the cache's lock on everything from its position to the end of the file for as long as it is open,
 * and anyone else reading that range fetches it from the network without keeping it. So while a fill runs, the
 * player reads only ([claimWrite] is false) and a player already writing hands over on its next read ([isFilling]).
 */
internal class CacheWriteClaims {
    private val filling = HashSet<String>()
    private val writers = HashMap<String, Int>()
    /** Bumped each time a writer lets go, to wake [awaitNoWriters]. */
    private val released = MutableStateFlow(0L)

    /** True when the player may write [key]; it must then [releaseWrite] it. */
    @Synchronized
    fun claimWrite(key: String): Boolean {
        if (key in filling) return false
        writers[key] = (writers[key] ?: 0) + 1
        return true
    }

    @Synchronized
    fun releaseWrite(key: String) {
        val n = writers[key] ?: return
        if (n <= 1) writers.remove(key) else writers[key] = n - 1
        released.value++
    }

    @Synchronized
    fun isFilling(key: String): Boolean = key in filling

    @Synchronized
    fun beginFill(key: String) {
        filling += key
    }

    @Synchronized
    fun endFill(key: String) {
        filling -= key
    }

    @Synchronized
    fun hasWriter(key: String): Boolean = key in writers

    /** Waits until no player writes [key]; false when one still does after [timeoutMs] (e.g. paused with a full buffer). */
    suspend fun awaitNoWriters(key: String, timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) {
            released.first { !hasWriter(key) }
            true
        } ?: false
}
