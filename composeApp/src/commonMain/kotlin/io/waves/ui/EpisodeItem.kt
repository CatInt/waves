package io.waves.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import io.waves.model.Podcast
import kotlin.math.ceil
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Secondary actions sit on the pill line, so they are a notch smaller than the default 48dp. */
private val RowControlSize = 40.dp

/**
 * One episode in a list: a header (the show's cover, title and the episode's age in mixed-show lists, otherwise
 * just the age), the title (after the episode's cover when given), a short description, then a play pill with the secondary actions beside it.
 *
 * @param show when set (mixed-show lists), shows its cover and title in the header; tapping them opens the show.
 * @param artworkUrl the episode cover, at the start of the title line, when [show] is null.
 * @param showArtworkUrl the show's cover, whose colour tints the play icon of episodes not started yet.
 * @param showDescription false in narrow lists (the menu bar panel) to keep rows short.
 * @param divider draws a hairline above the row; lists pass false for their first row.
 * @param dividerBelow whether the next row draws a divider, so the press ripple reaches down to it.
 * @param dividerAbove whether the row sits right under a [ListDivider] that stays put (a first row under a pinned
 *   shelf), so the press ripple reaches up to it.
 * @param onToggleComplete after the download control: marks the episode complete; hidden once it's played.
 */
@Composable
fun EpisodeItem(
    row: EpisodeRow,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    modifier: Modifier = Modifier,
    show: Podcast? = null,
    artworkUrl: String? = null,
    showArtworkUrl: String? = show?.artworkUrl,
    onOpenShow: (() -> Unit)? = null,
    badge: String? = null,
    downloadControl: (@Composable (Modifier) -> Unit)? = null,
    showDescription: Boolean = true,
    divider: Boolean = false,
    dividerBelow: Boolean = false,
    dividerAbove: Boolean = false,
    onToggleComplete: (() -> Unit)? = null,
    /** The list's [SnapGuides], which keep its top divider from resting across the row's text. */
    snapGuides: SnapGuides? = null,
) {
    val episode = row.episode
    val colors = MaterialTheme.colorScheme
    val age = episode.publishedAtEpochMs?.let { formatAge(it) }
    // The small age text has more space above its letters than a cover has; rows starting with it sit higher so
    // their content starts as far below the divider as rows starting with the show's cover.
    val leadingTrim = if (show == null && (age != null || badge != null)) AgeLineLeading else 0.dp
    Column(
        modifier.fillMaxWidth()
            .topDivider(divider)
            .zigzagClickable(top = divider, bottom = dividerBelow, above = dividerAbove && !divider, onClick = onPlay)
            .contentInset()
            // The divider is drawn inside the top padding; without one, content keeps the same distance from
            // whatever line is above the row.
            .padding(
                start = ScreenGutter, end = ScreenGutter - 8.dp, bottom = 6.dp,
                top = (if (divider) 12.dp else 12.dp - ZigzagHeight - ZigzagStroke) - leadingTrim,
            ),
    ) {
        if (show != null) {
            val anchor = rememberCoverAnchor(show.feedUrl, corner = 5.dp)
            Row(
                // The list's top divider comes to rest above or below each block of the row, never across one.
                Modifier.padding(end = 8.dp, bottom = 4.dp).keepWhole(snapGuides).then(
                    if (onOpenShow != null) {
                        Modifier.clickable(
                            interactionSource = null, indication = null,
                            onClickLabel = "Open ${show.title}",
                        ) {
                            anchor.mark()
                            onOpenShow()
                        }
                    } else {
                        Modifier
                    },
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ListRowGap),
            ) {
                Artwork(
                    show.artworkUrl ?: episode.artworkUrl, show.title, anchor.modifier, size = 28.dp, corner = 5.dp,
                    onLoaded = anchor.onLoaded,
                )
                Column {
                    Text(
                        show.title,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.sp),
                        color = colors.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    AgeLine(age, badge)
                }
            }
        } else if (age != null || badge != null) {
            AgeLine(
                age, badge, Modifier.padding(bottom = if (artworkUrl != null) 6.dp else 2.dp).keepWhole(snapGuides),
            )
        }
        Row(
            Modifier.padding(end = 8.dp).keepWhole(snapGuides),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ListRowGap),
        ) {
            if (show == null && artworkUrl != null) {
                Artwork(artworkUrl, episode.title, size = 28.dp, corner = 5.dp)
            }
            Text(
                episode.title,
                style = listTitleStyle(),
                color = if (row.played && !isCurrent) colors.onSurfaceVariant else colors.onSurface,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        val summary = if (showDescription) episode.description?.let(::summaryText)?.takeIf { it.isNotEmpty() } else null
        if (summary != null) {
            Text(
                summary,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 16.sp),
                color = colors.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp, end = 8.dp).keepWhole(snapGuides),
            )
        }
        // As tall as its buttons even when a played episode shows none, so rows keep one height.
        Row(
            Modifier.padding(top = 4.dp).height(RowControlSize).keepWhole(snapGuides),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides RowControlSize) {
                PlayPill(row, isCurrent, isPlaying, showArtworkUrl, onClick = if (isPlaying) onPause else onPlay)
                Spacer(Modifier.width(6.dp))
                downloadControl?.invoke(Modifier.size(RowControlSize))
                if (onToggleComplete != null && !row.played) {
                    IconButton(onClick = onToggleComplete, modifier = Modifier.size(RowControlSize)) {
                        CompleteGlyph("Mark complete")
                    }
                }
            }
        }
    }
}

