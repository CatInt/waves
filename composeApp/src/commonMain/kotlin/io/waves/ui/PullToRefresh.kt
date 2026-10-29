package io.waves.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollDispatcher
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [PullToRefreshBox] that a trackpad or mouse wheel can pull too. A list at its top drops the wheel scroll that would
 * pull (it never reaches the pull's nested scroll), and wheel events carry no finger lift to settle it, so this feeds the
 * pull the wheel scroll the list leaves over and lets go when [LocalWheelGesture] says the fingers lifted, or for a
 * plain wheel once it goes quiet.
 *
 * The content itself moves down with the pull, opening a gap at the top with the spinner in it, never wider than the
 * pull that starts a refresh. Once a pull starts one, the gap stays open and the content holds still, taking no scroll,
 * until the refresh is over; then it springs back up. A pull whose [onRefresh] does nothing (offline, sync
 * rate-limited) spins for [MinSpinMillis] instead. Content whose rows animate into place once a refresh lands passes
 * [settleMillis] to hold that much longer, so the rows finish moving before the whole list does.
 *
 * Only a pull opens the gap: a refresh that starts on its own ([isRefreshing] with no pull) leaves the content be.
 *
 * With [gapInContent], the content stays put and the gap opens where the content places a [PullGap] (a list item
 * under a header, say): only what's below it moves down.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WavesPullToRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    state: PullToRefreshState = rememberPullToRefreshState(),
    settleMillis: Long = 0,
    gapInContent: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    // True from a pull's release until the refresh it started is over. A refresh that nobody pulled for leaves it false,
    // so the content stays put.
    var armed by remember { mutableStateOf(false) }
    // Stays set until the gap has closed, so the coast after a lift can't start the refresh over again.
    var cycle by remember { mutableStateOf(false) }
    var sawRefresh by remember { mutableStateOf(false) }
    LaunchedEffect(armed, isRefreshing) {
        if (!armed) return@LaunchedEffect
        if (isRefreshing) {
            sawRefresh = true
        } else if (sawRefresh) {
            delay(settleMillis)
            sawRefresh = false
            armed = false
        } else {
            delay(MinSpinMillis)
            armed = false
        }
    }
    LaunchedEffect(cycle, armed) {
        if (cycle && !armed) {
            snapshotFlow { state.distanceFraction }.first { it == 0f }
            cycle = false
        }
    }
    // While the gap is closing, swallow a downward fling before Material sees it. A coast after the fingers lift
    // otherwise starts the refresh over, and the spinner never leaves.
    val closing = rememberUpdatedState(cycle && !armed)
    val closeGuard = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                if (closing.value && available.y > 0f) Offset(0f, available.y) else Offset.Zero

            override suspend fun onPreFling(available: Velocity): Velocity =
                if (closing.value) available else Velocity.Zero
        }
    }
    // The pull as drawn, in [PullToRefreshState.distanceFraction]s: follows the pull up to the threshold, except that
    // after a refresh it springs back on its own rather than with Material's much quicker hide.
    val shown = remember { Animatable(0f) }
    var bouncing by remember { mutableStateOf(false) }
    LaunchedEffect(state, armed) {
        if (!armed && shown.value > 0f) {
            bouncing = true
            try {
                shown.animateTo(0f, BounceBack)
                // Material may still be hiding the pull it held; following it before then would yank the content down.
                snapshotFlow { state.distanceFraction }.first { it == 0f }
            } finally {
                bouncing = false
            }
        }
        snapshotFlow { state.distanceFraction }.collect { shown.snapTo(it.coerceAtMost(1f)) }
    }
    PullToRefreshBox(
        isRefreshing = armed,
        onRefresh = {
            if (!cycle) {
                cycle = true
                armed = true
                onRefresh()
            }
        },
        modifier = modifier.clipToBounds().nestedScroll(closeGuard),
        state = state,
        indicator = { if (!gapInContent) PullSpinner({ shown.value }, armed, Modifier.align(Alignment.TopCenter)) },
    ) {
        // The scroll handler stays put, so a pull can keep going once the pointer is over the gap it opened.
        // Only the content moves down.
        Box(Modifier.fillMaxSize().pullScroll(state, locked = { armed || bouncing })) {
            if (gapInContent) {
                val gap = remember { PullGapState({ shown.value }, { armed }) }
                CompositionLocalProvider(LocalPullGap provides gap) { Box(Modifier.fillMaxSize(), content = content) }
            } else {
                Box(
                    Modifier.fillMaxSize().graphicsLayer { translationY = shown.value * PullThreshold.toPx() },
                    content = content,
                )
            }
        }
    }
}

