package io.waves.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AutoDelete
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.waves.BuildInfo
import io.waves.data.UserSettings
import io.waves.data.deviceRegion
import io.waves.platformName
import io.waves.sync.AuthState
import io.waves.sync.SyncStatus
import io.waves.update.RELEASES_REPO
import kotlin.math.abs

@Composable
fun SettingsScreen(state: SettingsState, deviceId: String, downloads: DownloadsState? = null, updates: UpdateState? = null) {
    val ui by state.state.collectAsState()
    Scaffold(
        topBar = { WavesTopBar("Settings", icon = Tab.Settings.icon) },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Column(
            Modifier.padding(padding).adaptiveContentWidth().fillMaxSize().verticalFadingEdge()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = CardInset)
                .padding(top = 8.dp, bottom = LocalBottomClearance.current),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SyncSettings(state, ui)
            PlaybackSettings(state, ui.settings)
            DiscoverSettings(state, ui.settings)
            if (downloads != null) DownloadSettings(downloads)
            AboutSettings(deviceId, updates)
        }
    }
}

@Composable
private fun SyncSettings(state: SettingsState, ui: SettingsUiState) {
    val auth = ui.auth
    SettingsGroup(
        "Sync",
        footer = "Subscriptions, progress and history merge across devices through your Google Drive app folder.",
    ) {
        when (auth) {
            AuthState.SignedOut -> SettingsRow(
                icon = Icons.Outlined.CloudSync,
                title = "Sign in with Google",
                onClick = state::signIn,
                enabled = !ui.busy,
                trailing = { if (ui.busy) SmallSpinner() },
            )
            is AuthState.SignedIn -> {
                SettingsRow(
                    icon = Icons.Default.AccountCircle,
                    title = auth.email ?: "Google account",
                    trailing = { TextButton(onClick = state::signOut, enabled = !ui.busy) { Text("Sign out") } },
                )
                RowDivider()
                ToggleRow(
                    icon = Icons.Default.Sync,
                    title = "Sync with Google Drive",
                    subtitle = if (ui.settings.syncEnabled) null else "This device neither uploads nor downloads changes.",
                    checked = ui.settings.syncEnabled,
                    onChange = state::setSyncEnabled,
                )
                RowDivider()
                SettingsRow(
                    icon = null,
                    title = "Sync now",
                    subtitle = syncStatusText(ui),
                    subtitleColor = if (ui.sync is SyncStatus.Failed) MaterialTheme.colorScheme.error else Color.Unspecified,
                    onClick = state::syncNow,
                    enabled = ui.canSyncNow,
                    trailing = { if (ui.busy || ui.sync == SyncStatus.Syncing) SmallSpinner() },
                )
            }
        }
        ui.error?.let {
            RowDivider()
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = RowPadding, vertical = 12.dp),
            )
        }
    }
}

private fun syncStatusText(ui: SettingsUiState): String = when {
    ui.offline -> "Offline. Sync resumes when the connection is back."
    else -> when (val s = ui.sync) {
        SyncStatus.Idle -> "Not synced yet"
        SyncStatus.Syncing -> "Syncing…"
        is SyncStatus.Done -> "Last synced ${formatDateTime(s.atEpochMs)}"
        is SyncStatus.Failed -> "Last sync failed: ${s.message}"
    }
}

@Composable
private fun PlaybackSettings(state: SettingsState, settings: UserSettings) {
    SettingsGroup("Playback") {
        ChoiceRow(
            icon = Icons.Default.Speed,
            title = "Default speed",
            options = SpeedPresets,
            selected = SpeedPresets.firstOrNull { abs(it - settings.defaultSpeed) < 0.01f },
            selectedLabel = formatSpeed(settings.defaultSpeed),
            label = ::formatSpeed,
            onSelect = state::setDefaultSpeed,
        )
        RowDivider()
        ChoiceRow(
            icon = Icons.Default.FastRewind,
            title = "Skip back",
            options = SkipBackOptions,
            selected = settings.skipBackSeconds,
            selectedLabel = "${settings.skipBackSeconds} s",
            label = { "$it seconds" },
            onSelect = state::setSkipBackSeconds,
        )
        RowDivider()
        ChoiceRow(
            icon = Icons.Default.FastForward,
            title = "Skip forward",
            options = SkipForwardOptions,
            selected = settings.skipForwardSeconds,
            selectedLabel = "${settings.skipForwardSeconds} s",
            label = { "$it seconds" },
            onSelect = state::setSkipForwardSeconds,
        )
    }
}

@Composable
private fun DiscoverSettings(state: SettingsState, settings: UserSettings) {
    SettingsGroup("Discover") {
        val deviceDefault = "Device default (${deviceRegion().uppercase()})"
        ChoiceRow(
            icon = Icons.Default.Public,
            title = "Podcast store",
            subtitle = settings.country?.let(::countryLabel) ?: deviceDefault,
            options = listOf<String?>(null) + CountryOptions.map { it.first },
            selected = settings.country,
            selectedLabel = null,
            label = { it?.let(::countryLabel) ?: deviceDefault },
            onSelect = state::setCountry,
        )
    }
}

@Composable
private fun DownloadSettings(downloads: DownloadsState) {
    val autoDelete by downloads.autoDeleteAfterFinishing.collectAsState()
    SettingsGroup("Downloads") {
        ToggleRow(
            icon = Icons.Default.AutoDelete,
            title = "Delete downloads after playing",
            subtitle = null,
            checked = autoDelete,
            onChange = downloads::setAutoDeleteAfterFinishing,
        )
    }
}

