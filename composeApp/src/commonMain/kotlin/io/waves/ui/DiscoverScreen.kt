package io.waves.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.unit.dp
import io.waves.model.Podcast

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(state: DiscoverState, onOpenPodcast: (Podcast) -> Unit) {
    val ui by state.state.collectAsState()
    Scaffold(
        topBar = { WavesTopBar("Discover", icon = Tab.Discover.icon) },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // As tall as Home's shelf, so the divider under it stays put when swiping between the tabs.
            Box(Modifier.fillMaxWidth().height(ShelfBandHeight), contentAlignment = Alignment.Center) {
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
                        if (ui.isSearching) SearchResults(ui, onOpenPodcast) else TopCharts(ui, state, onOpenPodcast)
                    }
                }
                // Takes on the waves' colour as a pull opens the gap under it.
                ListDivider(tint = { pull.value })
            }
        }
    }
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

@Composable
private fun TopCharts(ui: DiscoverUiState, state: DiscoverState, onOpenPodcast: (Podcast) -> Unit) {
    when {
        ui.topLoading && ui.topPodcasts.isEmpty() -> Box(Modifier.fillMaxSize())
        ui.topError != null && ui.topPodcasts.isEmpty() -> Centered {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(ui.topError, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = state::loadTopCharts) { Text("Retry") }
            }
        }
        else -> PodcastGrid(podcasts = ui.topPodcasts, onClick = onOpenPodcast, modifier = Modifier.contentInset())
    }
}

/** Adaptive grid of artwork tiles, three or more across on a phone. */
@Composable
fun PodcastGrid(
    podcasts: List<Podcast>,
    onClick: (Podcast) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(100.dp),
        contentPadding = listContentPadding(horizontal = ScreenGutter, top = ListDividerHeight + 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.fillMaxSize().verticalFadingEdge(fadeLength = 0.dp, bottomFadeLength = floatingBarBottomGap()),
    ) {
        items(podcasts) { podcast ->
            val anchor = rememberCoverAnchor(podcast.feedUrl, corner = 9.dp)
            Column(
                Modifier.clickable {
                    anchor.mark()
                    onClick(podcast)
                },
            ) {
                Artwork(
                    podcast.artworkUrl, podcast.title, Modifier.fillMaxWidth().aspectRatio(1f).then(anchor.modifier),
                    corner = 9.dp, onLoaded = anchor.onLoaded,
                )
                Text(
                    podcast.title, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
                podcast.author?.let { MetaText(it) }
            }
        }
    }
}

@Composable
internal fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(ScreenGutter), contentAlignment = Alignment.Center) { content() }
}
