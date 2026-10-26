package io.waves.data

import io.waves.model.Episode
import io.waves.model.EpisodeId
import io.waves.model.HistoryEntry
import io.waves.model.PlaybackProgress
import io.waves.model.Podcast
import kotlinx.coroutines.flow.Flow

/** Local source of truth for everything the user owns: subscriptions, episodes, progress, history. */
interface LibraryRepository {
    val subscriptions: Flow<List<Podcast>>
    fun episodes(feedUrl: String): Flow<List<Episode>>
    fun progress(episodeId: EpisodeId): Flow<PlaybackProgress?>
    val allProgress: Flow<List<PlaybackProgress>>
    val history: Flow<List<HistoryEntry>>

    suspend fun subscribe(podcast: Podcast)
    suspend fun unsubscribe(feedUrl: String)

    /**
     * Updates a known podcast's metadata (title, author, description, artwork; appleId only if non-null) as
     * found in its feed. Never changes the subscription state or its sync timestamp, so it isn't a user edit
     * for sync purposes. No-op if the podcast isn't stored. Default: no-op (for fakes).
     */
    suspend fun updatePodcastMetadata(podcast: Podcast) {}

    /**
     * Keeps [podcast]'s metadata without subscribing, so episodes of shows the user doesn't follow (e.g. downloads)
     * still have a title and cover. Stores a new podcast as unsubscribed with no subscription history (not synced);
     * a known unsubscribed one gets its metadata updated (fields [podcast] lacks keep their stored value), and
     * subscriptions are left to feed refreshes.
     * Default: no-op (for fakes).
     */
    suspend fun rememberPodcast(podcast: Podcast) {}

    /** Any stored podcast, subscribed or not. Default: null (for fakes). */
    suspend fun podcast(feedUrl: String): Podcast? = null
    suspend fun upsertEpisodes(feedUrl: String, episodes: List<Episode>)
    suspend fun episode(episodeId: EpisodeId): Episode?
    suspend fun saveProgress(progress: PlaybackProgress)
    suspend fun addHistory(entry: HistoryEntry)

    /** Snapshot used by sync; includes unsubscribe tombstones. */
    suspend fun exportSyncState(): io.waves.sync.SyncState
    /**
     * Merge a snapshot from sync into local state (never a blind replace): subscriptions and progress are
     * last-writer-wins per key using the same rules as [io.waves.sync.SyncMerge], history is unioned.
     * Local changes newer than the snapshot survive.
     */
    suspend fun importSyncState(state: io.waves.sync.SyncState)
}
