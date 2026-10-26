package io.waves.ui

import io.waves.BuildInfo
import io.waves.update.AppRelease
import io.waves.update.GitHubReleases
import io.waves.update.ReleaseAsset
import io.waves.update.UpdateInstaller
import io.waves.update.UpdateNeedsPermissionException
import io.waves.update.Version
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data object UpToDate : UpdateStatus
    /** [asset] is null when the release has no file for this platform. */
    data class Available(val release: AppRelease, val asset: ReleaseAsset?, val message: String? = null) : UpdateStatus
    data class Downloading(val release: AppRelease, val progress: Float?) : UpdateStatus
    data class Failed(val message: String) : UpdateStatus
}

/**
 * Checks GitHub for a newer release and installs it. Release builds check [AUTO_CHECK_DELAY_MS] after launch
 * and then every [AUTO_CHECK_INTERVAL_MS]; development builds only check when asked.
 */
class UpdateState(
    private val releases: GitHubReleases,
    private val installer: UpdateInstaller,
    private val scope: CoroutineScope,
    val currentVersion: String = BuildInfo.VERSION,
    autoCheck: Boolean = BuildInfo.IS_RELEASE,
) {
    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    private var job: Job? = null

    init {
        if (autoCheck) {
            scope.launch {
                delay(AUTO_CHECK_DELAY_MS)
                while (true) {
                    if (job?.isActive != true && _status.value !is UpdateStatus.Available) runCheck(quiet = true)
                    delay(AUTO_CHECK_INTERVAL_MS)
                }
            }
        }
    }

    fun check() {
        if (job?.isActive == true) return
        job = scope.launch { runCheck(quiet = false) }
    }

    fun install() {
        val available = _status.value as? UpdateStatus.Available ?: return
        val asset = available.asset ?: return
        if (job?.isActive == true) return
        job = scope.launch {
            val release = available.release
            _status.value = UpdateStatus.Downloading(release, null)
            try {
                installer.install(release, asset) { progress -> _status.value = UpdateStatus.Downloading(release, progress) }
                // Android: the system installer is showing; if the user backs out they can tap Update again.
                _status.value = UpdateStatus.Available(release, asset)
            } catch (e: CancellationException) {
                throw e
            } catch (e: UpdateNeedsPermissionException) {
                _status.value = UpdateStatus.Available(release, asset, e.message)
            } catch (e: Exception) {
                _status.value = UpdateStatus.Available(release, asset, "Update failed: ${e.message ?: e::class.simpleName}")
            }
        }
    }

    private suspend fun runCheck(quiet: Boolean) {
        val before = _status.value
        _status.value = UpdateStatus.Checking
        _status.value = try {
            val latest = releases.latest()
            val current = Version.parse(currentVersion)
            if (latest != null && (current == null || latest.version > current)) {
                UpdateStatus.Available(latest, installer.assetFor(latest))
            } else {
                UpdateStatus.UpToDate
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (quiet) before else UpdateStatus.Failed("Couldn't check for updates: ${e.message ?: e::class.simpleName}")
        }
    }

    companion object {
        const val AUTO_CHECK_DELAY_MS = 5_000L
        const val AUTO_CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L
    }
}
