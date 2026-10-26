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
import androidx.compose.material3.CircularProgressIndicator
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
        Column(Modifier.padding(padding).adaptiveContentWidth().fillMaxSize()) {
            OutlinedTextField(
                value = ui.query,
                onValueChange = state::onQueryChange,
                modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenGutter, vertical = 4.dp),
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
            if (ui.isSearching) SearchResults(ui, onOpenPodcast) else TopCharts(ui, state, onOpenPodcast)
        }
    }
}

@Composable
private fun SearchResults(ui: DiscoverUiState, onOpenPodcast: (Podcast) -> Unit) {
    when {
        ui.searching && ui.results.isEmpty() -> Centered { CircularProgressIndicator() }
        ui.searchError != null -> Centered { Text(ui.searchError, color = MaterialTheme.colorScheme.error) }
        ui.results.isEmpty() -> Centered { Text("No podcasts found") }
        else -> LazyVerticalGrid(
            columns = GridCells.Adaptive(360.dp),
            contentPadding = listContentPadding(),
            modifier = Modifier.fillMaxSize().verticalFadingEdge(),
        ) {
            itemsIndexed(ui.results) { index, podcast ->
                Row(
                    Modifier.fillMaxWidth().topDivider(index > 0).clickable { onOpenPodcast(podcast) }
                        .padding(horizontal = ScreenGutter, vertical = ListRowVerticalPadding),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ListRowGap),
                ) {
                    Artwork(podcast.artworkUrl, podcast.title, size = 48.dp)
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
        ui.topLoading && ui.topPodcasts.isEmpty() -> Centered { CircularProgressIndicator() }
        ui.topError != null && ui.topPodcasts.isEmpty() -> Centered {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(ui.topError, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = state::loadTopCharts) { Text("Retry") }
            }
        }
        else -> PodcastGrid(podcasts = ui.topPodcasts, onClick = onOpenPodcast)
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
        contentPadding = listContentPadding(horizontal = ScreenGutter),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.fillMaxSize().verticalFadingEdge(),
    ) {
        items(podcasts) { podcast ->
            Column(Modifier.clickable { onClick(podcast) }) {
                Artwork(podcast.artworkUrl, podcast.title, Modifier.fillMaxWidth().aspectRatio(1f), corner = 9.dp)
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
