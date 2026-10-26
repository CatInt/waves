package io.waves.ui

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
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.waves.model.Episode

/**
 * Per-episode download control, drawn as one family of circles in the accent colour: outlined arrow (download),
 * progress ring around a stop square (tap to cancel), filled arrow (downloaded; tap or long-press for "Remove
 * download"), faint bolt (kept automatically while listening; same menu) and an error ring (tap to retry).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DownloadButton(episode: Episode, downloads: DownloadsState, modifier: Modifier = Modifier, compact: Boolean = false) {
    val size = if (compact) 22.dp else 26.dp
    val control by remember(episode.id) { downloads.control(episode.id) }
        .collectAsState(initial = DownloadControl.NotDownloaded)
    var menu by remember(episode.id) { mutableStateOf(false) }
    when (val c = control) {
        DownloadControl.NotDownloaded -> IconButton(onClick = { downloads.download(episode) }, modifier) {
            DownloadStateIcon(c, size, Modifier.semantics { contentDescription = "Download" })
        }
        is DownloadControl.InProgress -> IconButton(onClick = { downloads.cancel(episode.id) }, modifier) {
            DownloadStateIcon(c, size, Modifier.semantics { contentDescription = "Cancel download" })
        }
        DownloadControl.Downloaded -> StoredEpisodeButton(
            modifier = modifier,
            menu = menu,
            onOpen = { menu = true },
            onClose = { menu = false },
            description = "Downloaded",
            action = "Remove download",
            onRemove = { downloads.remove(episode.id) },
        ) {
            DownloadStateIcon(c, size)
        }
        DownloadControl.Cached -> StoredEpisodeButton(
            modifier = modifier,
            menu = menu,
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
internal fun DownloadStateIcon(control: DownloadControl, size: Dp, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    // Same ink as the play pill beside it: hairline outline, onSurface glyphs.
    val ink = colors.onSurface
    when (control) {
        DownloadControl.NotDownloaded -> DownloadGlyph(size, modifier, ring = colors.outlineVariant, arrow = ink)
        is DownloadControl.InProgress -> Box(modifier.size(size), contentAlignment = Alignment.Center) {
            val track = colors.outlineVariant
            val p = control.progress
            if (p == null) {
                CircularProgressIndicator(Modifier.fillMaxSize(), color = ink, strokeWidth = 2.dp, trackColor = track)
            } else {
                CircularProgressIndicator(
                    progress = { p }, modifier = Modifier.fillMaxSize(), color = ink, strokeWidth = 2.dp,
                    trackColor = track,
                )
            }
            Box(Modifier.size(size * 0.3f).clip(RoundedCornerShape(1.5.dp)).background(ink))
        }
        DownloadControl.Downloaded -> DownloadGlyph(size, modifier, fill = ink, arrow = colors.surface)
        DownloadControl.Cached -> Box(
            modifier.size(size).background(ink.copy(alpha = 0.1f), CircleShape),
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

/** A circle ([ring] outline and/or [fill]) with a down arrow. */
@Composable
private fun DownloadGlyph(size: Dp, modifier: Modifier, arrow: Color, ring: Color? = null, fill: Color? = null) {
    Box(
        modifier.size(size)
            .then(if (fill != null) Modifier.background(fill, CircleShape) else Modifier)
            .then(if (ring != null) Modifier.border(1.dp, ring, CircleShape) else Modifier),
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
                .combinedClickable(role = Role.Button, onClick = onOpen, onLongClick = onOpen)
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
