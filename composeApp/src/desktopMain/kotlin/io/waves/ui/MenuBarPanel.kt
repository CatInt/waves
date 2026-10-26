package io.waves.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.waves.model.Episode
import io.waves.model.Podcast

/**
 * The menu bar popover, a pocket version of the app: the Now Playing title chip and transport over the cover's
 * colours, then Home's shelf of shows and its "Up next" list. The chip, covers and show names open the app there.
 */
@Composable
internal fun MenuBarPanel(app: AppState, onOpenApp: () -> Unit, modifier: Modifier = Modifier) {
    val playback by app.playback.state.collectAsState()
    val podcast by app.playback.podcast.collectAsState()
    val skip by app.playback.skipIntervals.collectAsState()
    val home by app.home.state.collectAsState()
    val episode = playback.episode
    val playerColors = rememberPlayerColors(rememberArtworkSeed(podcast?.artworkUrl))
    val shape = RoundedCornerShape(14.dp)
    fun open(screen: Screen) {
        app.navigator.push(screen)
        onOpenApp()
    }
    fun openShow(show: Podcast) = open(Screen.PodcastDetail(show, autoFetch = false))

    CompositionLocalProvider(LocalPlayerColors provides playerColors) {
        Column(
            modifier.fillMaxSize().clip(shape)
                .background(MaterialTheme.colorScheme.background)
                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, shape),
        ) {
            MaterialTheme(colorScheme = playerColors.applyTo(MaterialTheme.colorScheme)) {
                val colors = MaterialTheme.colorScheme
                Column(
                    Modifier.fillMaxWidth()
                        .background(Brush.verticalGradient(0f to playerColors.backdrop, 1f to colors.background)),
                ) {
                    CompositionLocalProvider(LocalContentColor provides colors.onBackground) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 12.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            NowPlayingChip(
                                episode, podcast,
                                onClick = { open(Screen.NowPlaying) }.takeIf { episode != null },
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = onOpenApp) { Icon(Icons.AutoMirrored.Filled.OpenInNew, "Open Waves") }
                        }
                        if (episode != null) {
                            ControlPanel(
                                isPlaying = playback.isPlaying,
                                ring = playRing(playback.isBuffering, episode, app.downloads),
                                positionMs = playback.positionMs,
                                durationMs = playback.durationMs.takeIf { it > 0 } ?: (episode.durationSeconds ?: 0) * 1000,
                                speed = playback.speed,
                                error = playback.error,
                                skipBackSeconds = skip.backSeconds,
                                skipForwardSeconds = skip.forwardSeconds,
                                controller = app.playback,
                                download = app.downloads?.let { d -> { DownloadButton(episode, d) } },
                            )
                        } else {
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
            val pinned = home.shelf.isNotEmpty()
            if (pinned) {
                LazyRow(
                    Modifier.padding(top = 4.dp, bottom = 8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(home.shelf, key = { it.feedUrl }) { show ->
                        Box(Modifier.clip(RoundedCornerShape(8.dp)).clickable { openShow(show) }) {
                            Artwork(show.artworkUrl, show.title, size = 52.dp, corner = 7.dp)
                        }
                    }
                }
            }
            if (home.offline) SectionTitle("Offline · downloaded episodes", Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(Modifier.fillMaxSize().verticalFadingEdge(fadeLength = if (pinned) 4.dp else 8.dp)) {
                    if (home.upNext.isEmpty()) {
                        item {
                            Text(
                                when {
                                    home.offline && home.loaded && !home.refreshing -> "No downloaded or fully cached episodes."
                                    !home.loaded || home.refreshing -> "Loading episodes…"
                                    home.subscriptions.isEmpty() -> "Subscribe to shows in Waves to fill this list."
                                    else -> "You're all caught up."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            )
                        }
                    }
                    itemsIndexed(home.upNext, key = { _, it -> it.row.episode.id.toString() }) { index, item ->
                        val isCurrent = episode?.id == item.row.episode.id
                        EpisodeItem(
                            row = item.row,
                            isCurrent = isCurrent,
                            isPlaying = isCurrent && playback.isPlaying,
                            onPlay = { app.playback.play(item.row.episode, item.podcast) },
                            onPause = app.playback::pause,
                            show = item.podcast.takeIf { it.title.isNotBlank() },
                            onOpenShow = { openShow(item.podcast) },
                            badge = item.reason.label,
                            downloadControl = app.downloads?.let { d -> { m -> DownloadButton(item.row.episode, d, m, compact = true) } },
                            showDescription = false,
                            divider = pinned || index > 0,
                            dividerBelow = index < home.upNext.lastIndex,
                        )
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
                if (pinned) ListDivider()
            }
        }
    }
}

/** Now Playing's title chip: cover, episode and show in one rounded pill; tapping it opens Now Playing. */
@Composable
private fun NowPlayingChip(episode: Episode?, podcast: Podcast?, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    Box(modifier) {
        Row(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(LocalContentColor.current.copy(alpha = 0.08f))
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .heightIn(min = TitleChipArtSize + 12.dp)
                .padding(start = 6.dp, top = 6.dp, bottom = 6.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (episode != null) {
                Artwork(
                    episode.artworkUrl ?: podcast?.artworkUrl, podcast?.title ?: episode.title,
                    size = TitleChipArtSize, corner = TitleChipArtCorner,
                )
                Spacer(Modifier.width(10.dp))
            } else {
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.weight(1f, fill = false)) {
                Text(
                    episode?.title ?: "Nothing playing",
                    style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val subtitle = if (episode != null) podcast?.title?.takeIf { it.isNotBlank() } else "Pick an episode below"
                subtitle?.let {
                    Text(
                        it, style = MaterialTheme.typography.bodySmall,
                        color = if (episode != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
