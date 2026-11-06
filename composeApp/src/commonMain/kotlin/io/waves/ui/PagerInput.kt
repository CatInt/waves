package io.waves.ui

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Whether tab pages follow horizontal wheel / trackpad scrolling through [wheelPaging] rather than the pager's own
 * scrolling. A trackpad swipe arrives as a long stream of scroll events (momentum included) that would otherwise
 * glide through several pages.
 */
internal expect val wheelPagesTabs: Boolean

/**
 * A pause this long between scroll events ends a gesture, momentum included, unless the platform says fingers still
 * rest on the trackpad ([WheelGesture.Touching]): then it goes on until they move again or lift. Fingers coming
 * down again end it too, pause or not.
 */
private const val GestureGapMs = 120L

/** How far (in pages) a gesture must carry the page, in its direction of travel, to turn it. */
private const val TurnFraction = 0.25f

/** Pixels per wheel unit; matches Compose's own scroll speed on macOS. */
private val WheelUnit = 10.dp

/** Horizontally scrollable areas inside the pages, which get first go at horizontal scrolling over them. */
internal class HorizontalScrollRegions {
    internal val nodes = mutableSetOf<Holder>()
    /** The pager itself, to map regions into its pointer coordinates. */
    internal val host = Holder()

    internal class Holder {
        var coordinates: LayoutCoordinates? = null
    }
}

internal val LocalHorizontalScrollRegions = staticCompositionLocalOf<HorizontalScrollRegions?> { null }

/**
 * Marks horizontally scrollable content (e.g. a shelf) so [wheelPaging] lets it scroll before turning the page, and
 * keeps the horizontal drag and fling it can't use (past either end) from reaching the pager.
 */
internal fun Modifier.horizontalScrollRegion(): Modifier = composed {
    val regions = LocalHorizontalScrollRegions.current
    val holder = remember { HorizontalScrollRegions.Holder() }
    DisposableEffect(regions) {
        regions?.nodes?.add(holder)
        onDispose { regions?.nodes?.remove(holder) }
    }
    onGloballyPositioned { holder.coordinates = it }.nestedScroll(KeepHorizontalLeftover)
}

private object KeepHorizontalLeftover : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) = Offset(available.x, 0f)

    override suspend fun onPostFling(consumed: Velocity, available: Velocity) = Velocity(available.x, 0f)
}

/**
 * Lets [pager] follow horizontal scroll gestures like a finger drag, capped at one page either side of where the
 * gesture began, then snaps to a page when the gesture ends. Each gesture's axis is judged from its first bit of
 * travel and then kept: a horizontal one is taken before the pages see it (so a vertical list under the pointer
 * can't swallow its slightly diagonal events) and drives the pager to its end; anything else, or a horizontal
 * gesture over a [horizontalScrollRegion], is left entirely to the content.
 */
internal fun Modifier.wheelPaging(
    pager: PagerState,
    scope: CoroutineScope,
    regions: HorizontalScrollRegions,
    wheelGesture: StateFlow<WheelGesture> = MutableStateFlow(WheelGesture.Unknown),
): Modifier {
    val self = regions.host
    return onGloballyPositioned { self.coordinates = it }.pointerInput(pager, regions, wheelGesture) {
        val unitPx = WheelUnit.toPx()

        fun PointerEvent.scrollChange(): PointerInputChange? =
            if (type == PointerEventType.Scroll) changes.firstOrNull()?.takeIf { !it.isConsumed } else null

        fun overRegion(position: Offset): Boolean {
            val pagerCoords = self.coordinates?.takeIf { it.isAttached } ?: return false
            return regions.nodes.any { region ->
                val c = region.coordinates?.takeIf { it.isAttached } ?: return@any false
                pagerCoords.localBoundingBoxOf(c, clipBounds = true).contains(position)
            }
        }

        // The latest page gesture: following its deltas, held, or settling on its page.
        var page: PageGesture? = null
        // While fingers resting on the trackpad hold [page] open: the touch that holds it, else -1.
        var heldTouch = -1
        var heldWatch: Job? = null
        val touches = Touches(wheelGesture)
        coroutineScope {
            launch { wheelGesture.collect { touches.note(it) } }
            awaitPointerEventScope {
                // The first event of the next gesture, when it was already taken from the stream.
                var pending: PointerInputChange? = null
                while (true) {
                    // A gesture: scroll events until a pause or a new touch. Its axis is decided once it has
                    // travelled LockUnits.
                    var change = pending ?: awaitPointerEvent(PointerEventPass.Initial).scrollChange() ?: continue
                    pending = null
                    touches.note()
                    val touch = touches.count
                    var sumX = 0f
                    var sumY = 0f
                    var axis = Axis.Undecided
                    // The page gesture this one feeds, once it's judged one.
                    var fed: PageGesture? = null
                    // Fingers that rested and now move again carry on the gesture they held; fingers that lifted
                    // and came down again start a new one, and momentum on its own (after a lift) never starts one.
                    heldWatch?.cancel()
                    page?.takeIf { heldTouch >= 0 }?.let { held ->
                        if (heldTouch == touch) {
                            axis = Axis.Pages
                            fed = held
                        } else {
                            held.deltas.close()
                        }
                    }
                    heldTouch = -1
                    if (axis == Axis.Undecided && wheelGesture.value == WheelGesture.Momentum) axis = Axis.Other
                    var newTouch = false
                    while (true) {
                        val d = change.scrollDelta
                        when (axis) {
                            Axis.Undecided -> {
                                sumX += d.x; sumY += d.y
                                if (abs(sumX) + abs(sumY) >= LockUnits) {
                                    axis = when {
                                        abs(sumX) <= abs(sumY) * 1.5f -> Axis.Other
                                        overRegion(change.position) -> Axis.Other
                                        else -> Axis.Pages
                                    }
                                    if (axis == Axis.Pages) {
                                        change.consume()
                                        val previous = page
                                        val gesture = PageGesture(direction = sumX)
                                        gesture.job = scope.launch { followGesture(pager, gesture, previous, unitPx) }
                                        gesture.deltas.trySend(sumX)
                                        page = gesture
                                        fed = gesture
                                    }
                                }
                            }
                            Axis.Pages -> {
                                change.consume()
                                fed?.let { gesture ->
                                    if (d.x != 0f) gesture.direction = d.x
                                    gesture.deltas.trySend(d.x)
                                }
                            }
                            Axis.Other -> Unit
                        }
                        change = withTimeoutOrNull(GestureGapMs) {
                            var next: PointerInputChange? = null
                            while (next == null) next = awaitPointerEvent(PointerEventPass.Initial).scrollChange()
                            next
                        } ?: break
                        touches.note()
                        if (touches.count != touch) {
                            // Fingers came down again with no pause (as the last swipe glides on): a new swipe.
                            pending = change
                            newTouch = true
                            break
                        }
                    }
                    val open = fed?.deltas
                    if (!newTouch && open != null && wheelGesture.value == WheelGesture.Touching) {
                        // Resting fingers hold the page where they left it, as a finger would, until they lift.
                        heldTouch = touch
                        heldWatch = scope.launch {
                            wheelGesture.first { it != WheelGesture.Touching }
                            open.close()
                            heldTouch = -1
                        }
                    } else {
                        open?.close()
                    }
                }
            }
        }
    }
}

