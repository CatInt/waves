package io.waves.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.waves.model.Episode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.exp

/**
 * Per-episode download control, drawn as one family of circles in the accent colour: outlined arrow (download),
 * progress ring around a stop square (tap to cancel), an arrow onto a tray on a soft tint (downloaded; tap turns it
 * into a delete button for a few seconds, which removes the file; long-press for "Remove download"), a bolt on the same tint (kept automatically while listening; tap keeps it as a
 * download, long-press for "Remove cached episode") and an error ring (tap to retry).
 *
 * @param played hides the download arrow of an episode already played; one kept (downloaded or cached), on its way
 *   or failed still shows.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DownloadButton(
    episode: Episode,
    downloads: DownloadsState,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    played: Boolean = false,
) {
    val size = if (compact) 22.dp else 26.dp
    val control by remember(episode.id) { downloads.control(episode.id) }
        .collectAsState(initial = DownloadControl.NotDownloaded)
    var menu by remember(episode.id) { mutableStateOf(false) }
    // A tapped download asks once more: a delete button for a while, then back to the download.
    var armed by remember(episode.id) { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(DeleteArmedMs)
            armed = false
        }
    }
    if (control != DownloadControl.Downloaded) armed = false
    val armedProgress by animateFloatAsState(if (armed) 1f else 0f, tween(ArmMs, easing = FastOutSlowInEasing))
    // Keeping a cached episode and deleting a download each play out on the icon (on a set pace, not the real one).
    var change by remember(episode.id) { mutableStateOf<IconChange?>(null) }
    // Where the download ring stood when it finished, for the landing to fill on from.
    val ring = remember(episode.id) { RingProgress() }
    var landingFrom by remember(episode.id) { mutableFloatStateOf(0f) }
    val changeProgress = remember(episode.id) { Animatable(0f) }
    LaunchedEffect(change) {
        val running = change ?: return@LaunchedEffect
        changeProgress.snapTo(0f)
        // The landing paces its own stages; the others ease as a whole.
        val easing = if (running == IconChange.Landing) LinearEasing else FastOutSlowInEasing
        changeProgress.animateTo(1f, tween(running.durationMs, easing = easing))
        if (running == IconChange.Deleting) downloads.remove(episode.id)
        // Hand over once the real state has caught up, so the old icon doesn't flash back.
        val before = when (running) {
            IconChange.Keeping -> DownloadControl.Cached
            IconChange.Deleting -> DownloadControl.Downloaded
            IconChange.Landing -> null
        }
        if (before != null) withTimeoutOrNull(1_000) { snapshotFlow { control }.first { it != before } }
        change = null
    }
    // A download seen finishing lands on the icon; one already done when the row appears just shows as done.
    var lastControl by remember(episode.id) { mutableStateOf<DownloadControl?>(null) }
    LaunchedEffect(control) {
        if (lastControl is DownloadControl.InProgress && control == DownloadControl.Downloaded && change == null) {
            landingFrom = ring.shown
            change = IconChange.Landing
        }
        lastControl = control
    }
    change?.let { running ->
        IconButton(onClick = {}, modifier) { IconChangeGlyph(running, changeProgress.value, size, played, landingFrom) }
        return
    }
    when (val c = control) {
        DownloadControl.NotDownloaded -> if (!played) IconButton(onClick = { downloads.download(episode) }, modifier) {
            DownloadStateIcon(c, size, Modifier.semantics { contentDescription = "Download" })
        }
        is DownloadControl.InProgress -> IconButton(onClick = { downloads.cancel(episode.id) }, modifier) {
            DownloadStateIcon(c, size, Modifier.semantics { contentDescription = "Cancel download" }, ring)
        }
        DownloadControl.Downloaded -> StoredEpisodeButton(
            modifier = modifier,
            menu = menu,
            onClick = {
                if (armed) {
                    armed = false
                    change = IconChange.Deleting
                } else {
                    armed = true
                }
            },
            onOpen = { menu = true },
            onClose = { menu = false },
            description = if (armed) "Remove download" else "Downloaded",
            action = "Remove download",
            onRemove = { downloads.remove(episode.id) },
        ) {
            ArmingGlyph(size, armedProgress)
        }
        DownloadControl.Cached -> StoredEpisodeButton(
            modifier = modifier,
            menu = menu,
            onClick = { downloads.download(episode); change = IconChange.Keeping },
            onOpen = { menu = true },
            onClose = { menu = false },
            description = "Cached",
            action = "Remove cached episode",
            onRemove = { downloads.remove(episode.id) },
        ) {
            DownloadStateIcon(c, size)
        }
        is DownloadControl.Failed -> IconButton(onClick = { downloads.download(episode) }, modifier) {
            DownloadStateIcon(
                c, size,
                Modifier.semantics { contentDescription = "Download failed" + (c.error?.let { ": $it" } ?: "") + ". Retry" },
            )
        }
    }
}

/** How [control] looks; the button around it (if any) supplies the action and its description. */
@Composable
internal fun DownloadStateIcon(
    control: DownloadControl,
    size: Dp,
    modifier: Modifier = Modifier,
    ring: RingProgress = remember { RingProgress() },
) {
    val colors = MaterialTheme.colorScheme
    // Same ink as the play pill beside it: hairline outline, onSurface glyphs.
    val ink = colors.onSurface
    when (control) {
        DownloadControl.NotDownloaded -> DownloadGlyph(size, modifier, ring = colors.outlineVariant, arrow = ink)
        is DownloadControl.InProgress -> Box(modifier.size(size), contentAlignment = Alignment.Center) {
            SmoothProgress(control.progress, ring)
            CircularProgressIndicator(
                progress = { ring.shown }, modifier = Modifier.fillMaxSize(), color = ink, strokeWidth = 2.dp,
                trackColor = colors.outlineVariant,
            )
            Box(Modifier.size(size * 0.3f).clip(RoundedCornerShape(1.5.dp)).background(ink))
        }
        // Kept episodes, downloaded or cached, sit on the same soft tint.
        DownloadControl.Downloaded -> Box(
            modifier.size(size).background(storedTint(ink), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            LandedGlyph(Modifier.size(size * 0.56f), ink)
        }
        DownloadControl.Cached -> Box(
            modifier.size(size).background(storedTint(ink), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Bolt, null, Modifier.size(size * 0.66f), tint = ink)
        }
        is DownloadControl.Failed -> Box(
            modifier.size(size).border(1.75.dp, colors.error, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.PriorityHigh, null, Modifier.size(size * 0.6f), tint = colors.error)
        }
    }
}

/**
 * [target] progress (null while the size is unknown) as a ring that moves every frame and never goes back: it eases up
 * to each real update, then creeps a little past it until the next one. Without a size (queued, or a server that
 * doesn't say) it only creeps a little way, so the real progress, once it comes, takes over at once.
 */
@Composable
private fun SmoothProgress(target: Float?, ring: RingProgress) {
    val latest by rememberUpdatedState(target)
    LaunchedEffect(ring) {
        ring.shown = 0f // a new download (say, after deleting the last one) starts from empty
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val step = (now - last) / 1_000_000_000f
            last = now
            // The last stretch is left for the landing to fill, slowly, once the download is done.
            val real = latest?.coerceAtMost(FinalStretch)
            val shown = ring.shown
            ring.shown = when {
                real != null && shown < real -> shown + (real - shown) * (1f - exp(-CatchUpRate * step))
                else -> {
                    val ahead = if (real == null) UnknownSizeCeiling else (real + CreepAhead).coerceAtMost(FinalStretch)
                    shown + (ahead - shown).coerceAtLeast(0f) * (1f - exp(-CreepRate * step))
                }
            }
        }
    }
}

