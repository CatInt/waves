package io.waves.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.waves.search.ChartShow
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.size
import io.waves.model.Episode
import io.waves.player.PlaybackState
import io.waves.model.Podcast
import io.waves.search.ChartEpisode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    state: DiscoverState,
    onOpenPodcast: (Podcast) -> Unit,
    /** What's loaded in the player, so a top episode's button shows it playing. */
    playback: PlaybackState = PlaybackState(),
    /** Null leaves the top episodes without a play button. */
    onPlay: ((Episode, Podcast) -> Unit)? = null,
    onPause: () -> Unit = {},
) {
    val ui by state.state.collectAsState()
    // The genre whose heading the charts have scrolled fully under the divider, which the header then names.
    var scrolledGenre by remember { mutableStateOf<String?>(null) }
    Scaffold(
        topBar = { WavesTopBar("Discover", icon = Tab.Discover.icon) },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // As tall as Home's shelf, so the divider under it stays put when swiping between the tabs: the search
            // field, and under it what the list below is, just above the divider (as History names its day).
            Column(Modifier.fillMaxWidth().height(ShelfBandHeight)) {
                // Its top on the line Home's shelf covers start on.
                Box(Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp), contentAlignment = Alignment.TopCenter) {
                    OutlinedTextField(
                        value = ui.query,
                        onValueChange = state::onQueryChange,
                        modifier = Modifier.fillMaxWidth().contentInset().padding(horizontal = ScreenGutter),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (ui.query.isNotEmpty()) {
                                IconButton(onClick = state::clearQuery) { Icon(Icons.Default.Clear, "Clear search") }
                            }
                        },
                        placeholder = { Text("Search or paste a link") },
                    )
                }
                SectionLabel(
                    listHeader(ui, scrolledGenre),
                    // Drawn a little higher, off the divider, without moving the list.
                    Modifier.contentInset().padding(start = ScreenGutter, end = ScreenGutter, bottom = 4.dp).offset(y = -BandLabelLift),
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val pull = rememberPullProgress()
                // Results scrolling up end at the divider's line.
                WavesPullToRefreshBox(
                    isRefreshing = if (ui.isSearching) ui.searching else ui.topLoading,
                    onRefresh = state::refresh,
                    modifier = Modifier.fillMaxSize(),
                    gapTop = ListDividerHeight,
                    progress = pull,
                    // A search running or the charts loading show in the gap, as a pulled refresh would.
                    showsRefresh = true,
                ) {
                    // On the results, not the pull's box: the waves the pull opens come down through the line.
                    Box(Modifier.fillMaxSize().clip(BelowListDivider)) {
                        if (ui.isSearching) SearchResults(ui, onOpenPodcast) else TopCharts(ui, state, onOpenPodcast, playback, onPlay, onPause) { scrolledGenre = it }
                    }
                }
                // Takes on the waves' colour as a pull opens the gap under it.
                ListDivider(tint = { pull.value })
            }
        }
    }
}

/**
 * What Discover's list shows: the charts (named for the [genre] scrolled into, if any), or what the search (or a pasted
 * link) found; blank while there's nothing.
 */
internal fun listHeader(ui: DiscoverUiState, genre: String? = null): String = when {
    !ui.isSearching -> genre ?: "Hits"
    ui.searching || ui.searchError != null || ui.results.isEmpty() -> ""
    ui.resolvedFromUrl -> "From the link"
    ui.results.size == 1 -> "1 show"
    else -> "${ui.results.size} shows"
}