private class PullGapState(val fraction: () -> Float, val refreshing: () -> Boolean)

private val LocalPullGap = staticCompositionLocalOf<PullGapState?> { null }

/**
 * Where the gap of the [WavesPullToRefreshBox] around it opens, for one made with `gapInContent`: as tall as the pull,
 * with the spinner in it, and nothing at all otherwise.
 */
@Composable
fun PullGap(modifier: Modifier = Modifier) {
    val gap = LocalPullGap.current ?: return
    Box(
        modifier.fillMaxWidth()
            .clipToBounds()
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                val height = (gap.fraction() * PullThreshold.toPx()).roundToInt().coerceAtLeast(0)
                layout(placeable.width, height) { placeable.place(0, 0) }
            },
    ) {
        Box(Modifier.fillMaxWidth()) { PullSpinner(gap.fraction, gap.refreshing(), Modifier.align(Alignment.TopCenter)) }
    }
}

private const val MinSpinMillis = 500L

/** Overshoots the top by about 7% of the gap (some 5 dp) before settling. */
private val BounceBack = spring<Float>(dampingRatio = 0.65f, stiffness = 300f)

/** How far the content sits down while refreshing: where a pull starts a refresh, Material's threshold. */
@OptIn(ExperimentalMaterial3Api::class)
private val PullThreshold = PullToRefreshDefaults.PositionalThreshold

/**
 * Rides on top of the pulled-down content, moving with it as if it lived in the gap the pull opens: it comes down
 * into view from behind the top edge (the box clips it) and is centred in the gap once the pull reaches the
 * threshold. Fills in as the pull nears the threshold, then spins.
 * Laid out, not drawn into a layer: a layer stops redrawing once the pull is still, and the spinner freezes.
 */
@Composable
private fun PullSpinner(fraction: () -> Float, refreshing: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(PullThreshold)
            // As tall as the gap at the threshold, its bottom edge on the content's top edge, then [SpinnerDrop] lower so the
            // ring sits a little below the gap's middle.
            .offset { IntOffset(0, ((fraction() - 1f) * PullThreshold.toPx() + SpinnerDrop.toPx()).roundToInt()) },
        contentAlignment = Alignment.Center,
    ) {
        val color = MaterialTheme.colorScheme.primary
        if (refreshing) {
            CircularProgressIndicator(Modifier.size(SpinnerSize), color = color, strokeWidth = SpinnerStroke)
        } else {
            CircularProgressIndicator(
                progress = { fraction().coerceIn(0f, 1f) },
                modifier = Modifier.size(SpinnerSize),
                color = color,
                strokeWidth = SpinnerStroke,
                trackColor = Color.Transparent,
            )
        }
    }
}

private val SpinnerSize = 24.dp

/** How far below the middle of the gap the ring sits; the line above the gap (a divider, a top bar) makes the middle look high. */
private val SpinnerDrop = 10.dp
private val SpinnerStroke = 2.5.dp

/** Scroll per wheel unit, as on macOS (a notch, or a trackpad's precise delta). */
private val WheelStep = 10.dp

/** Without [WheelGesture]s, how long the wheel stays quiet before a pull counts as let go. */
private const val WheelIdleMillis = 150L

