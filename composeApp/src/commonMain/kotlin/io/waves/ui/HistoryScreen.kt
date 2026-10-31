package io.waves.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.waves.currentTimeMillis
import io.waves.platformName
import io.waves.download.DownloadStatus
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
        Column(Modifier.padding(padding).fillMaxSize()) {
            val listState = rememberLazyListState()
            val today = HistoryState.localDate(currentTimeMillis(), TimeZone.currentSystemDefault())
            // The day the list shows at its top, named just above the divider instead of on the list. A day's own label
            // takes over only once it has gone all the way under the divider.
            val line = with(LocalDensity.current) { ListDividerHeight.toPx() }
            val topDay by remember(ui.days, line) {
                derivedStateOf {
                    val below = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.offset + it.size > line }?.index
                        ?: listState.firstVisibleItemIndex
                    dayAtTop(ui, if (isDayLabel(ui, below)) below - 1 else below)
                }
            }
            // Pinned like Home's shelf, its divider on the same line.
            Column(Modifier.height(PinnedHeaderHeight)) {
                HistorySummary(ui.listenedThisWeekMs, downloads, Modifier.weight(1f))
                SectionLabel(
                    topDay?.let { dayLabel(it, today) }.orEmpty(),
                    Modifier.contentInset().padding(start = ScreenGutter, end = ScreenGutter, bottom = 4.dp),
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val pull = rememberPullProgress()
                // Rows scrolling up end at the divider's line; on the list, so a pull's waves come down through it.
                val list = Modifier.clip(BelowListDivider)
                if (sync != null && syncUi != null && syncUi.auth is AuthState.SignedIn && syncUi.settings.syncEnabled) {
                    WavesPullToRefreshBox(
                        // Only syncs asked for here or in Settings spin; background ones run often while playing.
                        isRefreshing = syncUi.busy,
                        onRefresh = sync::syncNow,
                        modifier = Modifier.fillMaxSize(),
                        gapTop = ListDividerHeight,
                        progress = pull,
                    ) { HistoryList(ui, deviceId, onPlay, downloads, listState, today, list) }
                } else {
                    HistoryList(ui, deviceId, onPlay, downloads, listState, today, list)
                }
                ListDivider(tint = { pull.value })
            }
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
    listState: LazyListState,
    today: LocalDate,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier.fillMaxSize()
            .verticalFadingEdge(fadeLength = 0.dp, bottomFadeLength = floatingBarBottomGap()),
        // Rows start below the divider.
        contentPadding = listContentPadding(top = ListDividerHeight),
        state = listState,
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
            // The first day is named above the divider; the others where they start, until they reach the top.
            if (day != ui.days.first()) {
                item(key = day.date.toString()) {
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
                        // The very first row sits right under the pinned divider: its ripple reaches up to the line.
                        .zigzagClickable(
                            top = index > 0, bottom = index < day.entries.lastIndex,
                            above = index == 0 && day == ui.days.first(),
                        ) { onPlay(entry) }
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

/** Whether the list's item at [index] is a day's label (every day's but the first's), as [HistoryList] lays them out. */
private fun isDayLabel(ui: HistoryUiState, index: Int): Boolean {
    var start = 0
    ui.days.forEachIndexed { n, day ->
        if (n > 0 && index == start) return true
        start += day.entries.size + if (n == 0) 0 else 1
    }
    return false
}

/** The day of the list's item at [index], in the order [HistoryList] lays them out. */
private fun dayAtTop(ui: HistoryUiState, index: Int): LocalDate? {
    var i = index
    ui.days.forEachIndexed { n, day ->
        val size = day.entries.size + if (n == 0) 0 else 1
        if (i < size) return day.date
        i -= size
    }
    return ui.days.lastOrNull()?.date
}

/** This week's listening time (every device) and how many episodes are downloaded here, side by side. */
@Composable
private fun HistorySummary(listenedMs: Long, downloads: DownloadsState?, modifier: Modifier = Modifier) {
    val downloaded = downloads?.let { d ->
        d.state.collectAsState().value.items.count { it.status == DownloadStatus.Done }
    }
    Row(
        modifier.fillMaxWidth().contentInset().padding(horizontal = ScreenGutter),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SummaryTile(formatListened(listenedMs), "listened this week", Modifier.weight(1f))
        if (downloaded != null) SummaryTile("$downloaded ep", "downloaded", Modifier.weight(1f))
    }
}

@Composable
private fun SummaryTile(value: String, label: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** "0 min", "45 min", "3 h 20 min". */
internal fun formatListened(ms: Long): String {
    val minutes = ms / 60_000
    val hours = minutes / 60
    return if (hours == 0L) "$minutes min" else "$hours h ${minutes % 60} min"
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
