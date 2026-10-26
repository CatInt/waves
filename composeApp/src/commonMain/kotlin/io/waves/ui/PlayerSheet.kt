package io.waves.ui

import androidx.compose.foundation.basicMarquee
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import io.waves.model.Episode
import io.waves.model.Podcast
import io.waves.player.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

const val MINI_PLAYER_TAG = "miniPlayer"

/** Collapsed player height: a 2 dp progress line over a row with 44 dp artwork and 8 dp vertical padding. */
internal val MiniPlayerHeight = 62.dp
private val MiniArtSize = 44.dp
private val MiniArtStart = 10.dp
private val MiniArtTop = 10.dp
private val MiniMaxWidth = 480.dp
private val MiniSideMargin = 24.dp

/**
 * Distance from the screen bottom to the floating player's bottom edge: 40 dp in portrait, 16 dp in landscape,
 * measured from the screen edge as the app draws edge to edge, but always clear of a tall navigation bar.
 */
internal fun miniBottomGap(systemBottom: Dp, landscape: Boolean): Dp =
    maxOf(if (landscape) 16.dp else 40.dp, systemBottom + 8.dp)

/** The sheet moves a little faster than the finger, so a short swipe goes a long way. */
private const val SwipeFactor = 1.2f

/** Expansion of the player sheet, 0 = floating mini player, 1 = full screen. Follows drags, springs on release. */
private class SheetProgress(initial: Float, private val scope: CoroutineScope) {
    var value by mutableFloatStateOf(initial)
        private set
    private var target = initial
    private var job: Job? = null

    fun dragBy(delta: Float) {
        job?.cancel()
        target = Float.NaN
        value = (value + delta).coerceIn(0f, 1f)
    }

    /**
     * Eases out to [to] without overshooting (a spring carrying fling velocity would hit the 0/1 bound and stop
     * dead). The duration is chosen so the starting speed matches a fling towards the target.
     */
    fun animateTo(to: Float, velocity: Float = 0f) {
        if (to == target) return
        target = to
        job?.cancel()
        val distance = abs(to - value)
        val towards = if (to > value) velocity else -velocity
        // EaseOutCubic starts at ~3x its average speed.
        val durationMs = if (towards > 0.5f) (3000f * distance / towards).roundToInt().coerceIn(180, 420)
        else (240 + 160 * distance).roundToInt()
        job = scope.launch {
            animate(value, to, animationSpec = tween(durationMs, easing = EaseOutCubic)) { v, _ -> value = v }
        }
    }
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

/**
 * The player as one surface that morphs between the floating mini player and the full-screen Now Playing view,
 * driven by vertical drags (YouTube Music style) as well as taps, the collapse button and system Back.
 * [expanded] mirrors the navigation state; settling a drag calls [onExpand] / [onCollapse] to update it.
 */
@Composable
internal fun PlayerSheet(
    controller: PlaybackController,
    expanded: Boolean,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    hiddenByScroll: Boolean,
    bottomInset: Dp,
    downloads: DownloadsState?,
    onOpenPodcast: (Podcast) -> Unit,
    playerColors: PlayerColors,
) {
    val playback by controller.state.collectAsState()
    val podcast by controller.podcast.collectAsState()
    val scope = rememberCoroutineScope()
    val sheet = remember { SheetProgress(if (expanded) 1f else 0f, scope) }
    LaunchedEffect(expanded) { sheet.animateTo(if (expanded) 1f else 0f) }

    val episode = playback.episode
    val collapsed by remember { derivedStateOf { sheet.value == 0f } }
    val hide by animateFloatAsState(
        if (episode == null || (hiddenByScroll && collapsed && !expanded)) 1f else 0f,
        tween(180),
    )
    if (episode == null && !expanded && hide == 1f) return

    MaterialTheme(colorScheme = playerColors.applyTo(MaterialTheme.colorScheme)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val fullW = constraints.maxWidth
            val fullH = constraints.maxHeight
            val miniH = with(density) { MiniPlayerHeight.toPx() }
            val collapsedInset = with(density) { max(MiniSideMargin.toPx(), (fullW - MiniMaxWidth.toPx()) / 2f) }
            val bottomGap = miniBottomGap(bottomInset, LocalIsLandscapeLayout.current)
            val travel = (fullH - with(density) { bottomGap.toPx() } - miniH).coerceAtLeast(1f)
            val flingThreshold = with(density) { 400.dp.toPx() }
            val collapseFlingThreshold = with(density) { 300.dp.toPx() }
        val miniArtSize = with(density) { MiniArtSize.toPx() }
        val miniArtStart = with(density) { MiniArtStart.toPx() }
        val miniArtTop = with(density) { MiniArtTop.toPx() }
            fun p() = sheet.value
            val collapsedGap = fullH - travel - miniH
            // Width and bottom edge reach the screen edges within the first third of the drag (eased out);
            // the top edge follows the finger.
            fun edgeLag(): Float {
                val t = (p() / 0.35f).coerceAtMost(1f)
                return (1f - t) * (1f - t)
            }
            fun inset() = collapsedInset * edgeLag()

            fun settle(velocityY: Float) {
                val to = when {
                    velocityY < -flingThreshold -> 1f
                    velocityY > collapseFlingThreshold -> 0f
                    // From Now Playing a short pull down is enough to collapse.
                    else -> if (p() > if (expanded) 0.8f else 0.5f) 1f else 0f
                }
                sheet.animateTo(to, -velocityY / travel)
                if (to == 1f && !expanded) onExpand() else if (to == 0f && expanded) onCollapse()
            }

            // Touch drags inside the scrolling Now Playing content: collapse once it is scrolled to the top.
            var pointerDown by remember { mutableStateOf(false) }
            val nestedScroll = remember(travel, expanded) {
                object : NestedScrollConnection {
                    var dragging = false

                    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                        if (!pointerDown || available.y >= 0f || p() >= 1f) return Offset.Zero
                        dragging = true
                        sheet.dragBy(-available.y * SwipeFactor / travel)
                        return Offset(0f, available.y)
                    }

                    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                        if (!pointerDown || available.y <= 0f) return Offset.Zero
                        dragging = true
                        sheet.dragBy(-available.y * SwipeFactor / travel)
                        return Offset(0f, available.y)
                    }

                    override suspend fun onPreFling(available: Velocity): Velocity {
                        if (!dragging) return Velocity.Zero
                        dragging = false
                        settle(available.y)
                        return available
                    }
                }
            }

