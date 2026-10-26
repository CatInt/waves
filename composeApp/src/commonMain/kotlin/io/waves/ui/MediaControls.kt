package io.waves.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import io.waves.model.Episode
import kotlinx.coroutines.flow.flowOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.sin

/** Sizes of the transport controls: thumb-sized on touch screens, a notch smaller with a pointer. */
@Immutable
internal class ControlSizes(
    val play: Dp,
    val playRing: Dp,
    val playIcon: Dp,
    val skip: Dp,
    val skipGlyph: Dp,
    val skipLabel: TextUnit,
    val button: Dp,
    val seekHeight: Dp,
    val thumb: Dp,
) {
    companion object {
        val Regular = ControlSizes(
            play = 64.dp, playRing = 72.dp, playIcon = 34.dp, skip = 52.dp, skipGlyph = 32.dp, skipLabel = 11.sp,
            button = 48.dp, seekHeight = 32.dp, thumb = 16.dp,
        )
        val Compact = ControlSizes(
            play = 44.dp, playRing = 50.dp, playIcon = 24.dp, skip = 40.dp, skipGlyph = 24.dp, skipLabel = 8.5.sp,
            button = 40.dp, seekHeight = 24.dp, thumb = 12.dp,
        )

        /** [Compact] where [compactControls] says so, else [Regular]. */
        val Default: ControlSizes get() = if (compactControls) Compact else Regular
    }
}

/** True on desktop, where controls are clicked with a pointer rather than tapped. */
internal expect val compactControls: Boolean

/** What the ring around a play button shows. */
internal sealed interface PlayRing {
    /** Waiting for audio: spinner. */
    data object Loading : PlayRing
    /** Episode is downloading: [progress] in 0..1, null while queued or of unknown size. */
    data class Downloading(val progress: Float?) : PlayRing
}
/** Loading wins over downloading: it's what stands between the listener and sound right now. */
@Composable
internal fun playRing(isBuffering: Boolean, episode: Episode, downloads: DownloadsState?): PlayRing? {
    val control by remember(episode.id, downloads) { downloads?.control(episode.id) ?: flowOf(DownloadControl.NotDownloaded) }
        .collectAsState(initial = DownloadControl.NotDownloaded)
    return if (isBuffering) PlayRing.Loading
    else (control as? DownloadControl.InProgress)?.let { PlayRing.Downloading(it.progress) }
}

@Composable
internal fun PlayRingIndicator(ring: PlayRing?, modifier: Modifier, strokeWidth: Dp = 2.dp) {
    val color = MaterialTheme.colorScheme.primary
    when (ring) {
        null -> Unit
        PlayRing.Loading -> CircularProgressIndicator(
            modifier.semantics { contentDescription = "Loading" }, color = color, strokeWidth = strokeWidth,
        )
        is PlayRing.Downloading -> {
            val p = ring.progress
            if (p == null) {
                CircularProgressIndicator(
                    modifier.semantics { contentDescription = "Downloading" }, color = color, strokeWidth = strokeWidth,
                )
            } else {
                CircularProgressIndicator(
                    progress = { p },
                    modifier = modifier.semantics { contentDescription = "Downloading ${(p * 100).toInt()}%" },
                    color = color, strokeWidth = strokeWidth, trackColor = color.copy(alpha = 0.2f),
                )
            }
        }
    }
}

