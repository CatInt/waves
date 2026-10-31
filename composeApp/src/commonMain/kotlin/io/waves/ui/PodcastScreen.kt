package io.waves.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.round
import coil3.memory.MemoryCache
import io.waves.model.Episode
import io.waves.model.Podcast
import io.waves.player.PlaybackState
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastScreen(
    state: PodcastDetailState,
    playback: PlaybackState,
    onBack: () -> Unit,
    onPlay: (Episode, Podcast) -> Unit,
    onPause: () -> Unit,
    /** Null hides the per-episode download control. */
    downloads: DownloadsState? = null,
    /** Marks an episode complete or not; null hides the per-episode button. */
    onSetCompleted: ((Episode, Boolean) -> Unit)? = null,
    /** Null leaves the creator name as plain text. */
    onAuthorClick: ((String) -> Unit)? = null,
    authorClickLabel: (String) -> String = { "Search podcasts by $it" },
    /** The creator line when the feed names no author: a YouTube channel's own page names the channel. */
    creatorName: String? = null,
    /** The tapped cover this page's cover grows out of; null fades the cover in with the background instead. */
    origin: CoverOrigin? = null,
    /** How far the page's background (and the hero's gradient) has faded in: 0 shut (or closed again), 1 fully open. */
    entry: () -> Float = { 1f },
    /** How far the cover has flown from [origin] to its place on the page, likewise. */
    flight: () -> Float = { 1f },
    /** How far everything else (text, buttons, episodes, the bar) has faded in, likewise. */
    content: () -> Float = { 1f },
    /** The cover flying from [origin] is drawn, its image loaded. */
    onCoverShown: () -> Unit = {},
) {
    val ui by state.state.collectAsState()
    val shown = Modifier.graphicsLayer { alpha = content() }
    val hero = rememberPlayerColors(rememberArtworkSeed(ui.podcast.artworkUrl))
    val listState = rememberLazyListState()
    var heroHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val barPx = WindowInsets.statusBars.getTop(density) + with(density) { (CollapsedBarTop + CollapsedBarHeight).roundToPx() }
    val fadePx = with(density) { 48.dp.toPx() }
    // Hero cover's unscrolled position and this screen's in the root, so the morphing cover starts exactly on it.
    var coverRest by remember { mutableStateOf<Offset?>(null) }
    // The hero cover's loaded image, which the morphing copy shows until its own request lands.
    var coverKey by remember { mutableStateOf<MemoryCache.Key?>(null) }
    var screenInRoot by remember { mutableStateOf(Offset.Zero) }
    // Where the cover lands: the collapsed chip's bounds.
    var chipInRoot by remember { mutableStateOf<Rect?>(null) }
    val scope = rememberCoroutineScope()
    val coverPx = with(density) { HeroCoverSize.toPx() }
    // The whole collapse: 0 at the top, 1 after [MorphStretch] times the scroll the cover's bottom must rise to meet
    // the chip's (at most the hero's whole travel). The cover morphs into the chip over all of it while the hero's
    // text scrolls up and fades under it.
    val morph by remember(coverPx, barPx) {
        derivedStateOf {
            val rest = coverRest
            val chip = chipInRoot
            when {
                rest == null || chip == null -> 0f
                listState.firstVisibleItemIndex > 0 -> 1f
                else -> {
                    val rise = rest.y + coverPx - chip.bottom
                    val length = minOf(rise * MorphStretch, (heroHeight - barPx).toFloat()).coerceAtLeast(rise)
                    if (length <= 0f) 1f else (listState.firstVisibleItemScrollOffset / length).coerceIn(0f, 1f)
                }
            }
        }
    }
    // 0 while the hero fills the top, 1 once its bottom has scrolled up under the bar.
    val collapse by remember(barPx, fadePx) {
        derivedStateOf {
            when {
                heroHeight == 0 -> 0f
                listState.firstVisibleItemIndex > 0 -> 1f
                else -> ((listState.firstVisibleItemScrollOffset - (heroHeight - barPx - fadePx)) / fadePx).coerceIn(0f, 1f)
            }
        }
    }
    // The first row's divider has reached the bar's bottom.
    val barPinned by remember { derivedStateOf { collapse >= 1f } }
    val coverFraction = { morph }
    // The morphing cover's bottom edge in the root: hero text fades as it slides under it.
    val fadeLine = {
        val rest = coverRest
        val chip = chipInRoot
        if (rest == null || chip == null) {
            Float.NEGATIVE_INFINITY
        } else {
            val from = rest.y + coverPx
            from + (chip.bottom - from) * MorphShrinkEasing.transform(coverFraction())
        }
    }
    val page = MaterialTheme.colorScheme.background
    Scaffold(
        Modifier.drawBehind { drawRect(page, alpha = entry()) },
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        WavesPullToRefreshBox(
            isRefreshing = ui.refreshing,
            onRefresh = { if (!ui.offline) state.refresh() },
            modifier = Modifier.padding(padding).fillMaxSize().onGloballyPositioned { screenInRoot = it.positionInRoot() },
            // The hero stays put; the gap opens under it, above the episodes.
            gapInContent = true,
        ) {
            // Once the bar has its divider, episodes come to rest with theirs on it, and never with it across a title.
            val guides = rememberSnapGuides()
            if (barPinned) {
                SnapToTopLine(
                    listState, topLine = { barPx }, snappable = { it.contentType == EpisodeRowType },
                    reach = EpisodeSnapReach, guides = guides,
                )
            }
            LazyColumn(
                Modifier.fillMaxSize()
                    .snapGuideList(guides)
                    .verticalFadingEdge(fadeLength = 0.dp, bottomFadeLength = floatingBarBottomGap()),
                state = listState,
                contentPadding = listContentPadding(top = 0.dp),
            ) {
                item {
                    val latest = ui.episodes.firstOrNull()
                    val latestPlaying = latest != null && playback.episode?.id == latest.episode.id && playback.isPlaying
                    Hero(
                        modifier = Modifier.onSizeChanged { heroHeight = it.height },
                        backdrop = entry,
                        contentModifier = shown,
                        coverModifier = Modifier
                            .graphicsLayer { alpha = if (coverRest != null && chipInRoot != null) 0f else content() }
                            .onGloballyPositioned {
                                if (listState.firstVisibleItemIndex == 0) {
                                    coverRest = it.positionInRoot() + Offset(0f, listState.firstVisibleItemScrollOffset.toFloat())
                                }
                            },
                        onCoverLoaded = { coverKey = it },
                        fadeLine = fadeLine,
                        ui = ui,
                        colors = hero,
                        latestPlaying = latestPlaying,
                        onPlayLatest = latest?.let { row -> { if (latestPlaying) onPause() else onPlay(row.episode, ui.podcast) } },
                        onToggleSubscribe = state::toggleSubscribe,
                        onAuthorClick = onAuthorClick,
                        authorClickLabel = authorClickLabel,
                        creator = ui.podcast.author?.takeIf { it.isNotBlank() } ?: creatorName,
                    )
                }
                // The line under the hero that the episodes come down from, in place of the first one's own divider.
                val gapDivider = ui.error == null && ui.episodes.isNotEmpty()
                item(key = "pull") { PullGap(divider = gapDivider) }
                if (ui.error != null) {
                    item {
                        Text(
                            ui.error!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                            modifier = shown.contentInset().padding(horizontal = ScreenGutter, vertical = 8.dp),
                        )
                    }
                }
                if (ui.episodes.isEmpty() && !ui.refreshing && ui.error == null) {
                    item {
                        Text(
                            if (ui.offline) "No downloaded episodes" else "No episodes yet", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = shown.contentInset().padding(horizontal = ScreenGutter, vertical = 12.dp),
                        )
                    }
                }
                itemsIndexed(ui.episodes, contentType = { _, _ -> EpisodeRowType }) { index, row ->
                    val isCurrent = playback.episode?.id == row.episode.id
                    EpisodeItem(
                        row = row,
                        isCurrent = isCurrent,
                        isPlaying = isCurrent && playback.isPlaying,
                        artworkUrl = row.episode.artworkUrl ?: ui.podcast.artworkUrl,
                        showArtworkUrl = ui.podcast.artworkUrl,
                        onPlay = { onPlay(row.episode, ui.podcast) },
                        onPause = onPause,
                        downloadControl = downloads?.let { d -> { m -> DownloadButton(row.episode, d, m, compact = true, played = row.played) } },
                        divider = index > 0 || !gapDivider,
                        dividerBelow = index < ui.episodes.lastIndex,
                        onToggleComplete = onSetCompleted?.let { { it(row.episode, !row.played) } },
                        modifier = shown,
                        snapGuides = guides,
                    )
                }
            }
            // The collapsed bar's backdrop, fading in as the hero's bottom slides under it, and once it has, the
            // divider fixed along its bottom for the rows to scroll under.
            val barHeight = with(density) { barPx.toDp() }
            Box(Modifier.fillMaxWidth()) {
                Box(
                    Modifier.fillMaxWidth().height(barHeight + ListDividerHeight)
                        .graphicsLayer { alpha = collapse * entry() }
                        .background(MaterialTheme.colorScheme.background, AboveListDivider),
                )
                if (barPinned) ListDivider(Modifier.padding(top = barHeight))
            }
            val rest = coverRest
            val chip = chipInRoot
            if (rest != null && chip != null) {
                CoverMorph(
                    ui = ui,
                    coverKey = coverKey ?: origin?.image,
                    from = Rect(rest - screenInRoot, Size(coverPx, coverPx)),
                    to = chip.translate(-screenInRoot),
                    fraction = coverFraction,
                    origin = origin?.let { it.bounds.translate(-screenInRoot) to it.corner },
                    entry = entry,
                    flight = flight,
                    onShown = onCoverShown,
                )
            }
            Box(shown) {
                CollapsedBar(
                    colors = hero,
                    collapsed = collapse > 0.5f,
                    onBack = onBack,
                    onClick = { scope.launch { listState.animateScrollToItem(0) } },
                    chipBounds = Modifier.onGloballyPositioned { chipInRoot = it.boundsInRoot() },
                )
            }
        }
    }
}