@Composable
private fun AboutSettings(deviceId: String, updates: UpdateState?) {
    val uriHandler = LocalUriHandler.current
    SettingsGroup("About") {
        if (updates != null) {
            UpdateRow(updates)
        } else {
            SettingsRow(icon = Icons.Default.SystemUpdate, title = versionTitle(BuildInfo.VERSION))
        }
        RowDivider()
        SettingsRow(
            icon = Icons.Default.Code,
            title = "Source code",
            subtitle = "Free software under the GNU GPL v3",
            onClick = { uriHandler.openUri("https://github.com/$RELEASES_REPO") },
            trailing = { TrailingIcon(Icons.AutoMirrored.Filled.OpenInNew) },
        )
        RowDivider()
        SettingsRow(icon = ThisDeviceIcon, title = platformName, subtitle = "Device ID $deviceId")
    }
}

private fun versionTitle(version: String) = "Version $version" + if (BuildInfo.IS_RELEASE) "" else " (development)"

@Composable
private fun UpdateRow(updates: UpdateState) {
    val status by updates.status.collectAsState()
    val s = status
    SettingsRow(
        icon = Icons.Default.SystemUpdate,
        title = versionTitle(updates.currentVersion),
        subtitle = when (s) {
            UpdateStatus.Idle -> null
            UpdateStatus.Checking -> "Checking for updates…"
            UpdateStatus.UpToDate -> "You're on the latest version."
            is UpdateStatus.Failed -> s.message
            is UpdateStatus.Available -> s.message
                ?: if (s.asset != null) "Version ${s.release.version} is available."
                else "Version ${s.release.version} has no download for this platform."
            is UpdateStatus.Downloading -> "Downloading ${s.release.version}…"
        },
        subtitleColor = when {
            s is UpdateStatus.Failed || (s is UpdateStatus.Available && s.message != null) -> MaterialTheme.colorScheme.error
            else -> Color.Unspecified
        },
        onClick = updates::check.takeIf { s == UpdateStatus.Idle || s == UpdateStatus.UpToDate || s is UpdateStatus.Failed },
        trailing = {
            when (s) {
                UpdateStatus.Checking -> SmallSpinner()
                is UpdateStatus.Available -> if (s.asset != null) Button(onClick = updates::install) { Text("Update") }
                is UpdateStatus.Downloading -> Unit
                else -> Text("Check", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        },
        below = (s as? UpdateStatus.Downloading)?.let { d ->
            {
                val progress = d.progress
                if (progress != null) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        },
    )
}

/** Horizontal inset of the setting cards from the screen edges. */
private val CardInset = 16.dp
private val RowPadding = 16.dp
private val IconSize = 20.dp
private val IconGap = 14.dp

/** A titled card of setting rows, with an optional note below it. */
@Composable
private fun SettingsGroup(title: String, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = RowPadding, bottom = 6.dp),
        )
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(content = content)
        }
        footer?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = RowPadding, end = RowPadding, top = 6.dp),
            )
        }
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(
        Modifier.padding(start = RowPadding + IconSize + IconGap),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

/**
 * One setting: [icon], [title] and [subtitle], and a [trailing] control. Clickable when [onClick] is set, unless a
 * [modifier] supplies the interaction (e.g. a toggle). [below] spans the text column under the subtitle.
 */
@Composable
private fun SettingsRow(
    icon: ImageVector?,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleColor: Color = Color.Unspecified,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    role: Role = Role.Button,
    trailing: (@Composable () -> Unit)? = null,
    below: (@Composable () -> Unit)? = null,
) {
    val interaction = if (onClick != null) Modifier.clickable(enabled = enabled, role = role, onClick = onClick) else Modifier
    Row(
        modifier.then(interaction).fillMaxWidth().heightIn(min = 48.dp)
            .padding(horizontal = RowPadding, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val contentAlpha = if (enabled) 1f else 0.38f
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(IconSize).alpha(contentAlpha))
        } else {
            Spacer(Modifier.width(IconSize))
        }
        Spacer(Modifier.width(IconGap))
        Column(Modifier.weight(1f).alpha(contentAlpha), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (subtitleColor != Color.Unspecified) subtitleColor else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            below?.let { Box(Modifier.padding(top = 6.dp)) { it() } }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

@Composable
private fun ToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    SettingsRow(
        icon = icon,
        title = title,
        subtitle = subtitle,
        enabled = enabled,
        modifier = Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        trailing = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
    )
}

/**
 * A setting showing its current value, [selectedLabel] at the end or (when null) in [subtitle]; tapping opens a
 * menu of [options] at the end of the row.
 */
@Composable
private fun <T> ChoiceRow(
    icon: ImageVector,
    title: String,
    options: List<T>,
    selected: T?,
    selectedLabel: String?,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    subtitle: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    SettingsRow(
        icon = icon,
        title = title,
        subtitle = subtitle,
        onClick = { open = true },
        role = Role.DropdownList,
        trailing = {
            Box {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    selectedLabel?.let {
                        Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    TrailingIcon(Icons.Default.UnfoldMore)
                }
                ChoiceMenu(open, title, options, selected, label, onSelect, onDismiss = { open = false })
            }
        },
    )
}

@Composable
private fun TrailingIcon(icon: ImageVector) {
    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
}

@Composable
private fun SmallSpinner() {
    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
}
