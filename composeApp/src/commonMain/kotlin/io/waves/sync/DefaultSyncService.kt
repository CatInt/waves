package io.waves.sync

import io.waves.currentTimeMillis
import io.waves.data.LibraryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * Read remote → merge with local → write remote (optimistic, retried on [ConflictException]) → merge into local.
 * Never throws (except cancellation); outcome is reported through [status]. Concurrent calls are serialized.
 */
class DefaultSyncService(
    private val library: LibraryRepository,
    private val cloud: CloudStore,
    private val fileName: String = DEFAULT_FILE_NAME,
    private val maxAttempts: Int = 3,
    private val clock: () -> Long = ::currentTimeMillis,
) : SyncService {

    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    override val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val mutex = Mutex()

    override suspend fun syncNow() = mutex.withLock {
        _status.value = SyncStatus.Syncing
        _status.value = try {
            runSync()
            SyncStatus.Done(clock())
        } catch (e: CancellationException) {
            _status.value = SyncStatus.Idle
            throw e
        } catch (e: Exception) {
            SyncStatus.Failed(e.message ?: e::class.simpleName ?: "Sync failed")
        }
    }

    private suspend fun runSync() {
        repeat(maxAttempts) {
            val remote = cloud.read(fileName)
            // Throws (failing the sync before anything is written or imported) for unreadable files and for
            // files from a newer app version: see SyncFormat for the compatibility rules.
            val decoded = remote?.let { SyncFormat.decode(it.first) }
            val remoteState = decoded?.state
            val local = library.exportSyncState()
            val merged = if (remoteState == null) SyncMerge.normalize(local) else SyncMerge.merge(local, remoteState)

            if (remote == null || merged != remoteState) {
                try {
                    cloud.write(fileName, SyncFormat.encode(merged, decoded?.extras.orEmpty()), remote?.second)
                } catch (e: ConflictException) {
                    return@repeat // someone else wrote meanwhile: start over with their version
                }
            }

            // importSyncState merges (LWW per key, history union), so anything recorded locally while we were
            // talking to the network survives without re-exporting; such late changes reach the remote next sync.
            // Only records that differ from our snapshot are imported (the rest would be no-ops), so pulling one
            // remote edit into a large library touches one row, not all of them.
            val delta = changesSince(SyncMerge.normalize(local), merged)
            if (delta.subscriptions.isNotEmpty() || delta.progress.isNotEmpty() || delta.history.isNotEmpty()) {
                library.importSyncState(delta)
            }
            return
        }
        throw ConflictException("Sync kept conflicting with another device after $maxAttempts attempts")
    }

    /** Records of [merged] that aren't identical in [local]. */
    private fun changesSince(local: SyncState, merged: SyncState): SyncState {
        val subs = local.subscriptions.toHashSet()
        val progress = local.progress.toHashSet()
        val history = local.history.toHashSet()
        return SyncState(
            version = merged.version,
            subscriptions = merged.subscriptions.filter { it !in subs },
            progress = merged.progress.filter { it !in progress },
            history = merged.history.filter { it !in history },
        )
    }

    companion object {
        const val DEFAULT_FILE_NAME = "waves-sync.json"
        internal val json: Json get() = SyncFormat.json
    }
}
