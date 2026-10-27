package io.waves.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import io.waves.data.ArtworkColorStore
import io.waves.model.EpisodeId
import io.waves.model.HistoryEntry
import io.waves.model.Podcast
import io.waves.offline.offlinePlayableIds
import io.waves.youtube.YoutubeUrls
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val LightColors = lightColorScheme(
    primary = Color(0xFF3D8BD4), onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E8FA), onPrimaryContainer = Color(0xFF0B3A63),
    secondary = Color(0xFF5F7A91), secondaryContainer = Color(0xFFDCE7F2), onSecondaryContainer = Color(0xFF1A3347),
    tertiary = Color(0xFFB0476A),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFA8D1FF), onPrimary = Color(0xFF00325A),
    primaryContainer = Color(0xFF1F4A70), onPrimaryContainer = Color(0xFFD6E8FA),
    secondary = Color(0xFFBACADB), secondaryContainer = Color(0xFF37495A), onSecondaryContainer = Color(0xFFDCE7F2),
    tertiary = Color(0xFFFFB0C8),
)

/** @param artworkColors where colours picked from cover art are kept (see [rememberArtworkSeed]). */
@Composable
fun WavesTheme(artworkColors: ArtworkColorStore = LocalArtworkColors.current, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalArtworkColors provides artworkColors) {
        MaterialTheme(
            colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
            typography = AppTypography,
            content = content,
        )
    }
}

private val AppTypography = appTypography()

/** App-wide state holders. Create once per process (they launch work in [AppDependencies.appScope]). */
class AppState(deps: AppDependencies, scope: kotlinx.coroutines.CoroutineScope) {
    val navigator = Navigator()
    val artworkColors = deps.artworkColors
    val playback = PlaybackController(
        deps.player, deps.library, deps.deviceId, scope, deps.onLocalChange, settings = deps.settings.settings,
        restoreLastSession = true, session = deps.playbackSession,
    )
    val discover = DiscoverState(
        deps.directory, scope, deps.parseAppleUrl,
        country = deps.settings.settings.map { it.effectiveCountry }
            .stateIn(scope, SharingStarted.Eagerly, deps.settings.settings.value.effectiveCountry),
        resolveYoutube = deps.youtube?.let { channels -> { channels.resolve(it) } },
    )
    val home = HomeState(
        deps.library, deps.feeds, scope,
        onLocalChange = deps.onLocalChange,
        offline = deps.offline,
        downloads = deps.downloads?.downloads ?: flowOf(emptyList()),
        playbackCache = deps.playbackCache?.entries ?: flowOf(emptyList()),
        cachedEpisodeIds = deps.downloads?.cachedEpisodeIds ?: flowOf(emptySet()),
        onEpisodesAbsent = deps.onEpisodesAbsent,
    )
    val history = HistoryState(deps.library, scope)
    val recentFetches = RecentFetches(deps.feedFetches)
    /** Null while online. While offline: the episodes that can play without a connection. */
    val offlinePlayable: StateFlow<Set<EpisodeId>?> = combine(
        deps.offline,
        deps.downloads?.downloads ?: flowOf(emptyList()),
        deps.playbackCache?.entries ?: flowOf(emptyList()),
        deps.downloads?.cachedEpisodeIds ?: flowOf(emptySet()),
    ) { off, items, entries, cached -> if (off) offlinePlayableIds(items, entries, cached) else null }
        .stateIn(scope, SharingStarted.Eagerly, null)
    val settings = SettingsState(deps.auth, deps.sync, scope, deps.settings, offline = deps.offline)
    /** Null when the platform provides no [AppDependencies.downloads]; download UI is hidden then. */
    val downloads = deps.downloads?.let {
        DownloadsState(
            it, deps.library, deps.player.state, scope, deps.settings,
            playbackCache = deps.playbackCache, offline = deps.offline,
            deviceId = deps.deviceId, onLocalChange = deps.onLocalChange,
        )
    }
    /** Null without [AppDependencies.releases] and [AppDependencies.updateInstaller]; the Updates section is hidden then. */
    val updates = if (deps.releases != null && deps.updateInstaller != null) {
        UpdateState(deps.releases, deps.updateInstaller, scope)
    } else null
}

/**
 * @param app app-wide state holders. Pass one created once per process (see [AppState]) so navigation and
 *   playback bookkeeping survive re-composition (e.g. Activity recreation) and the platform can reach the
 *   [Navigator] (hardware back) and [PlaybackController] (save on background / exit).
 */
