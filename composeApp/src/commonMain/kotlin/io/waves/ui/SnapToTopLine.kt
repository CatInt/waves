package io.waves.ui

import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

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
) {
    val line by rememberUpdatedState(topLine)
    val rows by rememberUpdatedState(snappable)
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
    )
}

/**
 * [offsets]: how far each visible row is from where it would rest, in px; the nearest within [reach] is moved there.
 */
@Composable
internal fun SnapToTopLine(
    scrolling: () -> Boolean,
    offsets: () -> List<Int>,
    scrollBy: suspend (Float) -> Unit,
    reach: Dp = SnapReach,
) {
    val reachPx = with(LocalDensity.current) { reach.toPx() }
    LaunchedEffect(Unit) {
        snapshotFlow { scrolling() }.collect { moving ->
            if (moving) return@collect
            // A wheel or trackpad scrolls in bursts with short rests between them; wait for a real stop.
            delay(SettleMillis)
            if (scrolling()) return@collect
            val off = offsets().minByOrNull { abs(it) } ?: return@collect
            if (off == 0 || abs(off) > reachPx) return@collect
            // In a job of its own: a finger (or another scroll) taking over cancels the coroutine that scrolls, which
            // must be the snap, not this watch, or no later stop would snap.
            coroutineScope { launch { scrollBy(off.toFloat()) } }
        }
    }
}

/** How close a row's top has to come to the line to be pulled onto it, unless a list says otherwise. */
private val SnapReach = 48.dp

/** For lists of episode rows, which are tall: a pull of up to about a fifth of one. */
internal val EpisodeSnapReach = 40.dp

private const val SettleMillis = 120L

private val SnapSpring = spring<Float>(stiffness = 600f)
