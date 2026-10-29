package io.waves.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.waves.currentTimeMillis
import io.waves.platformName
import io.waves.model.HistoryEntry
import io.waves.sync.AuthState
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    state: HistoryState,
    deviceId: String,
    onPlay: (HistoryEntry) -> Unit,
    /** Null hides the download state. */
    downloads: DownloadsState? = null,
    /** Pulling the list down syncs through it while signed in with sync on; null leaves out pull to sync. */
    sync: SettingsState? = null,
) {
    val ui by state.state.collectAsState()
    val syncUi = sync?.let { it.state.collectAsState().value }
    Scaffold(
        topBar = { WavesTopBar("History", icon = Tab.History.icon) },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        if (sync != null && syncUi != null && syncUi.auth is AuthState.SignedIn && syncUi.settings.syncEnabled) {
            WavesPullToRefreshBox(
                // Only syncs asked for here or in Settings spin; background ones run often while playing.
                isRefreshing = syncUi.busy,
                onRefresh = sync::syncNow,
                modifier = Modifier.padding(padding).fillMaxSize(),
            ) { HistoryList(ui, deviceId, onPlay, downloads) }
        } else {
            HistoryList(ui, deviceId, onPlay, downloads, Modifier.padding(padding))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryList(
    ui: HistoryUiState,
    deviceId: String,
    onPlay: (HistoryEntry) -> Unit,
    downloads: DownloadsState?,
    modifier: Modifier = Modifier,
) {
    val today = HistoryState.localDate(currentTimeMillis(), TimeZone.currentSystemDefault())
    LazyColumn(
        modifier.fillMaxSize()
            .verticalFadingEdge(bottomFadeLength = floatingBarBottomGap()),
        contentPadding = listContentPadding(),
    ) {
        // A list even when empty, so it can still be pulled to sync.
        if (ui.loaded && ui.days.isEmpty()) {
            item(key = "empty") {
                Box(Modifier.fillParentMaxSize().padding(ScreenGutter), contentAlignment = Alignment.Center) {
                    Text("Nothing played yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        ui.days.forEach { day ->
            stickyHeader(key = day.date.toString()) {
                Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
                    SectionLabel(
                        dayLabel(day.date, today),
                        Modifier.contentInset().padding(start = ScreenGutter, end = ScreenGutter, top = 10.dp, bottom = 4.dp),
                    )
                }
            }
            itemsIndexed(day.entries) { index, entry ->
                val thisDevice = entry.deviceId == deviceId
                Row(
                    Modifier.fillMaxWidth().topDivider(index > 0)
                        .zigzagClickable(top = index > 0, bottom = index < day.entries.lastIndex) { onPlay(entry) }
                        .contentInset()
                        .padding(horizontal = ScreenGutter, vertical = ListRowVerticalPadding),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ListRowGap),
                ) {
                    Box {
                        Icon(
                            if (thisDevice) ThisDeviceIcon else Icons.Default.Devices,
                            contentDescription = if (thisDevice) "This device" else "Other device",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                        if (downloads != null) {
                            HistoryDownloadState(entry, downloads, Modifier.align(Alignment.BottomEnd).offset(5.dp, 5.dp))
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            entry.episodeTitle, style = listTitleStyle(),
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        MetaText(
                            "${entry.podcastTitle} · ${formatTime(entry.playedAtEpochMs)} · " +
                                if (thisDevice) "This device" else entry.deviceId,
                        )
                    }
                }
            }
        }
    }
}

/** History rows played here: a phone on Android, a computer on macOS. */
internal val ThisDeviceIcon = if (platformName.startsWith("Android")) Icons.Default.PhoneAndroid else Icons.Default.Computer

/**
 * Badge on the device icon: the episode's download state when it has a local copy (or one is on the way);
 * nothing otherwise. The ring in the background colour keeps it apart from the icon underneath.
 */
@Composable
private fun HistoryDownloadState(entry: HistoryEntry, downloads: DownloadsState, modifier: Modifier = Modifier) {
    val control by remember(entry.episodeId) { downloads.control(entry.episodeId) }
        .collectAsState(initial = DownloadControl.NotDownloaded)
    val description = when (control) {
        DownloadControl.NotDownloaded -> return
        is DownloadControl.InProgress -> "Downloading"
        DownloadControl.Downloaded -> "Downloaded"
        DownloadControl.Cached -> "Cached"
        is DownloadControl.Failed -> "Download failed"
    }
    DownloadStateIcon(
        control, 12.dp,
        modifier.background(MaterialTheme.colorScheme.surface, CircleShape).padding(1.5.dp)
            .semantics { contentDescription = description },
    )
}

private val monthNamesLong = listOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)

internal fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "Today"
    today.minus(DatePeriod(days = 1)) -> "Yesterday"
    else -> buildString {
        append(date.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() })
        append(", ").append(monthNamesLong[date.monthNumber - 1]).append(' ').append(date.dayOfMonth)
        if (date.year != today.year) append(", ").append(date.year)
    }
}