@Composable
internal fun listDividerColor(): Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)

/** Gap between a list row's leading cover or icon and its text. */
internal val ListRowGap = 10.dp

/** Vertical padding of single-line-group list rows (search results, history). */
internal val ListRowVerticalPadding = 10.dp

/** Title of a list row: a notch below titleMedium so two lines stay compact. */
@Composable
internal fun listTitleStyle(): TextStyle = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp, lineHeight = 19.sp)

private val ZigzagPeriod = 12.dp
private val AgeLineLeading = 2.dp
private val ZigzagHeight = 3.dp
private val ZigzagStroke = 1.dp

/** Height a zigzag divider takes up, as drawn by [ListDivider] and [topDivider]. */
internal val ListDividerHeight = ZigzagHeight + ZigzagStroke

/**
 * Full-width zigzag across the top of a list row; lists pass false for their first row. [tint]: how far a pull-to-refresh
 * gap under it is open, which colours it towards the waves there ([pullDividerColor]).
 */
@Composable
internal fun Modifier.topDivider(show: Boolean, tint: () -> Float = { 0f }): Modifier {
    if (!show) return this
    val color = listDividerColor()
    val accent = MaterialTheme.colorScheme.primary
    return drawBehind { drawZigzag(pullDividerColor(color, accent, tint()), top = 0f) }
}

/**
 * Clickable whose press ripple is clipped to the zigzag dividers around the row: its top edge follows the row's own
 * divider ([top]) and, with [bottom], it reaches past the row's bottom to follow the next row's divider, so
 * neighbouring ripples meet along the line. With [above] (and no divider of its own) it reaches up under a
 * [ListDivider] just above the row, whose [BelowListDivider] clip cuts it to the line. Only the row itself takes taps.
 */