/**
 * Fades this out as it slides under [line], a y in the root: fully shown while its top is [leadDistance] or more
 * below the line, gone once [FadeSpan] of its height has passed it.
 */
@Composable
private fun Modifier.fadeUnder(line: () -> Float, leadDistance: Dp = FadeLead): Modifier {
    var top by remember { mutableStateOf(Float.NaN) }
    var height by remember { mutableStateOf(0f) }
    val lead = with(LocalDensity.current) { leadDistance.toPx() }
    return onGloballyPositioned {
        top = it.positionInRoot().y
        height = it.size.height.toFloat()
    }.graphicsLayer {
        val span = height * FadeSpan
        alpha = if (top.isNaN()) 1f else ((top + span - line()) / (span + lead)).coerceIn(0f, 1f)
    }
}

/**
 * Top of the page on a gradient from the cover's colour: the cover large and centred, the title and creator under it,
 * "Latest episode" and subscribe buttons, then the description.
 */
@Composable
private fun Hero(
    modifier: Modifier,
    /** The gradient's opacity, which comes and goes with the page's background. */
    backdrop: () -> Float,
    /** On everything under the cover: the title, creator, buttons and description. */
    contentModifier: Modifier,
    coverModifier: Modifier,
    onCoverLoaded: (MemoryCache.Key?) -> Unit,
    fadeLine: () -> Float,
    ui: PodcastDetailUiState,
    colors: PlayerColors,
    latestPlaying: Boolean,
    onPlayLatest: (() -> Unit)?,
    onToggleSubscribe: () -> Unit,
    onAuthorClick: ((String) -> Unit)?,
    authorClickLabel: (String) -> String,
    creator: String?,
) {
    val ink = colors.onContainer
    val page = MaterialTheme.colorScheme.background
    // Like Now Playing: the cover's colour at the top, melting into the page by the bottom.
    val gradient = Brush.verticalGradient(0f to colors.backdrop, 1f to page)
    Column(
        modifier.fillMaxWidth().drawBehind { drawRect(gradient, alpha = backdrop()) }
            .contentInset()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = ScreenGutter, end = ScreenGutter, top = CollapsedBarTop + (CollapsedBarHeight - TitleChipArtSize) / 2, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Normally hidden: the copy morphing into the chip stands in for it.
        Artwork(
            ui.podcast.artworkUrl, ui.podcast.title,
            coverModifier.shadow(16.dp, RoundedCornerShape(HeroCoverCorner)),
            size = HeroCoverSize, corner = HeroCoverCorner, onLoaded = onCoverLoaded,
            liveArchive = ui.podcast.isLiveArchive, playlist = ui.podcast.playlistId,
        )
        Spacer(Modifier.height(16.dp))
        Column(contentModifier, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                ui.podcast.title, style = MaterialTheme.typography.titleLarge, color = ink,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fadeUnder(fadeLine),
            )
            creator?.let { author ->
                Row(
                    Modifier.padding(top = 2.dp).fadeUnder(fadeLine).clip(CircleShape)
                        .then(
                            if (onAuthorClick != null) {
                                Modifier.clickable(onClickLabel = authorClickLabel(author)) { onAuthorClick(author) }
                            } else {
                                Modifier
                            },
                        )
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        author, style = MaterialTheme.typography.bodyMedium, color = ink.copy(alpha = 0.75f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    if (onAuthorClick != null) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp), tint = ink.copy(alpha = 0.75f))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fadeUnder(fadeLine),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    onClick = { onPlayLatest?.invoke() },
                    enabled = onPlayLatest != null,
                    shape = CircleShape,
                    color = ink.copy(alpha = 0.1f),
                    contentColor = ink,
                    modifier = Modifier.height(44.dp).widthIn(min = 200.dp),
                ) {
                    Row(
                        Modifier.padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(if (latestPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, Modifier.size(22.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Latest episode", style = MaterialTheme.typography.labelLarge)
                    }
                }
                HeroButton(onClick = onToggleSubscribe, colors = colors, enabled = ui.podcast.feedUrl.isNotBlank()) {
                    Icon(
                        if (ui.subscribed) Icons.Default.Check else Icons.Default.Add,
                        if (ui.subscribed) "Unsubscribe" else "Subscribe",
                        Modifier.size(22.dp),
                    )
                }
            }
            Description(ui, ink, Modifier.fadeUnder(fadeLine, leadDistance = DescriptionFadeLead))
        }
    }
}

/** The show page's episode rows, as against the hero and the notes above them. */
private const val EpisodeRowType = "episode"

private val HeroCoverSize = 200.dp

/** Where the hero cover rests, in the root, on a page [width] px wide under a status bar [statusBarTop] px tall. */
internal fun Density.heroCoverRest(width: Int, statusBarTop: Int): Rect {
    val size = HeroCoverSize.toPx()
    val top = statusBarTop + (CollapsedBarTop + (CollapsedBarHeight - TitleChipArtSize) / 2).toPx()
    return Rect(Offset((width - size) / 2, top), Size(size, size))
}

private const val MorphStretch = 2.5f
private val FadeLead = 0.dp
private val DescriptionFadeLead = 32.dp
private const val FadeSpan = 0.5f
private val MorphShrinkEasing = CubicBezierEasing(0.55f, 0f, 0.35f, 1f)
private val MorphSlideEasing = CubicBezierEasing(0.7f, 0f, 0.3f, 1f)
private val HeroCoverCorner = 16.dp
internal val CollapsedBarTop = 8.dp
internal val CollapsedBarHeight = 64.dp
private val ChipCorner = 16.dp
private val InnerShadowDepth = 3.dp
private val FrameBorder = 2.dp
private val ChipWidth = 160.dp
private val ChipHeight = 52.dp

/**
 * What the hero collapses into: a round Back button at the start, always shown, and at the end a chip like Now
 * Playing's whose background is the hero cover itself ([CoverMorph], drawn underneath), which identifies the show.
 * Tapping the chip scrolls back to the top.
 */
@Composable
private fun CollapsedBar(
    colors: PlayerColors,
    collapsed: Boolean,
    onBack: () -> Unit,
    onClick: () -> Unit,
    chipBounds: Modifier,
) {
    Row(
        Modifier.fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(top = CollapsedBarTop)
            .height(CollapsedBarHeight)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(TitleChipArtSize).clip(CircleShape).background(colors.container).clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", Modifier.size(22.dp), tint = colors.onContainer) }
        Box(
            Modifier.then(chipBounds)
                .size(ChipWidth, ChipHeight)
                .clip(RoundedCornerShape(ChipCorner))
                .clickable(enabled = collapsed, onClickLabel = "Scroll to top", onClick = onClick),
        )
    }
}

/**
 * The hero cover, which becomes the chip's background: it moves from its place in the hero to the chip's bounds,
 * reshaping from a square into the pill and cropping to fill it around its most detailed band, in the cover's own
 * colours. While the page opens or closes ([flight] below 1) it flies between that place and [origin], the tapped
 * cover's bounds and corner; without an origin it fades with the page ([entry]) instead.
 */
@Composable
private fun CoverMorph(
    ui: PodcastDetailUiState,
    coverKey: MemoryCache.Key?,
    from: Rect,
    to: Rect,
    fraction: () -> Float,
    origin: Pair<Rect, Dp>?,
    entry: () -> Float,
    flight: () -> Float,
    onShown: () -> Unit,
) {
    // Leaving the tapped tile, this stays unseen until its image is in, so neither shows its placeholder; if the
    // image never comes, it shows once the page is open.
    var loaded by remember { mutableStateOf(false) }
    // Read only while laying out and drawing, so scrolling and the flight move the cover without recomposing it.
    val shrink = { MorphShrinkEasing.transform(fraction()) }
    val lift = { if (origin == null) 1f else flight() }
    val density = LocalDensity.current
    val ribbonSide = {
        if (origin == null) HeroCoverSize
        else with(density) { lerp(origin.first.minDimension.toDp(), HeroCoverSize, flight()) }
    }
    val bounds = {
        // Both ease in slowly; the slide toward the chip and the narrowing trail the shrink, so the cover travels on an arc.
        val shrunk = shrink()
        val slide = MorphSlideEasing.transform(fraction())
        val centre = from.center.x + (to.center.x - from.center.x) * slide
        val width = from.width + (to.width - from.width) * slide
        val placed = Rect(
            left = centre - width / 2,
            top = from.top + (to.top - from.top) * shrunk,
            right = centre + width / 2,
            bottom = from.bottom + (to.bottom - from.bottom) * shrunk,
        )
        if (origin == null) placed else lerp(origin.first, placed, flight())
    }
    val corner = {
        val onPage = lerp(HeroCoverCorner, ChipCorner, shrink())
        if (origin == null) onPage else lerp(origin.second, onPage, flight())
    }
    // The chip shows a wide band of the square cover: frame the band with the most detail (lettering, faces) rather
    // than the middle, which is often empty.
    val rows = rememberRowDetail(ui.podcast.artworkUrl)
    val band = if (to.width > 0f) (to.height / to.width).coerceIn(0f, 1f) else 1f
    val bias = remember(rows, band) { rows?.let { cropBias(detailFocus(it, band), band) } ?: 0f }
    Box(
        Modifier
            .layout { measurable, _ ->
                val at = bounds()
                val placeable = measurable.measure(
                    Constraints.fixed(at.width.roundToInt().coerceAtLeast(0), at.height.roundToInt().coerceAtLeast(0)),
                )
                layout(placeable.width, placeable.height) { placeable.place(at.topLeft.round()) }
            }
            .graphicsLayer {
                alpha = when {
                    origin == null -> entry()
                    loaded || flight() >= 1f -> 1f
                    else -> 0f
                }
                shape = RoundedCornerShape(corner())
                clip = true
                // Gone well before landing: at tiny elevations the platform draws the shadow as a hairline outline.
                shadowElevation = (16.dp * lift() * (1f - shrink() / 0.6f).coerceIn(0f, 1f)).toPx()
            }
            .drawWithContent {
                drawContent()
                drawFrame(corner().toPx(), shrink() * lift())
            },
    ) {
        Artwork(
            ui.podcast.artworkUrl, ui.podcast.title, Modifier.matchParentSize(), corner = 0.dp,
            alignment = BiasAlignment(0f, bias), placeholderKey = coverKey, liveArchive = ui.podcast.isLiveArchive,
            playlist = ui.podcast.playlistId,
            // Follows the cover's flight between screens, but not its collapse into the bar.
            ribbonFor = ribbonSide,
            onLoaded = {
                if (!loaded) {
                    loaded = true
                    onShown()
                }
            },
        )
    }
}

/**
 * A board-like frame over the chip: an inner shadow darkening in from the edges, then a white border, both scaled
 * by [strength] so the hero cover starts plain.
 */
private fun DrawScope.drawFrame(cornerPx: Float, strength: Float) {
    if (strength <= 0f) return
    val depth = InnerShadowDepth.toPx()
    val steps = 8
    val step = depth / steps
    // Concentric strokes fading inward stand in for a blurred inner shadow, which Compose has no common API for.
    for (i in 0 until steps) {
        val inset = i * step + step / 2
        val falloff = 1f - i / steps.toFloat()
        drawRoundRect(
            color = Color.Black.copy(alpha = 0.22f * falloff * falloff * strength),
            topLeft = Offset(inset, inset),
            size = Size(size.width - 2 * inset, size.height - 2 * inset),
            cornerRadius = CornerRadius((cornerPx - inset).coerceAtLeast(0f)),
            style = Stroke(step),
        )
    }
    // Centred on the clipped edge at twice the width: the clip trims the outer half, and the white covers the edge's
    // anti-aliasing so no sliver of the cover shows at the corners.
    drawRoundRect(
        color = Color.White.copy(alpha = 0.9f * strength),
        cornerRadius = CornerRadius(cornerPx),
        style = Stroke(FrameBorder.toPx() * 2),
    )
}

/** Round translucent button on the hero backdrop. */
@Composable
private fun HeroButton(
    onClick: () -> Unit,
    colors: PlayerColors,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = colors.onContainer.copy(alpha = 0.1f),
        contentColor = colors.onContainer,
        modifier = modifier.size(44.dp),
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
private fun Description(ui: PodcastDetailUiState, ink: Color, fade: Modifier) {
    var expanded by remember { mutableStateOf(false) }
    // Directory search results carry no description; it arrives with the feed.
    val description = ui.podcast.description?.let(::plainText)
    val modifier = Modifier.padding(top = 12.dp).then(fade)
    when {
        !description.isNullOrBlank() -> Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = ink.copy(alpha = 0.75f),
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier.fillMaxWidth().widthIn(max = 720.dp)
                .animateContentSize(tween(durationMillis = 250, easing = FastOutSlowInEasing))
                .clickable(interactionSource = null, indication = null) { expanded = !expanded },
        )
        ui.refreshing -> Text(
            "Loading details…", style = MaterialTheme.typography.bodyMedium,
            color = ink.copy(alpha = 0.75f), modifier = modifier,
        )
    }
}