/** How far a download's ring has got on screen. */
internal class RingProgress {
    var shown by mutableFloatStateOf(0f)
}

private const val CatchUpRate = 6f
private const val CreepRate = 0.25f
private const val CreepAhead = 0.05f
private const val UnknownSizeCeiling = 0.15f
private const val FinalStretch = 0.94f

/**
 * The soft fill behind a kept episode's glyph: [ink] at a tenth on a light background,
 * stronger on a dark one, where a light ink that faint all but vanishes.
 */
@Composable
private fun storedTint(ink: Color): Color =
    ink.copy(alpha = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) 0.22f else 0.1f)

/** An arrow come down onto a tray, in fine strokes: a downloaded episode. */
@Composable
private fun LandedGlyph(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = LandedStroke.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val arrow = Path().apply {
            moveTo(w * 0.5f, h * 0.06f)
            lineTo(w * 0.5f, h * 0.66f)
            moveTo(w * 0.24f, h * 0.42f)
            lineTo(w * 0.5f, h * 0.68f)
            lineTo(w * 0.76f, h * 0.42f)
            moveTo(w * 0.16f, h * 0.92f)
            lineTo(w * 0.84f, h * 0.92f)
        }
        drawPath(arrow, color, style = stroke)
    }
}

private val LandedStroke = 1.1.dp

/**
 * A download turning into its delete button as [progress] goes from 0 to 1: the landed arrow gives way to a bin and
 * the soft tint turns to the error colour's.
 */
