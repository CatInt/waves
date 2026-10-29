package io.waves.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollDispatcher
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [PullToRefreshBox] that a trackpad or mouse wheel can pull too. A list at its top drops the wheel scroll that would
 * pull (it never reaches the pull's nested scroll), and a wheel pull has no finger lift to settle it, so this feeds the
 * pull the wheel scroll the list leaves over and lets go once the wheel goes quiet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WavesPullToRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    PullToRefreshBox(isRefreshing = isRefreshing, onRefresh = onRefresh, modifier = modifier) {
        Box(Modifier.fillMaxSize().wheelPull(), content = content)
    }
}

/** Scroll per wheel unit, as on macOS (a notch, or a trackpad's precise delta). */
private val WheelStep = 10.dp

/** How long the wheel stays quiet before a pull counts as let go; trackpad momentum keeps it going until it ends. */
private const val WheelIdleMillis = 150L

@Composable
private fun Modifier.wheelPull(): Modifier {
    val scope = rememberCoroutineScope()
    val wheel = remember(scope) { WheelPull(scope) }
    return nestedScroll(wheel, wheel.dispatcher).pointerInput(wheel) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type != PointerEventType.Scroll) continue
                val change = event.changes.firstOrNull() ?: continue
                if (!change.isConsumed && wheel.scroll(-change.scrollDelta.y * WheelStep.toPx())) change.consume()
            }
        }
    }
}

/** Sits between the list and the pull-to-refresh node, standing in for the list on wheel scroll it won't take. */
private class WheelPull(private val scope: CoroutineScope) : NestedScrollConnection {
    val dispatcher = NestedScrollDispatcher()
    private var pulling = false
    private var settle: Job? = null

    /** Offers [dy] (positive pulls down) to the pull as scroll the list couldn't consume; true if the pull took some. */
    fun scroll(dy: Float): Boolean {
        val delta = Offset(0f, dy)
        val pre = dispatcher.dispatchPreScroll(delta, NestedScrollSource.UserInput)
        val post = dispatcher.dispatchPostScroll(Offset.Zero, delta - pre, NestedScrollSource.UserInput)
        val took = pre.y != 0f || post.y != 0f
        if (took) {
            pulling = true
            scheduleSettle()
        }
        return took
    }

    // The list scrolling back up while pulled goes to the pull first; keep holding until the wheel stops.
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (pulling) scheduleSettle()
        return Offset.Zero
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        settle?.cancel()
        pulling = false
        return Velocity.Zero
    }

    private fun scheduleSettle() {
        settle?.cancel()
        settle = scope.launch {
            delay(WheelIdleMillis)
            pulling = false
            withContext(NonCancellable) {
                dispatcher.dispatchPreFling(Velocity.Zero)
                dispatcher.dispatchPostFling(Velocity.Zero, Velocity.Zero)
            }
        }
    }
}
