package io.waves.data

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/**
 * Per-device user preferences. Not synced (deliberately absent from SyncState).
 *
 * @param country Apple Podcasts store (ISO 3166-1 alpha-2, lowercase) for charts and search; null follows the
 *   device region (see [effectiveCountry]).
 * @param autoDownloadNewEpisodes placeholder: persisted only, nothing acts on it yet.
 * @param syncEnabled when false, no automatic or manual Google Drive sync runs.
 */
@Serializable
data class UserSettings(
    val country: String? = null,
    val defaultSpeed: Float = 1.0f,
    val skipBackSeconds: Int = 15,
    val skipForwardSeconds: Int = 30,
    val autoDeleteDownloads: Boolean = false,
    val autoDownloadNewEpisodes: Boolean = false,
    val syncEnabled: Boolean = true,
) {
    /** [country], or the device region when it follows the device. */
    val effectiveCountry: String get() = country ?: deviceRegion()
}

/** Persistent [UserSettings]. [settings] always holds the current value (loaded at construction). */
interface SettingsStore {
    val settings: StateFlow<UserSettings>

    /** Atomically replace the settings with `transform(current)` and persist them. */
    suspend fun update(transform: (UserSettings) -> UserSettings)
}

/** Lowercase two-letter region of the device locale, or "us" when it has none. */
fun deviceRegion(): String = normalizeRegion(platformRegion()) ?: "us"

internal fun normalizeRegion(region: String?): String? =
    region?.trim()?.lowercase()?.takeIf { it.length == 2 && it.all { c -> c in 'a'..'z' } }

/** Raw region code of the device's default locale (may be empty or a UN M.49 number). */
internal expect fun platformRegion(): String?
