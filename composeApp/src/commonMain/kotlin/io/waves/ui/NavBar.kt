package io.waves.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import kotlin.math.abs
import kotlin.math.roundToInt

val Tab.label: String
    get() = when (this) {
        Tab.Home -> "Home"
        Tab.Discover -> "Discover"
        Tab.History -> "History"
        Tab.Settings -> "Settings"
    }

val Tab.icon: ImageVector
    get() = when (this) {
        Tab.Home -> Icons.Default.Home
        Tab.Discover -> Icons.Default.Explore
        Tab.History -> Icons.Default.History
        Tab.Settings -> Icons.Default.Settings
    }

/** Space scrolling content should leave at its bottom so its last item can scroll clear of the floating player. */
val LocalBottomClearance = staticCompositionLocalOf { 0.dp }

/** Bottom list clearance, from the screen bottom, while the floating mini player is shown. */
internal fun bottomBarClearance(systemBottom: Dp, landscape: Boolean): Dp =
    miniBottomGap(systemBottom, landscape) + MiniPlayerHeight + 26.dp

/** Distance from the screen bottom to the floating player's bottom edge. */
@Composable
internal fun floatingBarBottomGap(): Dp =
    miniBottomGap(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(), LocalIsLandscapeLayout.current)

/**
 * Hides the floating bottom bar when the user scrolls content down past a small threshold and shows it again on
 * scroll up. Distances accumulate (and reset on direction change) so slow drags work too.
 */
internal class BottomBarScrollConnection(density: Density) : NestedScrollConnection {
    var hidden by mutableStateOf(false)
    private val hideDistancePx = with(density) { 24.dp.toPx() }
    private val showDistancePx = with(density) { 12.dp.toPx() }
    private var accumulated = 0f

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        val dy = available.y
        if (dy != 0f && (dy > 0f) != (accumulated > 0f)) accumulated = 0f
        accumulated += dy
        if (accumulated < -hideDistancePx) hidden = true
        else if (accumulated > showDistancePx) hidden = false
        return Offset.Zero
    }

    fun reset() {
        hidden = false
        accumulated = 0f
    }
}

@Composable
internal fun rememberBottomBarScrollConnection(): BottomBarScrollConnection {
    val density = LocalDensity.current
    return remember(density) { BottomBarScrollConnection(density) }
}

/**
 * Where the tab roots' top bars leave room for the shared [TabStrip]. The app shell draws one strip there, above
 * the tab pager, so it stays put while pages slide underneath it.
 */
internal class TabStripSlot {
    /** Top-left of the reserved area, relative to its page (the same on every page). */
    var offset by mutableStateOf(IntOffset.Zero)
    /** Width available on each tab's page (they differ by the number of top bar actions). */
    val widths = mutableStateMapOf<Tab, Int>()
}

/** A page of the tab pager; [coordinates] lets its top bar report the slot position relative to the page. */
internal class TabPage(val tab: Tab, val slot: TabStripSlot) {
    var coordinates: LayoutCoordinates? = null
}

internal val LocalTabPage = staticCompositionLocalOf<TabPage?> { null }

/** Height of [TabStrip]: 36 dp chips in 4 dp padding. */
internal val TabStripHeight = 44.dp

/** Reserves the [TabStrip] area in a tab page's top bar. */
@Composable
internal fun TabStripPlaceholder(page: TabPage) {
    Spacer(
        Modifier.fillMaxWidth().height(TabStripHeight).onGloballyPositioned { coordinates ->
            val pageCoordinates = page.coordinates ?: return@onGloballyPositioned
            page.slot.offset = pageCoordinates.localPositionOf(coordinates, Offset.Zero).round()
            page.slot.widths[page.tab] = coordinates.size.width
        },
    )
}

/**
 * Segmented pill of the app's tabs. [position] is the pager position (page index plus swipe fraction): the chip
 * under it is highlighted and shows its label, both blending continuously into the neighbour while swiping. In
 * landscape there is room for every label.
 */
@Composable
internal fun TabStrip(position: Float, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier, offline: Boolean = false) {
    val showAllLabels = LocalIsLandscapeLayout.current
    Row(
        modifier
            .height(TabStripHeight)
            .horizontalScroll(rememberScrollState())
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tab.entries.forEachIndexed { index, tab ->
            val selection = (1f - abs(position - index)).coerceIn(0f, 1f)
            val icon = if (offline && tab == Tab.Home) Icons.Default.CloudOff else tab.icon
            TabChip(tab, icon, selection, labelShown = if (showAllLabels) 1f else selection) { onSelect(tab) }
        }
    }
}

/** [selection] and [labelShown] run 0..1 so the chip can sit part-way between unselected and selected. */
@Composable
private fun TabChip(tab: Tab, icon: ImageVector, selection: Float, labelShown: Float, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val content = lerp(colors.onSurfaceVariant, colors.onSecondaryContainer, selection)
    Row(
        Modifier
            .height(36.dp)
            .clip(CircleShape)
            .drawBehind { drawRect(colors.secondaryContainer.copy(alpha = colors.secondaryContainer.alpha * selection)) }
            .selectable(selected = selection > 0.5f, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 10.dp + 4.dp * labelShown),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = content)
        // The label stays composed (and readable by accessibility) but is revealed by width.
        Text(
            tab.label,
            style = MaterialTheme.typography.labelLarge,
            color = content,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .clipToBounds()
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity))
                    val gap = 6.dp.roundToPx()
                    val width = ((placeable.width + gap) * labelShown).roundToInt()
                    layout(width, placeable.height) {
                        placeable.placeWithLayer(gap, 0) { alpha = labelShown }
                    }
                },
        )
    }
}
