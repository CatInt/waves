package io.waves.sync

import io.waves.model.HistoryEntry
import io.waves.model.PlaybackProgress
import io.waves.model.Podcast
import kotlinx.serialization.Serializable

/**
 * Everything that syncs between devices, stored as one JSON file in the user's Google Drive appDataFolder.
 * Merge rules: subscriptions LWW per feedUrl (tombstones via subscribed=false), progress LWW per episode,
 * history = union deduplicated by (episodeId, playedAt, deviceId).
 */
@Serializable
data class SyncState(
    val version: Int = 1,
    val subscriptions: List<SubscriptionRecord> = emptyList(),
    val progress: List<PlaybackProgress> = emptyList(),
    val history: List<HistoryEntry> = emptyList(),
)

@Serializable
data class SubscriptionRecord(
    val podcast: Podcast,
    val subscribed: Boolean,
    val updatedAtEpochMs: Long,
)

/** Remote blob storage (Google Drive appDataFolder). */
interface CloudStore {
    /** Returns file content and an opaque revision tag, or null if absent. */
    suspend fun read(name: String): Pair<String, String>?
    /**
     * Writes content, as a compare-and-set on the revision from [read]:
     * - [ifRevision] non-null: replaces the file only if it is still at that revision.
     * - [ifRevision] null: creates the file only if it doesn't exist (the caller read nothing).
     * Otherwise throws [ConflictException]; callers re-read, merge and retry.
     */
    suspend fun write(name: String, content: String, ifRevision: String?)
}

class ConflictException(message: String) : Exception(message)

sealed interface AuthState {
    data object SignedOut : AuthState
    data class SignedIn(val email: String?) : AuthState
}

/** Google account sign-in granting the drive.appdata scope; platform-specific implementations. */
interface GoogleAuth {
    val state: kotlinx.coroutines.flow.StateFlow<AuthState>
    suspend fun signIn()
    suspend fun signOut()
    /** A currently valid OAuth access token, refreshing if needed. */
    suspend fun accessToken(): String?
}

sealed interface SyncStatus {
    data object Idle : SyncStatus
    data object Syncing : SyncStatus
    data class Done(val atEpochMs: Long) : SyncStatus
    data class Failed(val message: String) : SyncStatus
}

interface SyncService {
    val status: kotlinx.coroutines.flow.StateFlow<SyncStatus>
    /** Pull remote, merge with local, write merged back to both. */
    suspend fun syncNow()
}