@Composable
private fun SearchResults(ui: DiscoverUiState, onOpenPodcast: (Podcast) -> Unit) {
    when {
        ui.searching && ui.results.isEmpty() -> Box(Modifier.fillMaxSize())
        ui.searchError != null -> Centered { Text(ui.searchError, color = MaterialTheme.colorScheme.error) }
        ui.results.isEmpty() -> Centered { Text("No podcasts found") }
        else -> LazyVerticalGrid(
            // Spanning a landscape window, rows keep to the centred content column, which takes one.
            columns = if (LocalIsLandscapeLayout.current) GridCells.Fixed(1) else GridCells.Adaptive(360.dp),
            contentPadding = listContentPadding(top = ListDividerHeight),
            modifier = Modifier.fillMaxSize().verticalFadingEdge(fadeLength = 0.dp, bottomFadeLength = floatingBarBottomGap()),
        ) {
            itemsIndexed(ui.results) { index, podcast ->
                val anchor = rememberCoverAnchor(podcast.feedUrl, corner = 8.dp)
                Row(
                    Modifier.fillMaxWidth().topDivider(index > 0)
                        .clickable {
                            anchor.mark()
                            onOpenPodcast(podcast)
                        }
                        .contentInset()
                        .padding(horizontal = ScreenGutter, vertical = ListRowVerticalPadding),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ListRowGap),
                ) {
                    Artwork(
                        podcast.artworkUrl, podcast.title, anchor.modifier, size = 48.dp, onLoaded = anchor.onLoaded,
                        liveArchive = podcast.isLiveArchive, playlist = podcast.playlistId,
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(podcast.title, style = listTitleStyle(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        podcast.author?.let { MetaText(it) }
                    }
                }
            }
        }
    }
}

/**
 * Apple's charts as one list: the top episodes (a tap opens the episode's show), then the top shows, each with its place
 * in its genre.
 */
@Composable
private fun TopCharts(
    ui: DiscoverUiState,
    state: DiscoverState,
    onOpenPodcast: (Podcast) -> Unit,
    playback: PlaybackState,
    onPlay: ((Episode, Podcast) -> Unit)?,
    onPause: () -> Unit,
    /** The genre whose heading has scrolled fully under the list's top, or null above the first. */
    onScrolledGenre: (String?) -> Unit,
) {
    when {
        ui.topLoading && ui.topShows.isEmpty() -> Box(Modifier.fillMaxSize())
        ui.topError != null && ui.topShows.isEmpty() -> Centered {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(ui.topError, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = state::loadTopCharts) { Text("Retry") }
            }
        }
        else -> BoxWithConstraints(Modifier.fillMaxSize()) {
            // The shows as a grid of covers, as many across as fit at least ShowCellMin wide each.
            val inset = LocalContentInset.current
            val across = ((maxWidth - inset * 2 - ScreenGutter * 2 + ShowCellGap) / (ShowCellMin + ShowCellGap)).toInt().coerceAtLeast(3)
            val lines = remember(ui.topShows, ui.topEpisodes, across) { chartLines(ui.topShows, ui.topEpisodes, across) }
            // A grid cover's width: the top episodes' covers match it.
            val cell = (maxWidth - inset * 2 - ScreenGutter * 2 - ShowCellGap * (across - 1)) / across
            val listState = rememberLazyListState()
            // Above and under a heading's text, the rest of its line; and how far past halfway under the text goes
            // before the genre counts as reached.
            val density = LocalDensity.current
            val headingTop = with(density) { SectionHeadingTop.roundToPx() }
            val firstHeadingTop = with(density) { FirstSectionHeadingTop.roundToPx() }
            val headingBottom = with(density) { SectionHeadingBottom.roundToPx() }
            val pastHalf = with(density) { SectionHeadingPastHalf.roundToPx() }
            LaunchedEffect(listState, lines, headingTop, firstHeadingTop, headingBottom, pastHalf) {
                snapshotFlow {
                    // A genre is reached once its heading's text is a little past halfway under the list's top
                    // (offset 0, the divider's edge); before the first visible item, it's long past.
                    val info = listState.layoutInfo.visibleItemsInfo
                    val first = info.firstOrNull()?.index ?: 0
                    lines.withIndex().lastOrNull { (i, line) ->
                        line is ChartLine.Section && (i < first || info.any {
                            val textMiddle = ((if (i == 0) firstHeadingTop else headingTop) + it.size - headingBottom) / 2
                            it.index == i && it.offset + textMiddle + pastHalf <= 0
                        })
                    }?.let { (it.value as ChartLine.Section).title }
                }.collect(onScrolledGenre)
            }
            DisposableEffect(Unit) { onDispose { onScrolledGenre(null) } }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().verticalFadingEdge(fadeLength = 0.dp, bottomFadeLength = floatingBarBottomGap()),
                contentPadding = listContentPadding(top = ListDividerHeight),
            ) {
                itemsIndexed(lines, key = { _, line -> line.key }) { index, line ->
                    val before = lines.getOrNull(index - 1)
                    when (line) {
                        // Genres after the first are set off by a divider, halfway between the last row and the heading.
                        is ChartLine.Section -> SectionLabel(
                            line.title,
                            Modifier.fillMaxWidth()
                                .padding(top = if (before == null) 0.dp else SectionHeadingTop - 14.dp)
                                .topDivider(before != null)
                                .contentInset().padding(
                                    start = ScreenGutter, end = ScreenGutter, bottom = SectionHeadingBottom,
                                    top = if (before == null) FirstSectionHeadingTop else 14.dp,
                                ),
                        )
                        is ChartLine.Shows -> Row(
                            Modifier.fillMaxWidth().contentInset().padding(
                                start = ScreenGutter, end = ScreenGutter, bottom = ShowCellGap,
                                top = if (before is ChartLine.Featured) 8.dp else 0.dp,
                            ),
                            horizontalArrangement = Arrangement.spacedBy(ShowCellGap),
                        ) {
                            line.shows.forEach { show -> ShowCell(show, onOpenPodcast, Modifier.weight(1f)) }
                            // A short row keeps its covers the size of the rest.
                            repeat(across - line.shows.size) { Spacer(Modifier.weight(1f)) }
                        }
                        is ChartLine.Featured -> TopEpisode(
                            line.episode, ui, state, onOpenPodcast, playback, onPlay, onPause,
                            label = line.label,
                            cover = cell,
                            coverAtStart = line.order % 2 == 0,
                        )
                    }
                }
            }
        }
    }
}