@Composable
internal fun Modifier.zigzagClickable(top: Boolean, bottom: Boolean, above: Boolean = false, onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val reach = if (bottom) ListDividerHeight else 0.dp
    val rise = if (above) ListDividerHeight else 0.dp
    val shape = remember(top, reach) { ZigzagEdgesShape(top, reach) }
    return this
        // Reports the row's own height while the ripple layer inside runs [rise] further up and [reach] further down.
        .layout { measurable, constraints ->
            val up = rise.roundToPx()
            val extra = up + reach.roundToPx()
            val maxHeight = if (constraints.hasBoundedHeight) constraints.maxHeight + extra else Constraints.Infinity
            val placeable = measurable.measure(constraints.copy(maxHeight = maxHeight))
            layout(placeable.width, (placeable.height - extra).coerceIn(constraints.minHeight, constraints.maxHeight)) {
                placeable.place(0, -up)
            }
        }
        .clip(shape)
        .indication(interaction, ripple(color = MaterialTheme.colorScheme.primary))
        .layout { measurable, constraints ->
            val up = rise.roundToPx()
            val extra = up + reach.roundToPx()
            val maxHeight = if (constraints.hasBoundedHeight) (constraints.maxHeight - extra).coerceAtLeast(0) else Constraints.Infinity
            val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = maxHeight))
            layout(placeable.width, placeable.height + extra) { placeable.place(0, up) }
        }
        .clickable(interactionSource = interaction, indication = null, onClick = onClick)
}

/** Everything below a [ListDivider] drawn at the top: clips content scrolling up under the divider to its line. */
internal val BelowListDivider: Shape = ZigzagEdgesShape(top = true, reach = 0.dp)

/** Everything above a [ListDivider] whose top is [ListDividerHeight] above the bottom: a panel ending at its line. */
internal val AboveListDivider: Shape = ZigzagEdgesShape(top = false, reach = ListDividerHeight)

/**
 * The area between a row's zigzag divider ([top]; a straight edge without it) and, [reach] below its bottom, the
 * next row's (a straight bottom edge when [reach] is zero). The zigzags are those [drawZigzag] draws.
 */
private class ZigzagEdgesShape(private val top: Boolean, private val reach: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline = with(density) {
        val period = ZigzagPeriod.toPx()
        val peak = ZigzagStroke.toPx() / 2
        val base = peak + ZigzagHeight.toPx()
        val periods = ceil(size.width / period).toInt().coerceAtLeast(1)
        val end = periods * period
        val path = Path()
        if (top) {
            path.moveTo(0f, base)
            for (i in 0 until periods) {
                val x = i * period
                path.quadraticBezierTo(x + period * 0.3f, base, x + period / 2, peak)
                path.quadraticBezierTo(x + period * 0.7f, base, x + period, base)
            }
        } else {
            path.moveTo(0f, 0f)
            path.lineTo(end, 0f)
        }
        if (reach > 0.dp) {
            val next = size.height - reach.toPx()
            path.lineTo(end, next + base)
            for (i in periods downTo 1) {
                val x = i * period
                path.quadraticBezierTo(x - period * 0.3f, next + base, x - period / 2, next + peak)
                path.quadraticBezierTo(x - period * 0.7f, next + base, x - period, next + base)
            }
        } else {
            path.lineTo(end, size.height)
            path.lineTo(0f, size.height)
        }
        path.close()
        Outline.Generic(path)
    }
}

/** Full-width zigzag between list sections, like [EpisodeItem]'s dividers; [tint] as for [topDivider]. */
@Composable
internal fun ListDivider(modifier: Modifier = Modifier, tint: () -> Float = { 0f }) {
    val color = listDividerColor()
    val accent = MaterialTheme.colorScheme.primary
    Spacer(
        modifier.fillMaxWidth().height(ListDividerHeight)
            .drawBehind { drawZigzag(pullDividerColor(color, accent, tint()), top = 0f) },
    )
}

/** "人人人人": concave strokes meeting in sharp tips at [top], flowing smoothly into each other at the base. */
internal fun DrawScope.drawZigzag(color: Color, top: Float) {
    val period = ZigzagPeriod.toPx()
    val stroke = ZigzagStroke.toPx()
    val peak = top + stroke / 2
    val base = peak + ZigzagHeight.toPx()
    val path = Path().apply {
        moveTo(0f, base)
        var x = 0f
        while (x < size.width) {
            val tip = x + period / 2
            quadraticBezierTo(x + period * 0.3f, base, tip, peak)
            quadraticBezierTo(x + period * 0.7f, base, x + period, base)
            x += period
        }
    }
    clipRect(right = size.width) { drawPath(path, color, style = Stroke(width = stroke)) }
}

