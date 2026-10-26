package io.waves.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import io.waves.model.Episode
import io.waves.model.Podcast
import androidx.compose.ui.unit.Dp
import io.waves.player.AudioPlayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Default skip intervals; the effective ones come from [PlaybackController.skipIntervals]. */
const val SKIP_BACK_MS = 15_000L
const val SKIP_FORWARD_MS = 30_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    controller: PlaybackController,
    onCollapse: () -> Unit,
    downloads: DownloadsState? = null,
    /** Draws the chip's cover at the given size; the player sheet swaps in a placeholder and morphs its own copy. */
    artwork: (@Composable (size: Dp) -> Unit)? = null,
    /** Opens the show's page from the title chip; null makes the chip plain. */
    onOpenPodcast: ((Podcast) -> Unit)? = null,
) {
    val playback by controller.state.collectAsState()
    val podcast by controller.podcast.collectAsState()
    val skip by controller.skipIntervals.collectAsState()
    val episode = playback.episode
    Scaffold(
        topBar = {
            WavesTopBar(
                title = "Now Playing",
                navigationIcon = if (episode == null) {
                    { CollapseButton(onCollapse) }
                } else {
                    null
                },
                containerColor = Color.Transparent,
                titleContent = episode?.let {
                    {
                        TitleChip(
                            episode = it,
                            podcast = podcast,
                            artwork = artwork,
                            onCollapse = onCollapse,
                            onClick = podcast?.let { p -> onOpenPodcast?.let { open -> { open(p) } } },
                        )
                    }
                },
            )
        },
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        if (episode == null) {
            Box(Modifier.padding(padding)) { Centered { Text("Nothing playing") } }
            return@Scaffold
        }
        val durationMs = playback.durationMs.takeIf { it > 0 } ?: (episode.durationSeconds ?: 0) * 1000
        Column(Modifier.padding(padding).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            // Reading material on top; everything you tap lives in the panel at the bottom, within thumb reach.
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalFadingEdge().verticalScroll(rememberScrollState())
                    .padding(horizontal = ScreenGutter).padding(top = 16.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
                    Text(episode.title, style = MaterialTheme.typography.headlineSmall)
                    val meta = listOfNotNull(
                        episode.publishedAtEpochMs?.let(::formatDate),
                        durationMs.takeIf { it > 0 }?.let(::formatMinutes),
                    ).joinToString(" · ")
                    if (meta.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        MetaText(meta)
                    }
                    episode.description?.let(::plainText)?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(16.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            ControlPanel(
                isPlaying = playback.isPlaying,
                ring = playRing(playback.isBuffering, episode, downloads),
                positionMs = playback.positionMs,
                durationMs = durationMs,
                speed = playback.speed,
                error = playback.error,
                skipBackSeconds = skip.backSeconds,
                skipForwardSeconds = skip.forwardSeconds,
                controller = controller,
                download = downloads?.let { d -> { DownloadButton(episode, d) } },
            )
        }
    }
}

internal val TitleChipArtSize = 40.dp
internal val TitleChipArtCorner = 10.dp

@Composable
private fun CollapseButton(onCollapse: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onCollapse, modifier = modifier) { Icon(Icons.Default.KeyboardArrowDown, "Collapse player") }
}

/** Collapse arrow, cover, episode title and show in one pill at the top left of Now Playing. */
@Composable
private fun TitleChip(
    episode: Episode,
    podcast: Podcast?,
    artwork: (@Composable (size: Dp) -> Unit)?,
    onCollapse: () -> Unit,
    onClick: (() -> Unit)?,
) {
    Row(
        Modifier
            .padding(end = 12.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(LocalContentColor.current.copy(alpha = 0.08f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 2.dp, top = 6.dp, bottom = 6.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CollapseButton(onCollapse, Modifier.size(TitleChipArtSize))
        Spacer(Modifier.width(2.dp))
        if (artwork != null) {
            artwork(TitleChipArtSize)
        } else {
            Artwork(
                episode.artworkUrl ?: podcast?.artworkUrl, podcast?.title ?: episode.title,
                size = TitleChipArtSize, corner = TitleChipArtCorner,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f, fill = false)) {
            Text(episode.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            podcast?.let {
                Text(
                    it.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

val SpeedPresets = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)
    .filter { it in AudioPlayer.MIN_SPEED..AudioPlayer.MAX_SPEED }

/** "1x", "1.25x", "0.5x". */
fun formatSpeed(speed: Float): String {
    val hundredths = kotlin.math.round(speed * 100).toInt()
    val whole = hundredths / 100
    val frac = (hundredths % 100).toString().padStart(2, '0').trimEnd('0')
    return if (frac.isEmpty()) "${whole}x" else "$whole.${frac}x"
}
