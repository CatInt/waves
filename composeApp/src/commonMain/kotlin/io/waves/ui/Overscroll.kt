package io.waves.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.overscroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity

/**
 * The platform's overscroll (Android's stretch, as on Settings) for a horizontal list inside a
 * [horizontalScrollRegion]. The region swallows the scroll left over at the list's ends so the pager behind
 * doesn't take it, and the list's own overscroll only ever sees what's left after that: nothing. This sits between
 * the two and hands that leftover to the overscroll instead.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.horizontalOverscroll(): Modifier {
    val effect = ScrollableDefaults.overscrollEffect()
    val connection = remember(effect) { LeftoverToOverscroll(effect) }
    return nestedScroll(connection).overscroll(effect)
}

@OptIn(ExperimentalFoundationApi::class)
private class LeftoverToOverscroll(private val effect: OverscrollEffect) : NestedScrollConnection {
    // Scrolling back while stretched first eases the stretch, as the list's own overscroll would.
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (!effect.isInProgress || available.x == 0f) return Offset.Zero
        var left = Offset.Zero
        effect.applyToScroll(Offset(available.x, 0f), source) { rest -> left = rest; rest }
        return Offset(available.x - left.x, 0f)
    }

    // At an end: what the list couldn't scroll stretches it.
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (available.x == 0f) return Offset.Zero
        effect.applyToScroll(Offset(available.x, 0f), source) { Offset.Zero }
        return Offset(available.x, 0f)
    }

    // Letting go while stretched springs it back rather than flinging the list.
    override suspend fun onPreFling(available: Velocity): Velocity {
        if (!effect.isInProgress) return Velocity.Zero
        effect.applyToFling(Velocity(available.x, 0f)) { Velocity.Zero }
        return Velocity(available.x, 0f)
    }

    // A fling that runs into an end bounces off it.
    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        if (available.x == 0f) return Velocity.Zero
        effect.applyToFling(Velocity(available.x, 0f)) { it }
        return Velocity(available.x, 0f)
    }
}