/** What's behind the wheel scroll events, where the platform says (macOS trackpads); wheel events alone don't. */
enum class WheelGesture {
    /** A mouse wheel, or no word from the platform: a pull lets go once the wheel goes quiet. */
    Unknown,

    /** Fingers on the trackpad: a pull holds while they rest, as under a finger on a touch screen. */
    Touching,

    /** Fingers lifted: a pull lets go at once. */
    Lifted,

    /** Momentum after the fingers lifted: it scrolls the list but never pulls. */
    Momentum,
}

val LocalWheelGesture = staticCompositionLocalOf<StateFlow<WheelGesture>> { MutableStateFlow(WheelGesture.Unknown) }

/**
 * Between the content and the pull: feeds the pull wheel scroll the content leaves over, stops the pull at the
 * threshold, and while [locked] takes every scroll and fling so the content holds still.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Modifier.pullScroll(state: PullToRefreshState, locked: () -> Boolean): Modifier {
    val scope = rememberCoroutineScope()
    val gesture = LocalWheelGesture.current
    // Read on each scroll, so a pull that starts a refresh locks with the current value.
    val lockedNow by rememberUpdatedState(locked)
    val pull = remember(scope, gesture, state) { PullScroll(scope, gesture, state) { lockedNow() } }
    return nestedScroll(pull, pull.dispatcher).pointerInput(pull) {
        awaitPointerEventScope {
            while (true) {
                // Before the list: once a pull has started, keep the rest of a downward gesture. A grid that has
                // already scrolled this gesture would otherwise swallow it and the pull would stall short.
                val early = awaitPointerEvent(PointerEventPass.Initial)
                // Seen before the list scrolls with it, so the scroll it sets off knows what drives it.
                when (early.type) {
                    PointerEventType.Scroll -> pull.byWheel = true
                    PointerEventType.Press, PointerEventType.Move -> pull.byWheel = false
                }
                if (early.type == PointerEventType.Scroll && gesture.value != WheelGesture.Momentum) {
                    val change = early.changes.firstOrNull()
                    if (change != null && !change.isConsumed) {
                        val dy = -change.scrollDelta.y * WheelStep.toPx()
                        if (dy > 0f && pull.pulling && pull.wheel(dy)) change.consume()
                    }
                }
                val event = awaitPointerEvent()
                if (event.type != PointerEventType.Scroll || gesture.value == WheelGesture.Momentum) continue
                val change = event.changes.firstOrNull() ?: continue
                if (!change.isConsumed && pull.wheel(-change.scrollDelta.y * WheelStep.toPx())) change.consume()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
private class PullScroll(
    private val scope: CoroutineScope,
    private val gesture: StateFlow<WheelGesture>,
    private val state: PullToRefreshState,
    private val locked: () -> Boolean,
) : NestedScrollConnection {
    val dispatcher = NestedScrollDispatcher()
    /** A pull is under way, so the rest of this downward gesture belongs to it. */
    var pulling = false
    /**
     * The scroll comes from a wheel or trackpad, which has no release of its own, rather than a drag. A drag's pull
     * holds until the finger lifts, which Material sees as a fling.
     */
    var byWheel = false
    private var settle: Job? = null

    /** Downward wheel scroll the pull couldn't take yet, because it was still animating the last bit. */
    private var pending = 0f
    private var pump: Job? = null

    // Material starts a refresh only once the pull passes the threshold, so the stop is just past it; the gap drawn
    // never follows past the threshold itself.
    private val pastTrigger get() = state.distanceFraction > 1f

    /** Offers [dy] (positive pulls down) to the pull as wheel scroll the list couldn't consume; true if it's taken. */
    fun wheel(dy: Float): Boolean {
        if (locked()) return true
        if (dy > 0f && (pastTrigger || pulling)) {
            if (!pastTrigger) enqueue(dy)
            pulling = true
            scheduleSettle()
            return true
        }
        if (!offer(dy)) return false
        pulling = true
        scheduleSettle()
        return true
    }

    /** Keeps downward scroll that arrives while the pull is animating, and feeds it on once the animation is idle. */
    private fun enqueue(dy: Float) {
        pending += dy
        if (pump?.isActive == true) return
        pump = scope.launch {
            while (pending > 0f && !pastTrigger && !locked()) {
                if (state.isAnimating) snapshotFlow { state.isAnimating }.first { !it }
                if (pastTrigger || locked()) break
                val dy = pending
                pending = 0f
                if (!offer(dy)) {
                    if (state.isAnimating) pending += dy else break
                }
            }
        }
    }

    private fun offer(dy: Float): Boolean {
        val delta = Offset(0f, dy)
        val pre = dispatcher.dispatchPreScroll(delta, NestedScrollSource.UserInput)
        val post = dispatcher.dispatchPostScroll(Offset.Zero, delta - pre, NestedScrollSource.UserInput)
        return pre.y != 0f || post.y != 0f
    }

    // The list scrolling back up while pulled goes to the pull first; keep holding until the wheel stops.
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (locked()) return available
        // Coasting after the fingers lifted scrolls the list; it must not keep re-arming the refresh.
        if (gesture.value == WheelGesture.Momentum) return Offset.Zero
        if (pulling && byWheel) scheduleSettle()
        return Offset.Zero
    }

    // A drag past the threshold goes no further; while locked, nothing moves the content.
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset = when {
        locked() -> available
        // Leftover the list didn't take. Swallow it so the coast can't pull, without taking the list's own scroll.
        source == NestedScrollSource.UserInput && available.y > 0f && gesture.value == WheelGesture.Momentum ->
            Offset(0f, available.y)
        source == NestedScrollSource.UserInput && available.y > 0f && pastTrigger -> {
            pulling = true
            Offset(0f, available.y)
        }
        source == NestedScrollSource.UserInput && available.y > 0f -> {
            pulling = true
            Offset.Zero
        }
        else -> Offset.Zero
    }

    // Material has had the release by now, so a pull that started a refresh is already locked.
    override suspend fun onPreFling(available: Velocity): Velocity {
        settle?.cancel()
        pulling = false
        return if (locked()) available else Velocity.Zero
    }

    private fun scheduleSettle() {
        // Don't cancel the lift that's about to refresh, and don't arm a new one from the coast.
        if (gesture.value == WheelGesture.Momentum) return
        settle?.cancel()
        settle = scope.launch {
            if (gesture.value == WheelGesture.Unknown) delay(WheelIdleMillis)
            else gesture.first { it != WheelGesture.Touching }
            pump?.cancel()
            withContext(NonCancellable) {
                var spins = 0
                while (pending > 0f && !pastTrigger && spins++ < 8) {
                    if (state.isAnimating) snapshotFlow { state.isAnimating }.first { !it }
                    val dy = pending
                    pending = 0f
                    if (!offer(dy) && state.isAnimating) pending += dy
                }
                // A pull can land exactly on the threshold, and Material only refreshes once it has gone past.
                val before = state.distanceFraction
                if (before in 1f..1.05f && offer(8f)) {
                    // Material moves the pull a frame later. Letting go before then, that move lands mid-release.
                    withTimeoutOrNull(100) { snapshotFlow { state.distanceFraction }.first { it > before } }
                }
                pulling = false
                release()
                dispatcher.dispatchPostFling(Velocity.Zero, Velocity.Zero)
            }
        }
    }

    /**
     * Lets go of the pull. Past the threshold, Material first eases the pull back onto it and only then refreshes, and
     * anything moving the pull meanwhile cancels that ease and the refresh with it, leaving the pull (and its spinner)
     * hanging. Material still holds the pull then, so letting go again picks up where it stopped.
     */
    private suspend fun release() {
        var tries = 0
        while (true) {
            try {
                dispatcher.dispatchPreFling(Velocity.Zero)
                return
            } catch (e: CancellationException) {
                if (++tries == 3) return
            }
        }
    }
}