/**
 * Counts the times fingers come down on the trackpad, from the [WheelGesture]s [note]d: watched as the state
 * changes, and looked at again as each scroll event arrives, whichever sees the change first.
 */
private class Touches(private val gesture: StateFlow<WheelGesture>) {
    var count = 0
        private set
    private var last = gesture.value

    fun note(now: WheelGesture = gesture.value) {
        if (now == WheelGesture.Touching && last != WheelGesture.Touching) count++
        last = now
    }
}

/**
 * A gesture turning the pages: the deltas it feeds the pager, which way it last went, and the page it settles on
 * once they end.
 */
private class PageGesture(var direction: Float) {
    val deltas = Channel<Float>(Channel.UNLIMITED)
    lateinit var job: Job
    val landing = CompletableDeferred<Int>()
}

private enum class Axis { Undecided, Pages, Other }

/** Travel (in wheel units) before a gesture is judged horizontal or not; small, so the page follows promptly. */
private const val LockUnits = 1.5f

/**
 * One scroll session per gesture keeps the pager "in progress", so settledPage (and the tab) only change once it
 * lands; then snaps to the page the gesture carried it to (at most one either side). A gesture that starts while
 * the [previous] one is still settling counts from the page that one is settling on, so quick swipes turn a page
 * each.
 */
private suspend fun followGesture(
    pager: PagerState,
    gesture: PageGesture,
    previous: PageGesture?,
    unitPx: Float,
) {
    fun position() = pager.currentPage + pager.currentPageOffsetFraction
    try {
        val start = previous?.takeIf { it.job.isActive }?.let { settling ->
            settling.deltas.close()
            val landing = settling.landing.await()
            // This gesture takes the pager from here: the last one stops settling.
            settling.job.cancel()
            landing
        } ?: position().roundToInt()
        val pageSize = (pager.layoutInfo.pageSize + pager.layoutInfo.pageSpacing).coerceAtLeast(1)
        val min = (start - 1).coerceAtLeast(0).toFloat()
        val max = (start + 1).coerceAtMost(pager.pageCount - 1).toFloat()
        pager.scroll(MutatePriority.UserInput) {
            for (units in gesture.deltas) {
                val target = (position() + units * unitPx / pageSize).coerceIn(min, max)
                scrollBy((target - position()) * pageSize)
            }
        }
        val moved = position() - start
        val target = when {
            moved >= TurnFraction && gesture.direction > 0f -> start + 1
            moved <= -TurnFraction && gesture.direction < 0f -> start - 1
            else -> start
        }.coerceIn(0, pager.pageCount - 1)
        gesture.landing.complete(target)
        pager.animateScrollToPage(target)
    } finally {
        // Cut short before it knew where to land: where the pager is will do (a no-op once it knew).
        gesture.landing.complete(position().roundToInt().coerceIn(0, pager.pageCount - 1))
    }
}
