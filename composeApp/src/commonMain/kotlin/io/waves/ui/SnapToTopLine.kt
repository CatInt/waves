package io.waves.ui

import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Once [state] comes to rest with a row's top (where its divider is drawn) just above or below the list's top divider,
 * scrolls the little that lays the one on the other, so the list doesn't stop with a sliver of a row, or of the gap
 * above one, under the divider. [topLine] is where that divider's top sits, in px from the top of the list (not of
 * its content: a top padding that keeps the first row clear of the divider counts too); only rows [snappable] says
 * are rows count (not a hero above them, say). The first row has no divider of its own and rests below the padding.
 */
@Composable
internal fun SnapToTopLine(
    state: LazyListState,
    topLine: () -> Int = { 0 },
    snappable: (LazyListItemInfo) -> Boolean = { true },
    reach: Dp = SnapReach,
    guides: SnapGuides? = null,
) {
    val line by rememberUpdatedState(topLine)
    val rows by rememberUpdatedState(snappable)
    val divider = with(LocalDensity.current) { ListDividerHeight.toPx() }
    SnapToTopLine(
        scrolling = { state.isScrollInProgress },
        offsets = {
            val info = state.layoutInfo
            info.visibleItemsInfo.filter(rows).map { item ->
                if (item.index == 0) item.offset else item.offset - info.viewportStartOffset - line()
            }
        },
        scrollBy = { state.animateScrollBy(it, SnapSpring) },
        reach = reach,
        uncover = { guides?.uncover(line().toFloat(), divider) },
    )
}

/**
 * [offsets]: how far each visible row is from where it would rest, in px; the nearest within [reach] is moved there.
 * Failing that, [uncover] says how far to move so the divider doesn't rest across a row's text, if it does.
 */
@Composable
internal fun SnapToTopLine(
    scrolling: () -> Boolean,
    offsets: () -> List<Int>,
    scrollBy: suspend (Float) -> Unit,
    reach: Dp = SnapReach,
    uncover: () -> Float? = { null },
) {
    val reachPx = with(LocalDensity.current) { reach.toPx() }
    LaunchedEffect(Unit) {
        snapshotFlow { scrolling() }.collect { moving ->
            if (moving) return@collect
            // A wheel or trackpad scrolls in bursts with short rests between them; wait for a real stop.
            delay(SettleMillis)
            if (scrolling()) return@collect
            val nearest = offsets().minByOrNull { abs(it) }
            val off = nearest?.takeIf { abs(it) <= reachPx }?.toFloat() ?: uncover() ?: return@collect
            if (off == 0f) return@collect
            // In a job of its own: a finger (or another scroll) taking over cancels the coroutine that scrolls, which
            // must be the snap, not this watch, or no later stop would snap.
            coroutineScope { launch { scrollBy(off) } }
        }
    }
}

/**
 * The blocks inside a list's rows that its top divider shouldn't come to rest across, a title or a line of buttons
 * cut in two, and where they are. A list marks itself with [snapGuideList] and hands these to its rows, which mark
 * their blocks with [keepWhole].
 */
class SnapGuides internal constructor() {
    internal var list: LayoutCoordinates? = null
    internal val blocks = mutableSetOf<Block>()

    internal class Block {
        var coordinates: LayoutCoordinates? = null
    }

    /**
     * How far to scroll so no block is cut by the divider lying from [line] (px from the list's top) for [divider]
     * px: to the nearer of showing the block whole just below the divider or tucking it whole above. Null when no
     * block is cut.
     */
    internal fun uncover(line: Float, divider: Float): Float? {
        val list = list?.takeIf { it.isAttached } ?: return null
        val moves = blocks.mapNotNull { block ->
            val at = block.coordinates?.takeIf { it.isAttached } ?: return@mapNotNull null
            val box = list.localBoundingBoxOf(at, clipBounds = false)
            if (box.top >= line + divider || box.bottom <= line) return@mapNotNull null
            val show = box.top - (line + divider)
            val tuck = box.bottom - line
            if (abs(show) <= abs(tuck)) show else tuck
        }
        return moves.minByOrNull { abs(it) }
    }
}

@Composable
internal fun rememberSnapGuides(): SnapGuides = remember { SnapGuides() }

/** Marks the list whose rows' blocks [guides] measures against. */
internal fun Modifier.snapGuideList(guides: SnapGuides): Modifier = onGloballyPositioned { guides.list = it }

/**
 * A block of a row (a line of text, a row of buttons) the list's top divider shouldn't come to rest across; nothing
 * without [guides].
 */
@Composable
internal fun Modifier.keepWhole(guides: SnapGuides?): Modifier {
    if (guides == null) return this
    val block = remember { SnapGuides.Block() }
    DisposableEffect(guides, block) {
        guides.blocks += block
        onDispose { guides.blocks -= block }
    }
    return onGloballyPositioned { block.coordinates = it }
}

/** How close a row's top has to come to the line to be pulled onto it, unless a list says otherwise. */
private val SnapReach = 48.dp

/** For lists of episode rows, which are tall: a pull of up to about a fifth of one. */
internal val EpisodeSnapReach = 40.dp

private const val SettleMillis = 120L

private val SnapSpring = spring<Float>(stiffness = 600f)