@Composable
private fun ArmingGlyph(size: Dp, progress: Float) {
    val colors = MaterialTheme.colorScheme
    val ink = colors.onSurface
    val error = colors.error
    Box(
        Modifier.size(size).background(lerp(storedTint(ink), storedTint(error), progress), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (progress < 1f) {
            LandedGlyph(
                Modifier.size(size * 0.56f).graphicsLayer {
                    val shrink = 1f - 0.4f * progress
                    scaleX = shrink
                    scaleY = shrink
                    alpha = 1f - progress
                },
                ink,
            )
        }
        if (progress > 0f) {
            Icon(
                Icons.Outlined.Delete, null,
                Modifier.size(size * 0.62f).graphicsLayer {
                    val grow = 0.6f + 0.4f * progress
                    scaleX = grow
                    scaleY = grow
                    alpha = progress
                },
                tint = error,
            )
        }
    }
}

/** How long a download takes to turn into its delete button, and back. */
private const val ArmMs = 220

/** A change of state the download control shows as it happens. */
private enum class IconChange(val durationMs: Int) {
    /** A cached episode becoming a download: a ring fills round the bolt, which turns into the landed arrow. */
    Keeping(900),
    /** A download being deleted: the ring drains as the bin sinks away, and the download arrow comes back. */
    Deleting(700),
    /**
     * A download finishing: the ring fills its last stretch from [IconChangeGlyph]'s `landingFrom`, then it and the
     * stop square give way to the soft tint and the landed arrow.
     */
    Landing(1_400),
}

/** [change] at [progress] (0 to 1). */
@Composable
private fun IconChangeGlyph(change: IconChange, progress: Float, size: Dp, played: Boolean, landingFrom: Float = 0f) {
    val colors = MaterialTheme.colorScheme
    val ink = colors.onSurface
    // The last part of each change brings in the icon it ends on.
    val arrival = ((progress - 0.55f) / 0.45f).coerceIn(0f, 1f)
    when (change) {
        IconChange.Keeping -> Box(
            Modifier.size(size).background(storedTint(ink), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                progress = { progress }, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 1f - arrival },
                color = ink, strokeWidth = 1.5.dp, trackColor = Color.Transparent, strokeCap = StrokeCap.Round,
            )
            Icon(
                Icons.Rounded.Bolt, null,
                Modifier.size(size * 0.66f).graphicsLayer { alpha = 1f - arrival },
                tint = ink,
            )
            LandedGlyph(
                Modifier.size(size * 0.56f).graphicsLayer {
                    alpha = arrival
                    translationY = (1f - arrival) * -size.toPx() * 0.2f
                },
                ink,
            )
        }
        IconChange.Landing -> Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            // First the ring fills (the first 45%), then the landing.
            val fill = FastOutSlowInEasing.transform((progress / 0.45f).coerceIn(0f, 1f))
            val landed = FastOutSlowInEasing.transform(((progress - 0.45f) / 0.55f).coerceIn(0f, 1f))
            val arrival = ((landed - 0.4f) / 0.6f).coerceIn(0f, 1f)
            val ringFill = landingFrom + (1f - landingFrom) * fill
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = landed }.background(storedTint(ink), CircleShape))
            CircularProgressIndicator(
                progress = { ringFill }, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 1f - landed },
                color = ink, strokeWidth = 2.dp, trackColor = colors.outlineVariant,
            )
            Box(
                Modifier.size(size * 0.3f).graphicsLayer {
                    val shrink = 1f - landed
                    scaleX = shrink
                    scaleY = shrink
                }.clip(RoundedCornerShape(1.5.dp)).background(ink),
            )
            LandedGlyph(
                Modifier.size(size * 0.56f).graphicsLayer {
                    alpha = arrival
                    translationY = (1f - arrival) * -size.toPx() * 0.2f
                },
                ink,
            )
        }
        IconChange.Deleting -> Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            val error = colors.error
            Box(
                Modifier.fillMaxSize().graphicsLayer { alpha = 1f - arrival }.background(storedTint(error), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    progress = { 1f - progress }, modifier = Modifier.fillMaxSize(),
                    color = error, strokeWidth = 1.5.dp, trackColor = Color.Transparent, strokeCap = StrokeCap.Round,
                )
                Icon(
                    Icons.Outlined.Delete, null,
                    Modifier.size(size * 0.62f).graphicsLayer {
                        val shrink = 1f - 0.4f * progress
                        scaleX = shrink
                        scaleY = shrink
                        alpha = 1f - progress
                    },
                    tint = error,
                )
            }
            if (!played) {
                DownloadGlyph(
                    size, Modifier.graphicsLayer { alpha = arrival },
                    arrow = ink, ring = colors.outlineVariant,
                )
            }
        }
    }
}

/** How long a tapped download stays a delete button. */
private const val DeleteArmedMs = 3_000L

/** A [ring]-outlined circle with a down arrow: an episode not downloaded yet. */
@Composable
private fun DownloadGlyph(size: Dp, modifier: Modifier, arrow: Color, ring: Color) {
    Box(
        modifier.size(size).border(1.dp, ring, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.ArrowDownward, null, Modifier.size(size * 0.62f), tint = arrow)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StoredEpisodeButton(
    modifier: Modifier,
    menu: Boolean,
    onClick: () -> Unit,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    description: String,
    action: String,
    onRemove: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Box(modifier.size(48.dp)) {
        Box(
            Modifier.fillMaxSize().clip(CircleShape)
                .combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onOpen)
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            icon()
        }
        AppMenu(expanded = menu, onDismiss = onClose) {
            AppMenuItem(action, onClick = { onClose(); onRemove() }, icon = Icons.Default.Delete, destructive = true)
        }
    }
}
