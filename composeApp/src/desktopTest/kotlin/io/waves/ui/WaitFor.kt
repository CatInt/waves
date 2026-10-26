package io.waves.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi

/**
 * Renders frames until [condition] holds, failing after [timeoutMillis] of wall-clock time. Use instead of
 * `waitUntil`: on desktop (Compose Multiplatform 1.7.3) it never throws on timeout and loops forever.
 */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.waitFor(description: String, timeoutMillis: Long = 5_000, condition: () -> Boolean) {
    val deadline = System.nanoTime() + timeoutMillis * 1_000_000
    while (!condition()) {
        if (System.nanoTime() > deadline) throw AssertionError("Timed out after $timeoutMillis ms waiting for $description")
        mainClock.advanceTimeByFrame()
    }
}