/** One line of the charts: a genre's heading, a row of show covers, or a top episode on a row of its own. */
internal sealed interface ChartLine {
    val key: String

    data class Section(val title: String) : ChartLine {
        override val key get() = "g:$title"
    }

    data class Shows(val shows: List<ChartShow>) : ChartLine {
        override val key get() = "s:" + shows.first().podcast.feedUrl
    }

    /** [order]: its place among the episodes, which alternate their covers' sides. [label]: its place among the top episodes. */
    data class Featured(val episode: ChartEpisode, val order: Int, val label: String) : ChartLine {
        override val key get() = "e:${episode.showAppleId}:${episode.appleId}:${episode.title}"
    }
}

/**
 * The top [shows] and top [episodes] as one list, by genre: a heading per genre, the genres with top episodes first
 * (by their best episode's place among [episodes]), then the rest in the order they come in [shows]. Under each, up to
 * [MAX_GENRE_ROWS] rows: full rows of [across] covers by their place in the genre (a row that can't be filled is left
 * out), and episode rows. A show with a top episode leaves its row to stand as that episode, on its own line after the
 * row it would have been in. An episode with no room in its genre's rows isn't listed, nor is one whose show is in none
 * of the genres.
 */
internal fun chartLines(shows: List<ChartShow>, episodes: List<ChartEpisode>, across: Int): List<ChartLine> {
    val episodeOf = episodes.groupBy { it.showAppleId }.mapValues { (_, list) -> list.first() }
    val episodeRank = episodes.withIndex().associate { (i, e) -> e to i }
    val sections = mutableListOf<Pair<String, List<ChartLine>>>()
    val placed = mutableSetOf<ChartEpisode>()
    for ((genre, members) in shows.withIndex().groupBy({ it.value.genre ?: "More shows" }) { it }) {
        val ranked = members.sortedWith(compareBy({ it.value.genreRank ?: Int.MAX_VALUE }, { it.index })).map { it.value }
        // The genre's episodes, best first, each joining if the rows still have room for it and those before it.
        var chosen = emptySet<ChartEpisode>()
        var section = genreRows(ranked, chosen, across)
        val candidates = ranked.mapNotNull { show -> show.podcast.appleId?.let { episodeOf[it] } }
            .filter { it !in placed }.sortedBy { episodeRank.getValue(it) }
        for (episode in candidates) {
            val tried = genreRows(ranked, chosen + episode, across)
            if (tried.count { it is ChartLine.Featured } == chosen.size + 1) {
                chosen = chosen + episode
                section = tried
            }
        }
        placed += chosen
        // Its episode rows go to the best episodes first, wherever their shows sit.
        val best = section.filterIsInstance<ChartLine.Featured>().map { it.episode }
            .sortedBy { episodeRank.getValue(it) }.iterator()
        val lines = section.map { line ->
            if (line !is ChartLine.Featured) line
            else best.next().let { ChartLine.Featured(it, 0, "#${episodeRank.getValue(it) + 1} today") }
        }
        if (lines.isNotEmpty()) sections += genre to lines
    }
    val lines = mutableListOf<ChartLine>()
    var order = 0
    // Stable: genres without top episodes keep their order after the rest.
    val byEpisodes = sections.sortedBy { (_, section) ->
        section.filterIsInstance<ChartLine.Featured>().minOfOrNull { episodeRank.getValue(it.episode) } ?: Int.MAX_VALUE
    }
    for ((genre, section) in byEpisodes) {
        lines += ChartLine.Section(genre)
        // Numbered as they come out, for their covers to alternate sides.
        section.forEach { line -> lines += if (line is ChartLine.Featured) line.copy(order = order++) else line }
    }
    return lines
}

/**
 * A genre's rows, up to [MAX_GENRE_ROWS]: full rows of [across] covers, and the [featured] episodes (unlabelled), each
 * after the row its show would have been in. A featured episode without room is left out.
 */
private fun genreRows(ranked: List<ChartShow>, featured: Set<ChartEpisode>, across: Int): List<ChartLine> {
    val byShow = featured.associateBy { it.showAppleId }
    val rows = mutableListOf<ChartLine>()
    val row = mutableListOf<ChartShow>()
    val waiting = mutableListOf<ChartEpisode>()
    for (show in ranked) {
        if (rows.size == MAX_GENRE_ROWS) break
        val episode = show.podcast.appleId?.let { byShow[it] }
        if (episode != null) {
            waiting += episode
        } else {
            row += show
            if (row.size == across) {
                rows += ChartLine.Shows(row.toList())
                row.clear()
                for (e in waiting) {
                    if (rows.size == MAX_GENRE_ROWS) break
                    rows += ChartLine.Featured(e, 0, "")
                }
                waiting.clear()
            }
        }
    }
    return rows
}

