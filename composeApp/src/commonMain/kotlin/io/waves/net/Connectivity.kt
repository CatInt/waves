package io.waves.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Whether the device can reach the Internet, for platforms without a system signal: runs [check] now and then
 * every [onlineEvery] while it passes, every [offlineEvery] while it fails (to notice the network coming back
 * quickly). Starts out online so nothing is hidden before the first check finishes.
 */
fun pollOnline(
    scope: CoroutineScope,
    onlineEvery: Duration = 60.seconds,
    offlineEvery: Duration = 10.seconds,
    check: suspend () -> Boolean,
): StateFlow<Boolean> = flow {
    while (true) {
        val online = check()
        emit(online)
        delay(if (online) onlineEvery else offlineEvery)
    }
}.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, true)
