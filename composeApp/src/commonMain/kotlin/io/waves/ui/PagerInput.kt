package io.waves.ui

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Whether tab pages follow horizontal wheel / trackpad scrolling through [wheelPaging] rather than the pager's own
 * scrolling. A trackpad swipe arrives as a long stream of scroll events (momentum included) that would otherwise
 * glide through several pages.
 */
internal expect val wheelPagesTabs: Boolean

/** A pause this long between scroll events ends a gesture, momentum included. */
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

/** Marks horizontally scrollable content (e.g. a shelf) so [wheelPaging] lets it scroll before turning the page. */
internal fun Modifier.horizontalScrollRegion(): Modifier = composed {
    val regions = LocalHorizontalScrollRegions.current
    val holder = remember { HorizontalScrollRegions.Holder() }
    DisposableEffect(regions) {
        regions?.nodes?.add(holder)
        onDispose { regions?.nodes?.remove(holder) }
    }
    onGloballyPositioned { holder.coordinates = it }
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
): Modifier {
    val self = regions.host
    return onGloballyPositioned { self.coordinates = it }.pointerInput(pager, regions) {
        val unitPx = WheelUnit.toPx()
        var gesture: Job? = null

        fun PointerEvent.scrollChange(): PointerInputChange? =
            if (type == PointerEventType.Scroll) changes.firstOrNull()?.takeIf { !it.isConsumed } else null

        fun overRegion(position: Offset): Boolean {
            val pagerCoords = self.coordinates?.takeIf { it.isAttached } ?: return false
            return regions.nodes.any { region ->
                val c = region.coordinates?.takeIf { it.isAttached } ?: return@any false
                pagerCoords.localBoundingBoxOf(c, clipBounds = true).contains(position)
            }
        }

        awaitPointerEventScope {
            while (true) {
                // A gesture: scroll events until a pause. Its axis is decided once it has travelled LockUnits.
                var change = awaitPointerEvent(PointerEventPass.Initial).scrollChange() ?: continue
                var sumX = 0f
                var sumY = 0f
                var axis = Axis.Undecided
                var deltas: Channel<Float>? = null
                var direction = 0f
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
                                    direction = sumX
                                    gesture?.cancel()
                                    val channel = Channel<Float>(Channel.UNLIMITED).also { deltas = it }
                                    gesture = scope.launch { followGesture(pager, channel, unitPx) { direction } }
                                    channel.trySend(sumX)
                                }
                            }
                        }
                        Axis.Pages -> {
                            change.consume()
                            if (d.x != 0f) direction = d.x
                            deltas?.trySend(d.x)
                        }
                        Axis.Other -> Unit
                    }
                    change = withTimeoutOrNull(GestureGapMs) {
                        var next: PointerInputChange? = null
                        while (next == null) next = awaitPointerEvent(PointerEventPass.Initial).scrollChange()
                        next
                    } ?: break
                }
                deltas?.close()
            }
        }
    }
}

private enum class Axis { Undecided, Pages, Other }

/** Travel (in wheel units) before a gesture is judged horizontal or not; small, so the page follows promptly. */
private const val LockUnits = 1.5f

/**
 * One scroll session per gesture keeps the pager "in progress", so settledPage (and the tab) only change once it
 * lands; then snaps to the page the gesture carried it to (at most one either side).
 */
private suspend fun followGesture(pager: PagerState, deltas: Channel<Float>, unitPx: Float, direction: () -> Float) {
    val start = (pager.currentPage + pager.currentPageOffsetFraction).roundToInt()
    val pageSize = (pager.layoutInfo.pageSize + pager.layoutInfo.pageSpacing).coerceAtLeast(1)
    val min = (start - 1).coerceAtLeast(0).toFloat()
    val max = (start + 1).coerceAtMost(pager.pageCount - 1).toFloat()
    fun position() = pager.currentPage + pager.currentPageOffsetFraction
    pager.scroll(MutatePriority.UserInput) {
        for (units in deltas) {
            val target = (position() + units * unitPx / pageSize).coerceIn(min, max)
            scrollBy((target - position()) * pageSize)
        }
    }
    val moved = position() - start
    val target = when {
        moved >= TurnFraction && direction() > 0f -> start + 1
        moved <= -TurnFraction && direction() < 0f -> start - 1
        else -> start
    }.coerceIn(0, pager.pageCount - 1)
    pager.animateScrollToPage(target)
}