@Composable
fun WavesApp(deps: AppDependencies, app: AppState = remember(deps) { AppState(deps, deps.appScope) }) {
    val scope = rememberCoroutineScope()
    val nav = app.navigator

    val openPodcast: (Podcast, Boolean) -> Unit = { podcast, autoFetch ->
        nav.push(Screen.PodcastDetail(podcast, autoFetch))
    }
    val playById: (EpisodeId, String) -> Unit = { id, podcastTitle ->
        scope.launch {
            val episode = deps.library.episode(id) ?: return@launch
            val podcast = deps.library.podcast(id.feedUrl) ?: Podcast(feedUrl = id.feedUrl, title = podcastTitle)
            app.playback.play(episode, podcast)
        }
    }
    val playFromHistory: (HistoryEntry) -> Unit = { entry -> playById(entry.episodeId, entry.podcastTitle) }

    WavesTheme(app.artworkColors) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val isLandscape = maxWidth > maxHeight
            val inset = contentInset(maxWidth, isLandscape)
            val expanded = nav.current == Screen.NowPlaying
            val playback by app.playback.state.collectAsState()
            val playingPodcast by app.playback.podcast.collectAsState()
            val playerColors = rememberPlayerColors(rememberArtworkSeed(playingPodcast?.artworkUrl))
            val barScroll = rememberBottomBarScrollConnection()
            LaunchedEffect(nav.content) { barScroll.reset() }
            LaunchedEffect(playback.episode?.id, playback.isPlaying, playback.isEnded) { barScroll.reset() }
            val systemBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            val clearance = if (playback.episode != null) bottomBarClearance(systemBottom, isLandscape) else systemBottom + 16.dp

            // Tab roots live in a pager: swiping pages switches tabs, and the navigator stays the source of truth.
            val pager = rememberPagerState(initialPage = nav.currentTab.ordinal) { Tab.entries.size }
            val stripSlot = remember { TabStripSlot() }
            val coverOrigins = remember { CoverOrigins() }
            val atRoot = nav.content is Screen.Root
            val homeShown = atRoot && nav.currentTab == Tab.Home
            // Re-sort Home once it is off screen (after the transition away), never while it is visible.
            LaunchedEffect(homeShown) {
                if (!homeShown) {
                    delay(600)
                    app.home.releaseOrder()
                }
            }
            LaunchedEffect(pager) {
                snapshotFlow { pager.settledPage }.collect { page ->
                    val tab = Tab.entries[page]
                    if (nav.content is Screen.Root && nav.currentTab != tab) nav.select(tab)
                }
            }
            LaunchedEffect(nav.currentTab) {
                val page = nav.currentTab.ordinal
                if (pager.currentPage == page && pager.currentPageOffsetFraction == 0f) return@LaunchedEffect
                if (nav.content is Screen.Root) pager.animateScrollToPage(page) else pager.scrollToPage(page)
            }

            Box(Modifier.fillMaxSize()) {
                CompositionLocalProvider(
                    LocalBottomClearance provides clearance,
                    LocalPlayerColors provides playerColors,
                    LocalIsLandscapeLayout provides isLandscape,
                    LocalContentInset provides inset,
                    LocalCoverOrigins provides coverOrigins,
                ) {
                    val hiddenByPlayer = if (expanded) Modifier.clearAndSetSemantics {} else Modifier
                    CurrentScreen(
                        app, deps, pager, stripSlot,
                        Modifier.fillMaxSize().nestedScroll(barScroll).then(hiddenByPlayer),
                        openPodcast, playFromHistory,
                    )
                    PlayerSheet(
                        controller = app.playback,
                        expanded = expanded,
                        onExpand = { nav.push(Screen.NowPlaying) },
                        onCollapse = { if (nav.current == Screen.NowPlaying) nav.pop() },
                        hiddenByScroll = barScroll.hidden,
                        bottomInset = systemBottom,
                        downloads = app.downloads,
                        onOpenPodcast = { podcast ->
                            if (nav.current == Screen.NowPlaying) nav.pop()
                            val showing = (nav.content as? Screen.PodcastDetail)?.podcast?.feedUrl == podcast.feedUrl
                            if (!showing) openPodcast(podcast, false)
                        },
                        playerColors = playerColors,
                    )
                }
            }
        }
    }
}