/** "21 hours ago · New". */
@Composable
private fun AgeLine(age: String?, badge: String?, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (age != null) MetaText(age)
        if (badge != null) MetaText(badge, color = MaterialTheme.colorScheme.tertiary)
    }
}

/**
 * Outlined pill: play icon and duration or remaining time, e.g. "▶ 12 min left".
 * The play icon takes the show cover's colour; the playing row is filled with the player bar's artwork tint.
 */
@Composable
private fun PlayPill(row: EpisodeRow, isCurrent: Boolean, isPlaying: Boolean, coverUrl: String?, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val seed = rememberArtworkSeed(coverUrl.takeIf { !isCurrent })
    val dark = colors.background.luminance() < 0.5f
    val coverAccent = remember(seed, dark) { seed?.let { playerPalette(it, dark).accent } }
    val player = LocalPlayerColors.current?.takeIf { isCurrent }
    val container = player?.container ?: if (isCurrent) colors.primary else Color.Transparent
    val content = player?.onContainer ?: if (isCurrent) colors.onPrimary else colors.onSurface
    val icon = if (isCurrent) content else coverAccent ?: colors.primary
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = container,
        contentColor = content,
        border = if (isCurrent) null else BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier.height(30.dp),
    ) {
        Row(
            Modifier.padding(start = 6.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                if (isPlaying) Icons.Outlined.PauseCircle else Icons.Outlined.PlayCircle,
                if (isPlaying) "Pause" else "Play",
                Modifier.size(20.dp),
                tint = icon,
            )
            pillLabel(row)?.let { Text(it, style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp), maxLines = 1) }
        }
    }
}

/** An outlined check in the download control's circles. */
@Composable
private fun CompleteGlyph(description: String) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.size(RowGlyphSize).border(1.dp, colors.outlineVariant, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Check, description, Modifier.size(RowGlyphSize * 0.62f), tint = colors.onSurface)
    }
}

private val RowGlyphSize = 22.dp

/** The description as one paragraph of plain text, for a two-line excerpt. */
internal fun summaryText(description: String): String = plainText(description).replace(Regex("\\s+"), " ").trim()

private fun pillLabel(row: EpisodeRow): String? {
    if (row.played) return "Played"
    val durationMs = row.progress?.durationMs?.takeIf { it > 0 } ?: row.episode.durationSeconds?.times(1000)
    val position = row.progress?.positionMs ?: 0
    return when {
        durationMs == null -> null
        position > 0 -> "${formatMinutes(durationMs - position)} left"
        else -> formatMinutes(durationMs)
    }
}

/** "Just now", "5 min ago", "21 hours ago", "Yesterday", "3 days ago"; older dates as [formatShortDate]. */
internal fun formatAge(
    epochMs: Long,
    now: Long = io.waves.currentTimeMillis(),
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): String {
    val minutes = (now - epochMs) / 60_000
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "$minutes min ago"
        hours < 24 -> if (hours == 1L) "1 hour ago" else "$hours hours ago"
        days == 1L -> "Yesterday"
        days < 7 -> "$days days ago"
        else -> formatShortDate(epochMs, timeZone)
    }
}

/** "Oct 3" within the current year, otherwise "Oct 3, 2024". */
internal fun formatShortDate(epochMs: Long, timeZone: TimeZone = TimeZone.currentSystemDefault()): String {
    val full = formatDate(epochMs, timeZone)
    val year = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(timeZone).year
    val thisYear = Instant.fromEpochMilliseconds(io.waves.currentTimeMillis()).toLocalDateTime(timeZone).year
    return if (year == thisYear) full.substringBefore(',') else full
}