            val colors = MaterialTheme.colorScheme
            // Kept composed (invisible) while collapsed so a drag never waits on building the full player.
            val showFull = episode != null || expanded
            val fullReadable by remember { derivedStateOf { sheet.value >= 0.5f } }
            var contentOrigin by remember { mutableStateOf(Offset.Zero) }
            var artOrigin by remember { mutableStateOf(Offset.Unspecified) }
            var artSize by remember { mutableStateOf(0f) }
            Box(
                Modifier
                    .layout { measurable, _ ->
                        val inset = inset()
                        val w = (fullW - 2 * inset).roundToInt()
                        val top = lerp(travel, 0f, p())
                        val h = (fullH - collapsedGap * edgeLag() - top).roundToInt()
                        val placeable = measurable.measure(Constraints.fixed(w, h))
                        layout(fullW, fullH) { placeable.place(inset.roundToInt(), top.roundToInt()) }
                    }
                    .graphicsLayer {
                        val p = p()
                        translationY = hide * miniH / 2f
                        alpha = 1f - hide
                        // Android bakes the layer alpha into the shadow outline only when the outline is rebuilt, which an
                        // alpha change alone doesn't trigger; tying elevation to the fade rebuilds it every frame.
                        shadowElevation = (1f - p) * (1f - hide) * 10.dp.toPx()
                        shape = RoundedCornerShape(lerp(12.dp.toPx(), 0f, p))
                        clip = true
                    }
                    .drawBehind {
                        val p = p()
                        val top = lerp(playerColors.container, playerColors.backdrop, p)
                        val bottom = lerp(playerColors.container, colors.background, p)
                        drawRect(Brush.verticalGradient(0f to top, 0.75f to bottom))
                    }
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                pointerDown = event.changes.any { it.pressed }
                            }
                        }
                    }
                    .draggable(
                        state = rememberDraggableState { delta -> sheet.dragBy(-delta * SwipeFactor / travel) },
                        orientation = Orientation.Vertical,
                        onDragStopped = { velocity -> settle(velocity) },
                    )
                    .nestedScroll(nestedScroll),
            ) {
                val morphing = { p() > 0f && artOrigin != Offset.Unspecified && artSize > 0f }
                // Whichever layer is (mostly) visible sits on top and takes the taps.
                if (episode != null) {
                    CompositionLocalProvider(LocalContentColor provides colors.onSecondaryContainer) {
                        MiniPlayerBar(
                            controller, playback, episode, podcast, downloads,
                            onOpen = onExpand,
                            modifier = Modifier
                                .zIndex(if (fullReadable) 0f else 1f)
                                .height(MiniPlayerHeight)
                                .graphicsLayer { alpha = (1f - p() / 0.3f).coerceIn(0f, 1f) }
                                .then(if (collapsed) Modifier else Modifier.clearAndSetSemantics {}),
                            thumbAlpha = { if (morphing()) 0f else 1f },
                        )
                    }
                }

                if (showFull) {
                    DisposableEffect(Unit) { onDispose { artOrigin = Offset.Unspecified } }
                    Box(
                        Modifier
                            .zIndex(if (fullReadable) 1f else 0f)
                            .layout { measurable, constraints ->
                                val placeable = measurable.measure(Constraints.fixed(fullW, fullH))
                                layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(-inset().roundToInt(), 0) }
                            }
                            .graphicsLayer {
                                val t = ((p() - 0.3f) / 0.7f).coerceIn(0f, 1f)
                                alpha = t * t * (3f - 2f * t)
                            }
                            .onGloballyPositioned { contentOrigin = it.positionInRoot() }
                            .then(if (fullReadable) Modifier else Modifier.clearAndSetSemantics {}),
                    ) {
                        CompositionLocalProvider(LocalContentColor provides colors.onBackground) {
                            NowPlayingScreen(
                                controller = controller,
                                onCollapse = onCollapse,
                                downloads = downloads,
                                artwork = { size ->
                                    Spacer(
                                        Modifier.size(size).onGloballyPositioned {
                                            artOrigin = it.positionInRoot()
                                            artSize = it.size.width.toFloat()
                                        },
                                    )
                                },
                                onOpenPodcast = onOpenPodcast,
                            )
                        }
                    }
                }

                if (episode != null) {
                    if (artSize > 0f) {
                        Artwork(
                            episode.artworkUrl ?: podcast?.artworkUrl,
                            podcast?.title ?: episode.title,
                            modifier = Modifier
                                .zIndex(2f)
                                .clearAndSetSemantics {}
                                .wrapContentSize(Alignment.TopStart, unbounded = true)
                                .graphicsLayer {
                                    val p = p()
                                    val target = artOrigin - contentOrigin
                                    val scale = lerp(miniArtSize, artSize, p) / artSize
                                    transformOrigin = TransformOrigin(0f, 0f)
                                    translationX = lerp(miniArtStart, target.x - inset(), p)
                                    translationY = lerp(miniArtTop, target.y, p)
                                    scaleX = scale
                                    scaleY = scale
                                    shape = RoundedCornerShape(lerp(6.dp.toPx(), TitleChipArtCorner.toPx(), p) / scale)
                                    clip = true
                                    alpha = if (morphing()) 1f else 0f
                                },
                            size = with(density) { artSize.toDp() },
                            corner = 0.dp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniPlayerBar(
    controller: PlaybackController,
    playback: PlaybackState,
    episode: Episode,
    podcast: Podcast?,
    downloads: DownloadsState?,
    onOpen: () -> Unit,
    modifier: Modifier,
    thumbAlpha: () -> Float,
) {
    val skip by controller.skipIntervals.collectAsState()
    Column(modifier.fillMaxWidth()) {
        val duration = playback.durationMs.takeIf { it > 0 } ?: (episode.durationSeconds ?: 0) * 1000
        LinearProgressIndicator(
            progress = { if (duration > 0) (playback.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            drawStopIndicator = {},
        )
        Row(
            Modifier.fillMaxWidth().testTag(MINI_PLAYER_TAG).clickable(onClick = onOpen)
                .padding(start = MiniArtStart, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(
                episode.artworkUrl ?: podcast?.artworkUrl, podcast?.title ?: episode.title,
                modifier = Modifier.graphicsLayer { alpha = thumbAlpha() },
                size = MiniArtSize, corner = 6.dp,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    episode.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                )
                val error = playback.error
                val subtitle = error ?: podcast?.title
                subtitle?.let {
                    Text(
                        it, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                        modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                        color = if (error != null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                    )
                }
            }
            SkipButton(
                skip.backSeconds, forward = false, controller::skipBack,
                buttonSize = 48.dp, glyph = ControlSizes.Compact.skipGlyph, labelSize = ControlSizes.Compact.skipLabel,
            )
            Box(contentAlignment = Alignment.Center) {
                val ring = playRing(playback.isBuffering, episode, downloads)
                PlayRingIndicator(ring, Modifier.size(36.dp))
                IconButton(onClick = controller::togglePlayPause) {
                    if (playback.isPlaying) Icon(Icons.Default.Pause, "Pause") else Icon(Icons.Default.PlayArrow, "Play")
                }
            }
        }
    }
}