@Composable
private fun CurrentScreen(
    app: AppState,
    deps: AppDependencies,
    pager: PagerState,
    stripSlot: TabStripSlot,
    modifier: Modifier,
    openPodcast: (Podcast, Boolean) -> Unit,
    playFromHistory: (HistoryEntry) -> Unit,
) {
    val nav = app.navigator
    val playback by app.playback.state.collectAsState()
    val scope = rememberCoroutineScope()
    val scrollRegions = remember { HorizontalScrollRegions() }
    val origins = LocalCoverOrigins.current
    val shown = Shown(nav.content, nav.backStack.count { it != Screen.NowPlaying } - 1)
    val screens = updateTransition(shown, label = "screen")
    // The tabs stay composed under the screens opened on top of them, so closing one has nothing to rebuild while its
    // cover flies home; they are only left undrawn while fully covered.
    val covered = screens.currentState.screen !is Screen.Root && screens.targetState.screen !is Screen.Root
    val density = LocalDensity.current
    val statusBarTop = WindowInsets.statusBars.getTop(density)
    var width by remember { mutableIntStateOf(0) }
    Box(modifier.onSizeChanged { width = it.width }) {
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { alpha = if (covered) 0f else 1f }
                .then(if (covered) Modifier.clearAndSetSemantics {} else Modifier),
        ) {
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxSize()
                    .then(if (wheelPagesTabs) Modifier.wheelPaging(pager, scope, scrollRegions) else Modifier),
                userScrollEnabled = !wheelPagesTabs,
                flingBehavior = PagerDefaults.flingBehavior(
                    state = pager,
                    // settledPage is where the gesture began, so a long drag can't carry past the adjacent tab.
                    pagerSnapDistance = remember(pager) {
                        object : PagerSnapDistance {
                            override fun calculateTargetPage(
                                startPage: Int, suggestedTargetPage: Int, velocity: Float, pageSize: Int, pageSpacing: Int,
                            ) = suggestedTargetPage.coerceIn(pager.settledPage - 1, pager.settledPage + 1)
                        }
                    },
                ),
                beyondViewportPageCount = 1,
                key = { Tab.entries[it] },
            ) { index ->
                val tab = Tab.entries[index]
                val page = remember(tab) { TabPage(tab, stripSlot) }
                Box(
                    Modifier.fillMaxSize()
                        .onPlaced { page.coordinates = it }
                        // Neighbouring pages stay composed for smooth swipes; only the settled one is accessible.
                        .then(if (pager.settledPage == index) Modifier else Modifier.clearAndSetSemantics {}),
                ) {
                    CompositionLocalProvider(LocalTabPage provides page, LocalHorizontalScrollRegions provides scrollRegions) {
                        when (tab) {
                            Tab.Home -> HomeScreen(
                                state = app.home,
                                playback = playback,
                                onOpenPodcast = { openPodcast(it, false) },
                                onPlay = app.playback::play,
                                onPause = app.playback::pause,
                                onDiscover = { nav.select(Tab.Discover) },
                                downloads = app.downloads,
                            )
                            Tab.Discover -> DiscoverScreen(app.discover) { openPodcast(it, true) }
                            Tab.History -> HistoryScreen(app.history, deps.deviceId, playFromHistory, app.downloads)
                            Tab.Settings -> SettingsScreen(app.settings, deps.deviceId, app.downloads, app.updates)
                        }
                    }
                }
            }
            // One strip over the pages, so it stays put as they swipe; beneath any screen opened on top of them.
            RootTabStrip(nav, pager, stripSlot, deps.offline)
        }
        screens.AnimatedContent(
            // A screen on top takes every touch, even where it shows nothing, so none reach the tabs beneath it.
            modifier = Modifier.fillMaxSize().then(if (shown.screen is Screen.Root) Modifier else Modifier.pointerInput(Unit) {}),
            // Tab changes are the pager's to animate.
            contentKey = { if (it.screen is Screen.Root) Screen.Root::class else it },
            // The screen beneath stays as it is: a show page grows out of it and shrinks back into it on top.
            transitionSpec = {
                (EnterTransition.None togetherWith ExitTransition.KeepUntilTransitionsFinished)
                    .apply { targetContentZIndex = targetState.depth.toFloat() }
                    .using(null)
            },
        ) { (screen) -> when (screen) {
            is Screen.Root -> Unit
            is Screen.PodcastDetail -> {
                val origin = remember(screen) { origins.take(screen.podcast.feedUrl) }
                DisposableEffect(origin) { onDispose { origins.landed(origin) } }
                val rest = with(density) { heroCoverRest(width, statusBarTop) }
                // All 0 while the page is shut (not yet open, or closed again) and 1 once open: the background's fade,
                // the same for every show; the cover's flight out of its tile, timed by how far it goes; and the fade
                // of everything else, which follows the background and the cover in and, on the way out, is gone
                // before the background.
                val entry by transition.animateFloat(
                    transitionSpec = {
                        if (targetState == EnterExitState.Visible) tween(BackdropEnterMillis, easing = ScreenTransitionEasing)
                        else tween(BackdropExitMillis, easing = ScreenTransitionEasing)
                    },
                    label = "entry",
                ) { if (it == EnterExitState.Visible) 1f else 0f }
                val flight by transition.animateFloat(
                    transitionSpec = {
                        val closing = targetState != EnterExitState.Visible
                        val millis = origin?.let { with(density) { coverFlightMillis(it.bounds, rest, closing) } }
                        tween(millis ?: BackdropEnterMillis, easing = if (closing) CoverReturnEasing else ScreenTransitionEasing)
                    },
                    label = "flight",
                ) { if (it == EnterExitState.Visible) 1f else 0f }
                val content by transition.animateFloat(
                    transitionSpec = {
                        if (targetState == EnterExitState.Visible) {
                            val flightMillis = origin?.let { with(density) { coverFlightMillis(it.bounds, rest, closing = false) } }
                            tween(ContentEnterMillis, delayMillis = contentEnterDelayMillis(flightMillis), easing = LinearOutSlowInEasing)
                        } else {
                            tween(ContentExitMillis, easing = FastOutLinearInEasing)
                        }
                    },
                    label = "content",
                ) { if (it == EnterExitState.Visible) 1f else 0f }
                val detail = rememberHolder(screen) { scope ->
                    PodcastDetailState(
                        initial = screen.podcast,
                        feeds = deps.feeds,
                        library = deps.library,
                        directory = deps.directory,
                        scope = scope,
                        autoFetch = screen.autoFetch,
                        onLocalChange = deps.onLocalChange,
                        onEpisodesAbsent = deps.onEpisodesAbsent,
                        offline = deps.offline,
                        playable = app.offlinePlayable,
                        recentFetches = app.recentFetches,
                    )
                }
                PodcastScreen(
                    state = detail,
                    playback = playback,
                    onBack = { nav.pop() },
                    onPlay = app.playback::play,
                    onPause = app.playback::pause,
                    downloads = app.downloads,
                    onAuthorClick = { author ->
                        val search = {
                            nav.select(Tab.Discover)
                            app.discover.onQueryChange(author)
                        }
                        val youtube = deps.youtube
                        if (youtube != null && YoutubeUrls.playlistIdOfFeed(screen.podcast.feedUrl) != null) {
                            scope.launch {
                                val channel = youtube.creator(screen.podcast.feedUrl)
                                if (channel == null) search()
                                else openPodcast(deps.library.podcast(channel.feedUrl) ?: channel, true)
                            }
                        } else {
                            search()
                        }
                    },
                    authorClickLabel = if (YoutubeUrls.playlistIdOfFeed(screen.podcast.feedUrl) != null) {
                        { "Open $it" }
                    } else {
                        { "Search podcasts by $it" }
                    },
                    origin = origin,
                    entry = { entry },
                    flight = { flight },
                    content = { content },
                    onCoverShown = { origin?.let(origins::depart) },
                )
            }
            Screen.NowPlaying -> Unit // drawn by PlayerSheet
        } }
    }
}

@Composable
private fun RootTabStrip(nav: Navigator, pager: PagerState, stripSlot: TabStripSlot, offline: StateFlow<Boolean>) {
    val stripWidth = stripSlot.widths[nav.currentTab] ?: return
    val isOffline by offline.collectAsState()
    TabStrip(
        position = pager.currentPage + pager.currentPageOffsetFraction,
        onSelect = nav::select,
        offline = isOffline,
        // Centred on the screen, but kept within the slot so it never runs under the bar's actions.
        modifier = Modifier
            .offset { IntOffset(0, stripSlot.offset.y) }
            .layout { measurable, constraints ->
                val placeable = measurable.measure(
                    constraints.copy(minWidth = 0, maxWidth = minOf(stripWidth, constraints.maxWidth)),
                )
                layout(constraints.maxWidth, placeable.height) {
                    val left = stripSlot.offset.x
                    val centred = (constraints.maxWidth - placeable.width) / 2
                    placeable.place(centred.coerceIn(left, maxOf(left, left + stripWidth - placeable.width)), 0)
                }
            },
    )
}

/** The screen beneath the player sheet and how deep in the back stack it sits, which puts newer screens on top. */
private data class Shown(val screen: Screen, val depth: Int)