/** Seek line and transport pinned above the system navigation bar. */
@Composable
internal fun ControlPanel(
    isPlaying: Boolean,
    ring: PlayRing?,
    positionMs: Long,
    durationMs: Long,
    speed: Float,
    error: String?,
    skipBackSeconds: Int,
    skipForwardSeconds: Int,
    controller: PlaybackController,
    download: (@Composable () -> Unit)?,
    sizes: ControlSizes = ControlSizes.Default,
) {
    Column(
        Modifier
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
            .widthIn(max = 480.dp).fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        error?.let {
            Text(
                it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        SeekLine(positionMs, durationMs, isPlaying && ring != PlayRing.Loading, controller::seekTo, sizes)
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            SpeedChip(speed, controller::setSpeed, sizes)
            SkipButton(skipBackSeconds, forward = false, controller::skipBack, sizes)
            PlayPauseButton(isPlaying, ring, controller::togglePlayPause, sizes)
            SkipButton(skipForwardSeconds, forward = true, controller::skipForward, sizes)
            Box(Modifier.size(sizes.button), contentAlignment = Alignment.Center) { download?.invoke() }
        }
    }
}

/** Elapsed time, a squiggly seek track (as in Android System UI's media controls), and remaining time on one line. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeekLine(
    positionMs: Long,
    durationMs: Long,
    playing: Boolean,
    onSeek: (Long) -> Unit,
    sizes: ControlSizes,
) {
    // While dragging, show the drag position instead of the (still advancing) player position.
    var dragging by remember { mutableStateOf<Float?>(null) }
    val max = durationMs.coerceAtLeast(1).toFloat()
    val value = (dragging ?: positionMs.coerceIn(0, durationMs.coerceAtLeast(0)).toFloat()).coerceIn(0f, max)
    val interaction = remember { MutableInteractionSource() }
    val active by interaction.collectIsDraggedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val accent = MaterialTheme.colorScheme.primary
    val timeStyle = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum")
    val waving = playing && durationMs > 0 && !active && !pressed
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(formatDuration(value.toLong()), style = timeStyle, modifier = Modifier.widthIn(min = 36.dp))
        Slider(
            value = value,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { onSeek(it.toLong()) }
                dragging = null
            },
            valueRange = 0f..max,
            enabled = durationMs > 0,
            interactionSource = interaction,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp).height(sizes.seekHeight),
            thumb = {
                val height by animateDpAsState(if (active || pressed) sizes.thumb * 1.25f else sizes.thumb)
                Box(Modifier.width(ThumbWidth).height(sizes.thumb * 1.25f), contentAlignment = Alignment.Center) {
                    Box(Modifier.width(ThumbWidth).height(height).clip(CircleShape).background(accent))
                }
            },
            track = { SquigglyTrack(if (max > 0f) value / max else 0f, waving, accent) },
        )
        Text(
            if (durationMs > 0) "-" + formatDuration(durationMs - value.toLong()) else "--:--",
            style = timeStyle, textAlign = TextAlign.End, modifier = Modifier.widthIn(min = 36.dp),
        )
    }
}

private val ThumbWidth = 4.dp
private val TrackStroke = 3.dp
private val WaveLength = 20.dp
private val WaveAmplitude = 2.dp
private val WaveSpeed = 8.dp // per second
/** Wavelengths over which the wave flattens into the playhead. */
private const val WaveTaper = 1.5f
private val StandardDecelerate = CubicBezierEasing(0f, 0f, 0f, 1f)

/**
 * Played part as a sine wave drifting forward that tapers flat into the playhead, the rest as a faint straight line.
 * The wave grows in when [waving] turns on and settles flat when it turns off (paused, scrubbing).
 */
@Composable
private fun SquigglyTrack(fraction: Float, waving: Boolean, color: Color) {
    val height by animateFloatAsState(
        if (waving) 1f else 0f,
        tween(if (waving) 800 else 550, easing = StandardDecelerate),
    )
    var phase by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val moving = height > 0f
    if (moving) {
        LaunchedEffect(Unit) {
            val speed = with(density) { WaveSpeed.toPx() }
            val length = with(density) { WaveLength.toPx() }
            var last = withInfiniteAnimationFrameNanos { it }
            while (true) {
                withInfiniteAnimationFrameNanos { now ->
                    phase = (phase + (now - last) / 1e9f * speed) % length
                    last = now
                }
            }
        }
    }
    Canvas(Modifier.fillMaxWidth().height(WaveAmplitude * 2 + TrackStroke)) {
        val stroke = TrackStroke.toPx()
        val cy = size.height / 2
        val progressX = size.width * fraction.coerceIn(0f, 1f)
        if (progressX < size.width) {
            drawLine(color.copy(alpha = 0.3f), Offset(progressX, cy), Offset(size.width, cy), stroke, StrokeCap.Round)
        }
        if (progressX <= 0f) return@Canvas
        val amplitude = WaveAmplitude.toPx() * height
        if (amplitude < 0.05f) {
            drawLine(color, Offset(0f, cy), Offset(progressX, cy), stroke, StrokeCap.Round)
            return@Canvas
        }
        val length = WaveLength.toPx()
        val taper = WaveTaper * length
        val step = 1.dp.toPx()
        val wave = Path()
        var x = 0f
        while (true) {
            val coeff = ((progressX + taper / 2 - x) / taper).coerceIn(0f, 1f)
            val y = cy + amplitude * coeff * sin(2 * PI.toFloat() * (x - phase) / length)
            if (x == 0f) wave.moveTo(x, y) else wave.lineTo(x, y)
            if (x >= progressX) break
            x = (x + step).coerceAtMost(progressX)
        }
        drawPath(wave, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun PlayPauseButton(isPlaying: Boolean, ring: PlayRing?, onClick: () -> Unit, sizes: ControlSizes) {
    // Sized for the ring, so the panel keeps its height when the ring comes and goes.
    Box(Modifier.size(sizes.playRing), contentAlignment = Alignment.Center) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shadowElevation = 2.dp,
            modifier = Modifier.size(sizes.play),
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (isPlaying) Icon(Icons.Rounded.Pause, "Pause", Modifier.size(sizes.playIcon))
                else Icon(Icons.Rounded.PlayArrow, "Play", Modifier.size(sizes.playIcon + 2.dp))
            }
        }
        PlayRingIndicator(ring, Modifier.size(sizes.playRing))
    }
}

/** Circular arrow with the interval centred inside; drawn so any configured interval gets the same glyph. */
@Composable
private fun SkipButton(seconds: Int, forward: Boolean, onClick: () -> Unit, sizes: ControlSizes) =
    SkipButton(seconds, forward, onClick, sizes.skip, sizes.skipGlyph, sizes.skipLabel)

@Composable
internal fun SkipButton(seconds: Int, forward: Boolean, onClick: () -> Unit, buttonSize: Dp, glyph: Dp, labelSize: TextUnit) {
    val description = if (forward) "Forward $seconds seconds" else "Back $seconds seconds"
    val color = LocalContentColor.current
    val measurer = rememberTextMeasurer()
    val label = measurer.measure(
        seconds.toString(),
        TextStyle(fontSize = labelSize, fontWeight = FontWeight.Bold, color = color, fontFamily = AppFontFamily),
    )
    Box(
        Modifier.size(buttonSize).clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(glyph)) {
            val stroke = (2.2.dp * (glyph / 32.dp)).toPx()
            val r = size.minDimension / 2 - stroke * 1.2f
            val c = center
            val mirror = if (forward) 1f else -1f
            withTransform({ scale(mirror, 1f, c) }) {
                // Clockwise arc ending at 12 o'clock; the gap towards 1–2 o'clock holds the arrowhead.
                drawArc(
                    color, startAngle = -40f, sweepAngle = 310f, useCenter = false,
                    topLeft = Offset(c.x - r, c.y - r), size = Size(2 * r, 2 * r),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
                val a = stroke * 1.9f
                val head = Path().apply {
                    moveTo(c.x + a * 1.1f, c.y - r)
                    lineTo(c.x - a * 0.35f, c.y - r - a)
                    lineTo(c.x - a * 0.35f, c.y - r + a)
                    close()
                }
                drawPath(head, color)
                drawPath(head, color, style = Stroke(stroke * 0.6f, join = StrokeJoin.Round))
            }
            drawText(label, topLeft = Offset(c.x - label.size.width / 2f, c.y - label.size.height / 2f))
        }
    }
}

@Composable
private fun SpeedChip(speed: Float, onSpeed: (Float) -> Unit, sizes: ControlSizes) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier.size(sizes.button).clip(CircleShape)
                .clickable(role = Role.Button, onClick = { open = true })
                .semantics { contentDescription = "Speed ${formatSpeed(speed)}" },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                formatSpeed(speed).replace('x', '×'),
                style = if (sizes === ControlSizes.Compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        ChoiceMenu(
            expanded = open,
            title = "Playback speed",
            options = SpeedPresets,
            selected = SpeedPresets.firstOrNull { kotlin.math.abs(it - speed) < 0.01f },
            label = ::formatSpeed,
            onSelect = onSpeed,
            onDismiss = { open = false },
        )
    }
}
