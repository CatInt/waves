package io.waves.sync

import io.waves.data.LibraryRepository
import io.waves.model.Episode
import io.waves.model.EpisodeId
import io.waves.model.HistoryEntry
import io.waves.model.PlaybackProgress
import io.waves.model.Podcast
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

/** In-memory LibraryRepository that only implements what sync needs, with a hook to simulate concurrent edits. */
class FakeLibrary(initial: SyncState = SyncState()) : LibraryRepository {
    val stateFlow = MutableStateFlow(initial)
    var imports = 0
    var exports = 0
    var onExport: (Int) -> Unit = {}

    override val subscriptions: Flow<List<Podcast>> =
        stateFlow.map { s -> s.subscriptions.filter { it.subscribed }.map { it.podcast } }
    override fun episodes(feedUrl: String): Flow<List<Episode>> = MutableStateFlow(emptyList())
    override fun progress(episodeId: EpisodeId): Flow<PlaybackProgress?> =
        stateFlow.map { s -> s.progress.firstOrNull { it.episodeId == episodeId } }
    override val allProgress: Flow<List<PlaybackProgress>> = stateFlow.map { it.progress }
    override val history: Flow<List<HistoryEntry>> = stateFlow.map { it.history }

    override suspend fun subscribe(podcast: Podcast) = error("unused")
    override suspend fun unsubscribe(feedUrl: String) = error("unused")
    override suspend fun upsertEpisodes(feedUrl: String, episodes: List<Episode>) = error("unused")
    override suspend fun episode(episodeId: EpisodeId): Episode? = null
    override suspend fun saveProgress(progress: PlaybackProgress) {
        stateFlow.value = stateFlow.value.copy(progress = stateFlow.value.progress.filter { it.episodeId != progress.episodeId } + progress)
    }
    override suspend fun addHistory(entry: HistoryEntry) {
        stateFlow.value = stateFlow.value.copy(history = stateFlow.value.history + entry)
    }

    override suspend fun exportSyncState(): SyncState {
        onExport(exports++)
        return stateFlow.value
    }

    override suspend fun importSyncState(state: SyncState) {
        imports++
        stateFlow.value = SyncMerge.merge(stateFlow.value, state) // merge, like the real repository
    }
}

/** In-memory CloudStore with integer revisions and optional injected conflicts/failures. */
class FakeCloudStore : CloudStore {
    val files = mutableMapOf<String, Pair<String, Int>>()
    var writes = 0
    var reads = 0
    /** Called before each write's revision check; may mutate [files] to simulate another device. */
    var beforeWrite: (FakeCloudStore) -> Unit = {}
    var failWith: Exception? = null

    override suspend fun read(name: String): Pair<String, String>? {
        failWith?.let { throw it }
        reads++
        return files[name]?.let { it.first to it.second.toString() }
    }

    override suspend fun write(name: String, content: String, ifRevision: String?) {
        failWith?.let { throw it }
        beforeWrite(this)
        val current = files[name]
        // Same contract as CloudStore.write: null means "create; must not exist yet".
        if (current?.second?.toString() != ifRevision) throw ConflictException("conflict")
        writes++
        files[name] = content to ((current?.second ?: 0) + 1)
    }
}

class FakeAuth(signedIn: Boolean = true) : GoogleAuth, InvalidatableAuth {
    override val state = MutableStateFlow<AuthState>(if (signedIn) AuthState.SignedIn("me@example.com") else AuthState.SignedOut)
    var tokens = ArrayDeque(listOf("token-1", "token-2", "token-3"))
    val invalidated = mutableListOf<String>()
    private var current: String? = null

    override suspend fun signIn() { state.value = AuthState.SignedIn("me@example.com") }
    override suspend fun signOut() { state.value = AuthState.SignedOut }
    override suspend fun accessToken(): String? {
        if (state.value !is AuthState.SignedIn) return null
        return current ?: tokens.removeFirst().also { current = it }
    }
    override suspend fun invalidateAccessToken(token: String) {
        invalidated += token
        if (current == token) current = null
    }
}

val StateFlow<SyncStatus>.isDone get() = value is SyncStatus.Done
