package io.waves.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollDispatcher
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.util.lerp
import kotlin.math.ceil
import kotlin.math.floor
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
 * Only a pull opens the gap: a refresh that starts on its own ([isRefreshing] with no pull) leaves the content be,
 * unless [showsRefresh], when it opens the gap with the waves rolling too, as if pulled, until it's over.
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
    /** Where the gap's open part starts: below a divider that stays put along the top, say. */
    gapTop: Dp = 0.dp,
    /** Reports how far the gap is open, for things outside the box that follow it (a divider above, say). */
    progress: PullProgress? = null,
    showsRefresh: Boolean = false,
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
    // otherwise starts the refresh over, and the spinner never leaves. Material sees the lift after this, too.
    val closing = rememberUpdatedState(cycle && !armed)
    val closeGuard = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                if (closing.value && available.y > 0f) Offset(0f, available.y) else Offset.Zero

            override suspend fun onPreFling(available: Velocity): Velocity = when {
                closing.value -> available
                // Flicked up off an open gap: let go as a plain lift would. The flick's speed would otherwise carry
                // on into the list and send it scrolling while the gap closes.
                available.y < 0f && state.distanceFraction > 0f -> Velocity(0f, available.y)
                else -> Velocity.Zero
            }
        }
    }
    // The pull as drawn, in [PullToRefreshState.distanceFraction]s: follows the pull up to the threshold, except that
    // after a refresh it springs back on its own rather than with Material's much quicker hide.
    val shown = remember { Animatable(0f) }
    SideEffect { progress?.source = { shown.value } }
    var bouncing by remember { mutableStateOf(false) }
    // A refresh nobody pulled for, shown all the same: the gap opens to the threshold and holds while it runs.
    val unpulled = showsRefresh && isRefreshing && !armed
    val unpulledNow by rememberUpdatedState(unpulled) // for the lambdas below, which outlive this composition
    LaunchedEffect(state, armed, unpulled) {
        if (unpulled) {
            shown.animateTo(1f, OpenUnpulled)
            return@LaunchedEffect
        }
        if (!armed && shown.value > 0f) {
            bouncing = true
            try {
                shown.animateTo(0f, SpringBack)
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
        indicator = {
            if (!gapInContent) {
                PullSpinner({ shown.value }, armed || unpulled, lead = gapTop, Modifier.align(Alignment.TopCenter).padding(top = gapTop))
            }
        },
    ) {
        // The scroll handler stays put, so a pull can keep going once the pointer is over the gap it opened.
        // Only the content moves down.
        Box(Modifier.fillMaxSize().pullScroll(state, locked = { armed || bouncing || unpulledNow })) {
            if (gapInContent) {
                val gap = remember { PullGapState({ shown.value }, { armed || unpulledNow }) }
                CompositionLocalProvider(LocalPullGap provides gap) { Box(Modifier.fillMaxSize(), content = content) }
            } else {
                Box(
                    Modifier.fillMaxSize().graphicsLayer { translationY = shown.value * PullGapHeight.toPx() },
                    content = content,
                )
            }
        }
    }
}

private class PullGapState(val fraction: () -> Float, val refreshing: () -> Boolean)

/**
 * How far a [WavesPullToRefreshBox]'s gap is open, 0 shut to 1 at the threshold (and while refreshing), for things
 * drawn outside it. Read it while drawing, so they follow the pull frame by frame without recomposing.
 */
class PullProgress internal constructor() {
    internal var source: (() -> Float)? = null
    val value: Float get() = source?.invoke()?.coerceIn(0f, 1f) ?: 0f
}

@Composable
fun rememberPullProgress(): PullProgress = remember { PullProgress() }

/**
 * A divider's colour as the gap under it opens ([pull], 0 to 1): gone as soon as a pull starts, the bottom row of
 * waves, just like it, taking its place and carrying it down ([WaveField]).
 */
@Suppress("UNUSED_PARAMETER")
internal fun pullDividerColor(divider: Color, accent: Color, pull: Float): Color =
    if (pull > 0f) Color.Transparent else divider

private val LocalPullGap = staticCompositionLocalOf<PullGapState?> { null }

/**
 * Where the gap of the [WavesPullToRefreshBox] around it opens, for one made with `gapInContent`: as tall as the pull,
 * with the waves in it, and nothing at all otherwise. With [divider], a divider stays along the gap's top, the line
 * the rows below come down from (the first of them then has none of its own).
 */
@Composable
fun PullGap(modifier: Modifier = Modifier, divider: Boolean = false) {
    val gap = LocalPullGap.current ?: return
    Box(
        modifier.fillMaxWidth()
            .topDivider(divider, tint = { gap.fraction().coerceIn(0f, 1f) })
            .clipToBounds()
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                // The divider keeps its own height, at rest too; the gap opens under it.
                val line = if (divider) ListDividerHeight.roundToPx() else 0
                val height = (gap.fraction() * PullGapHeight.toPx()).roundToInt().coerceAtLeast(0)
                layout(placeable.width, line + height) { placeable.place(0, line) }
            },
    ) {
        Box(Modifier.fillMaxWidth()) {
            PullSpinner(gap.fraction, gap.refreshing(), lead = if (divider) ListDividerHeight else 0.dp, Modifier.align(Alignment.TopCenter))
        }
    }
}