/** How far down its line a genre's heading text starts: the first's, and the rest's, under their dividers. */
private val FirstSectionHeadingTop = 8.dp
private val SectionHeadingTop = 24.dp
private val SectionHeadingBottom = 6.dp
private val SectionHeadingPastHalf = 2.8.dp

/** At most this many rows under each genre, rows of covers and episodes alike. */
internal const val MAX_GENRE_ROWS = 3

/** A top episode as Home lists episodes, its show's cover on one side at the grid's size. */
@Composable
private fun TopEpisode(
    episode: ChartEpisode,
    ui: DiscoverUiState,
    state: DiscoverState,
    onOpenPodcast: (Podcast) -> Unit,
    playback: PlaybackState,
    onPlay: ((Episode, Podcast) -> Unit)?,
    onPause: () -> Unit,
    cover: Dp,
    coverAtStart: Boolean,
    label: String,
) {
    // The show as looked up (its own cover, its feed); until then, what the chart says.
    val show = ui.episodeShows[episode.showAppleId]
        ?: Podcast(feedUrl = "", title = episode.showTitle, artworkUrl = episode.artworkUrl, appleId = episode.showAppleId)
    // Until it's been found to play, it shows what the chart gives.
    val found = ui.playableEpisodes[episode.appleId]
    val finding = ui.findingEpisode == episode.appleId
    val isCurrent = found != null && playback.episode?.id == found.second.id
    val play = onPlay?.takeIf { episode.appleId != 0L }
    // Found as its row comes into view, so it shows its length and plays at once.
    if (play != null) LaunchedEffect(episode.appleId) { state.lookUpTopEpisode(episode) }
    // Until then the pill keeps room for a typical length: the average of those found so far.
    val pending = play != null && found == null && episode.appleId !in ui.missingEpisodes
    val typical = if (!pending) null else remember(ui.playableEpisodes) {
        val known = ui.playableEpisodes.values.mapNotNull { it.second.durationSeconds?.takeIf { s -> s > 0 } }
        formatMinutes((if (known.isEmpty()) TypicalEpisodeSeconds else known.average().toLong()) * 1000)
    }
    EpisodeItem(
        row = EpisodeRow(found?.second ?: episode.placeholder(), progress = null),
        // While it's being found, it shows as starting, like any episode just tapped.
        isCurrent = isCurrent || finding,
        isPlaying = (isCurrent && playback.isPlaying) || finding,
        onPlay = {
            if (play == null) onOpenPodcast(show)
            else state.playTopEpisode(episode, play) { onOpenPodcast(show) }
        },
        onPause = { if (isCurrent) onPause() },
        show = found?.first ?: show,
        showArtworkUrl = show.artworkUrl,
        onOpenShow = { onOpenPodcast(found?.first ?: show) },
        sideCover = cover,
        sideCoverAtStart = coverAtStart,
        // No divider and no ripple across the row: it plays from its pill and opens its show from its cover.
        rowClickable = false,
        showDescription = false,
        showLabel = label,
        showAge = false,
        pillPlaceholder = typical,
    )
}

/** A typical episode's length, for a top episode's pill before any have been found. */
private const val TypicalEpisodeSeconds = 45L * 60

/** A chart show in the grid: its cover alone; the title is for screen readers. */
@Composable
private fun ShowCell(show: ChartShow, onOpenPodcast: (Podcast) -> Unit, modifier: Modifier = Modifier) {
    val p = show.podcast
    val anchor = rememberCoverAnchor(p.feedUrl, corner = 9.dp)
    Column(
        modifier.clip(RoundedCornerShape(10.dp))
            .clickable {
                anchor.mark()
                onOpenPodcast(p)
            }
            .semantics(mergeDescendants = true) { contentDescription = p.title },
    ) {
        Artwork(
            p.artworkUrl, p.title, Modifier.fillMaxWidth().aspectRatio(1f).then(anchor.modifier),
            corner = 9.dp, onLoaded = anchor.onLoaded, liveArchive = p.isLiveArchive, playlist = p.playlistId,
        )
    }
}

private val ShowCellMin = 100.dp
private val ShowCellGap = 8.dp

/** A top episode as the chart gives it (no audio yet): enough for its row until it's found to play. */
private fun ChartEpisode.placeholder() = Episode(
    guid = "apple:$appleId", podcastFeedUrl = "", title = title, audioUrl = "", artworkUrl = artworkUrl,
)

@Composable
internal fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(ScreenGutter), contentAlignment = Alignment.Center) { content() }
}
