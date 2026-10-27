package io.waves.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Horizontal inset of screen content (list rows, section labels, headers). */
val ScreenGutter = 24.dp

/** Whether the window is wider than tall; provided at the app root. */
val LocalIsLandscapeLayout = staticCompositionLocalOf { false }

/** How wide phone-designed content gets in landscape (wide desktop window, landscape tablet). */
val ContentMaxWidth = 640.dp

/**
 * Keeps phone-designed content readable in landscape: caps it at [maxContentWidth] and centers it. No-op in portrait.
 * Apply at the front of the main content's modifier chain; top bars stay full width. Screens whose backgrounds and
 * dividers should reach the window's edges span it instead and pad each row's content with [contentInset].
 */
@Composable
fun Modifier.adaptiveContentWidth(maxContentWidth: Dp = ContentMaxWidth): Modifier =
    if (LocalIsLandscapeLayout.current) {
        fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = maxContentWidth)
    } else {
        this
    }

/**
 * Space on each side of the centred [ContentMaxWidth] column in a landscape window, 0 in portrait; provided at the
 * app root, where screens span the window.
 */
val LocalContentInset = staticCompositionLocalOf { 0.dp }

internal fun contentInset(windowWidth: Dp, landscape: Boolean): Dp =
    if (landscape) ((windowWidth - ContentMaxWidth) / 2).coerceAtLeast(0.dp) else 0.dp

/**
 * Pads by [LocalContentInset] on both sides: in a full-width row, puts its content in the centred column while the
 * row's own background and divider, drawn before this, span the window.
 */
@Composable
fun Modifier.contentInset(): Modifier = padding(horizontal = LocalContentInset.current)

fun Modifier.verticalFadingEdge(fadeLength: Dp = 8.dp, bottomFadeLength: Dp = fadeLength): Modifier =
    graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        .drawWithContent {
            drawContent()
            val fade = fadeLength.toPx()
            val bottomFade = bottomFadeLength.toPx()
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Transparent,
                    (fade / size.height).coerceIn(0f, 0.5f) to Color.Black,
                    (1f - bottomFade / size.height).coerceIn(0.5f, 1f) to Color.Black,
                    1f to Color.Transparent,
                ),
                blendMode = BlendMode.DstIn,
            )
        }

/** Padding for a screen's main scrolling list: a small top gap and clearance at the bottom. */
@Composable
fun listContentPadding(horizontal: Dp = 0.dp, top: Dp = 8.dp): PaddingValues =
    PaddingValues(start = horizontal, top = top, end = horizontal, bottom = LocalBottomClearance.current)

/**
 * Screen top bar. On a tab root (no [navigationIcon]) inside the tab pager, the title area is reserved for the
 * shared [TabStrip] instead of [title]; pushed screens show Back and their title.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WavesTopBar(
    title: String,
    icon: ImageVector? = null,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    containerColor: Color = MaterialTheme.colorScheme.surface,
    /** Shown instead of [title]. */
    titleContent: (@Composable () -> Unit)? = null,
) {
    val tabPage = LocalTabPage.current.takeIf { navigationIcon == null }
    TopAppBar(
        title = {
            if (titleContent != null) {
                titleContent()
            } else if (tabPage != null) {
                // The strip is centred on the screen within this slot; the title slot starts 16 dp in, the slot 12 dp.
                Box(Modifier.offset(x = (-4).dp).padding(end = 8.dp)) { TabStripPlaceholder(tabPage) }
            } else {
                Row(
                    modifier = if (navigationIcon == null) Modifier.padding(start = 8.dp) else Modifier,
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    icon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        },
        navigationIcon = { navigationIcon?.invoke() },
        actions = actions,
        expandedHeight = if (tabPage != null) TabStripHeight + 10.dp else TopAppBarDefaults.TopAppBarExpandedHeight,
        windowInsets = TopAppBarDefaults.windowInsets.add(WindowInsets(top = if (tabPage != null) TabBarTop else 8.dp)),
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = containerColor,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

/** Above a tab root's top bar: centres the tab strip on the same line as the show page's Back button. */
private val TabBarTop = CollapsedBarTop + CollapsedBarHeight / 2 - (TabStripHeight + 10.dp) / 2

/** Small muted label above a group of content. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        modifier = modifier,
    )
}

/** A labelled group of settings-style rows. */
@Composable
fun Section(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.padding(vertical = 10.dp)) {
        SectionLabel(title, Modifier.padding(bottom = 8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

/** Secondary line under a row title (dates, durations, show names). */
@Composable
fun MetaText(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified, maxLines: Int = 1) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = if (color != Color.Unspecified) color else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}