private const val MinSpinMillis = 500L

/** Opens the gap for a refresh that started without a pull. */
private val OpenUnpulled = tween<Float>(durationMillis = 300, easing = FastOutSlowInEasing)

/** Eases the content back up after a refresh, settling without overshooting the top. */
private val SpringBack = spring<Float>(dampingRatio = 1f, stiffness = 300f)

/**
 * How far the content sits down while refreshing: room for the rows of waves, evenly spaced, the top one in the place
 * of the divider above the gap. A pull still has to go Material's threshold to start a refresh; the gap opens in
 * proportion.
 */
internal val PullGapHeight: Dp get() = (WaveSpace + WaveDepth) * WaveRows - ListDividerHeight

/**
 * Fills the gap the pull opens with rows of waves, edge to edge, riding on the pulled-down content as if they lived in
 * the gap: as tall as the gap at the threshold with its bottom on the content's top, it comes down into view from
 * behind the top edge (the box clips it). [lead]: how far above the gap the divider over it sits, whose place the top
 * row takes.
 * Laid out, not drawn into a layer: a layer stops redrawing once the pull is still, and the waves freeze.
 */
@Composable
private fun PullSpinner(fraction: () -> Float, refreshing: Boolean, lead: Dp, modifier: Modifier = Modifier) {
    WaveField(
        fraction = { fraction().coerceIn(0f, 1f) },
        rolling = refreshing,
        lead = lead,
        modifier = modifier.fillMaxWidth().height(PullGapHeight)
            .offset { IntOffset(0, ((fraction() - 1f) * PullGapHeight.toPx()).roundToInt()) },
    )
}

/**
 * [WaveRows] rows of waves, evenly spaced, coming down with the pull. Each starts out as the divider itself and, as the
 * gap opens, opens out into its own waves and colour: the top row keeps the divider's waves, each row down is longer
 * and flatter, the bottom one flattest. As a pull starts, the bottom row takes the divider's place (the divider goes)
 * and carries it down along the content, the others coming into view above it. While [rolling] (a
 * refresh runs), every row rolls on, each its own way and pace, in an endless loop.
 */
