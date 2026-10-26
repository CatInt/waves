package io.waves.ui

import io.waves.data.SettingsStore
import io.waves.data.UserSettings
import io.waves.player.AudioPlayer
import io.waves.sync.AuthState
import io.waves.sync.GoogleAuth
import io.waves.sync.SyncService
import io.waves.sync.SyncStatus
import io.waves.ui.fake.InMemorySettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val auth: AuthState = AuthState.SignedOut,
    val sync: SyncStatus = SyncStatus.Idle,
    val busy: Boolean = false,
    val error: String? = null,
    val settings: UserSettings = UserSettings(),
    /** No Internet connection. */
    val offline: Boolean = false,
) {
    /** "Sync now" is offered only while signed in, sync is enabled, the app is online and nothing is running. */
    val canSyncNow: Boolean
        get() = auth is AuthState.SignedIn && settings.syncEnabled && !offline && !busy && sync != SyncStatus.Syncing
}

/** Google sign-in, manual sync and the per-device [UserSettings]. */
class SettingsState(
    private val auth: GoogleAuth,
    private val sync: SyncService,
    private val scope: CoroutineScope,
    private val store: SettingsStore = InMemorySettingsStore(),
    private val offline: StateFlow<Boolean> = MutableStateFlow(false),
) {
    private val busy = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)

    val state: StateFlow<SettingsUiState> =
        combine(auth.state, sync.status, busy, error, combine(store.settings, offline, ::Pair)) { a, s, b, e, (u, off) ->
            SettingsUiState(a, s, b, e, u, off)
        }.stateIn(
            scope, SharingStarted.Eagerly,
            SettingsUiState(auth.state.value, sync.status.value, settings = store.settings.value, offline = offline.value),
        )

    fun signIn() = run {
        auth.signIn()
        if (store.settings.value.syncEnabled && !offline.value) sync.syncNow()
    }

    fun signOut() = run { auth.signOut() }

    /** No-op while sync is disabled or offline. */
    fun syncNow() {
        if (store.settings.value.syncEnabled && !offline.value) run { sync.syncNow() }
    }

    /** null follows the device region. */
    fun setCountry(country: String?) = edit { it.copy(country = country?.trim()?.lowercase()?.ifEmpty { null }) }

    fun setDefaultSpeed(speed: Float) = edit {
        it.copy(defaultSpeed = if (speed.isNaN()) 1f else speed.coerceIn(AudioPlayer.MIN_SPEED, AudioPlayer.MAX_SPEED))
    }

    fun setSkipBackSeconds(seconds: Int) = edit { it.copy(skipBackSeconds = seconds.coerceIn(1, 600)) }

    fun setSkipForwardSeconds(seconds: Int) = edit { it.copy(skipForwardSeconds = seconds.coerceIn(1, 600)) }

    fun setAutoDeleteDownloads(enabled: Boolean) = edit { it.copy(autoDeleteDownloads = enabled) }

    fun setSyncEnabled(enabled: Boolean) = edit { it.copy(syncEnabled = enabled) }

    private fun edit(transform: (UserSettings) -> UserSettings) {
        scope.launch {
            try {
                store.update(transform)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error.value = e.message ?: "Couldn't save settings"
            }
        }
    }

    private fun run(block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        error.value = null
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error.value = e.message ?: e::class.simpleName ?: "Something went wrong"
            } finally {
                busy.value = false
            }
        }
    }
}

/** Apple Podcasts stores offered by the country picker (code to display name); null code = device default. */
val CountryOptions: List<Pair<String, String>> = listOf(
    "us" to "United States", "gb" to "United Kingdom", "ca" to "Canada", "au" to "Australia",
    "ie" to "Ireland", "nz" to "New Zealand", "in" to "India", "de" to "Germany", "fr" to "France",
    "nl" to "Netherlands", "be" to "Belgium", "es" to "Spain", "it" to "Italy", "se" to "Sweden",
    "br" to "Brazil", "mx" to "Mexico", "jp" to "Japan", "cn" to "China", "tw" to "Taiwan", "hk" to "Hong Kong",
)

fun countryLabel(code: String): String =
    CountryOptions.firstOrNull { it.first == code }?.second?.let { "$it (${code.uppercase()})" } ?: code.uppercase()

val SkipBackOptions = listOf(5, 10, 15, 30, 45, 60)
val SkipForwardOptions = listOf(10, 15, 30, 45, 60, 90)