@Composable
private fun WaveField(fraction: () -> Float, rolling: Boolean, lead: Dp, modifier: Modifier = Modifier) {
    val track = listDividerColor()
    val accent = MaterialTheme.colorScheme.primary
    val roll = if (rolling) {
        rememberInfiniteTransition(label = "waves").animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(WaveRollMillis, easing = LinearEasing)),
            label = "roll",
        )
    } else {
        null
    }
    Canvas(modifier) {
        val line = ListDividerHeight.toPx()
        val rows = WaveRows
        // The bottom row along the box's bottom, where the divider is at the start of a pull; the top one, once the gap
        // is open, in the divider's place above it ([lead]); the rest evenly between.
        val step = (size.height - line + lead.toPx()) / (rows - 1)
        val pivot = floor(size.width / 2 / DividerPeriod.toPx()) * DividerPeriod.toPx()
        val stroke = Stroke(width = WaveStroke.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val open = fraction()
        // Shut, the divider itself shows; drawn over it too, the bottom row would darken it.
        if (open <= 0f) return@Canvas
        val shift = roll?.value ?: 0f
        for (row in 0 until rows) {
            // 0 at the bottom (the divider's row), 1 at the top.
            val up = (rows - 1 - row) / (rows - 1f)
            val top = size.height - line - (rows - 1 - row) * step
            // Strongest at the top, softest along the content.
            val color = lerp(track, waveColor(track, accent, 1f - up), open)
            // Every row starts out as the divider and, as the gap opens, opens out into its own waves: the top one
            // stays the divider's, each row down longer and flatter, the bottom one flattest.
            val flat = (1f - up) * open
            val period = lerp(DividerPeriod, WavePeriod, flat).toPx()
            val depth = lerp(WaveDepth, FlatDepth, flat).toPx()
            // Rows alternate direction, and every third rolls twice as fast. Whole waves per turn of [roll], so where
            // it starts over every row is just where it was: a loop without a jump.
            val pace = (if (row % 2 == 0) 1f else -1f) * (if (row % 3 == 2) 2f else 1f)
            // Lined up with the divider at first, each row slipping half a wave from the one above as the gap opens.
            val offset = ((shift * pace + (rows - 1 - row) * 0.5f * open) % 1f + 1f) % 1f * period
            // Each row centred on the line a divider would take in its place.
            val y = top + (line - depth) / 2
            drawPath(wavePath(size.width, y, period, depth, offset, pivot), color, style = stroke)
        }
    }
}

/**
 * A row of waves' colour once the gap is open, from the strongest ([soft] 0: fully opaque with a little of the accent)
 * to the softest ([soft] 1), which still keeps some of the accent.
 */
private fun waveColor(track: Color, accent: Color, soft: Float): Color =
    lerp(track, accent, lerp(WaveAccent, TopAccent, soft)).copy(alpha = track.alpha * lerp(1f, TopAlpha, soft))

/**
 * A line of waves across [width], as the divider draws them: concave strokes from the base ([top] plus [depth])
 * meeting in sharp crests at [top], flowing into each other at the base, every [period] from [pivot], moved [offset] to
 * the right.
 */
private fun wavePath(width: Float, top: Float, period: Float, depth: Float, offset: Float, pivot: Float = 0f): Path = Path().apply {
    val base = top + depth
    // Waves keep a base at [pivot] (moved by [offset]), so a change of [period] stretches them out from there.
    var x = pivot + offset - ceil((pivot + offset) / period) * period - period
    moveTo(x, base)
    while (x < width) {
        quadraticTo(x + period * 0.3f, base, x + period / 2, top)
        quadraticTo(x + period * 0.7f, base, x + period, base)
        x += period
    }
}

/**
 * The divider's waves, which every row starts out as and the top row keeps; and the bottom row's, the longest and
 * flattest.
 */
private val DividerPeriod = 12.dp
private val WaveDepth = 3.dp
private val WavePeriod = 15.dp
private val FlatDepth = 2.5.dp
private val WaveStroke = 1.dp

/** Rows of waves in the gap, the bottom one the divider's, spaced evenly up to the divider's place. */
private const val WaveRows = 5

/** Between rows: sets how tall the gap opens. */
private val WaveSpace = 9.dp

/** How much of the accent the strongest row takes on (fully opaque, as the divider is). */
private const val WaveAccent = 0.5f

/** How much of the accent the softest row keeps, and how opaque it is. */
private const val TopAccent = 0.2f
private const val TopAlpha = 0.3f

/** How long the slower rows take to roll on by one wave. */
private const val WaveRollMillis = 2000



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
