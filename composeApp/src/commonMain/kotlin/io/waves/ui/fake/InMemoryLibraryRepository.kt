package io.waves.ui.fake

import io.waves.currentTimeMillis
import io.waves.data.LibraryRepository
import io.waves.model.Episode
import io.waves.model.EpisodeId
import io.waves.model.HistoryEntry
import io.waves.model.PlaybackProgress
import io.waves.model.Podcast
import io.waves.sync.SubscriptionRecord
import io.waves.sync.SyncMerge
import io.waves.sync.SyncState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** Non-persistent [LibraryRepository] for previews, tests and running without the database. */
class InMemoryLibraryRepository(
    private val clock: () -> Long = ::currentTimeMillis,
) : LibraryRepository {
    private val subs = MutableStateFlow<Map<String, SubscriptionRecord>>(emptyMap())
    private val episodesByFeed = MutableStateFlow<Map<String, List<Episode>>>(emptyMap())
    private val progressById = MutableStateFlow<Map<EpisodeId, PlaybackProgress>>(emptyMap())
    private val historyList = MutableStateFlow<List<HistoryEntry>>(emptyList())
    /** Podcasts kept by [rememberPodcast]; unlike [subs] these have no subscription history and aren't synced. */
    private val remembered = MutableStateFlow<Map<String, Podcast>>(emptyMap())

    override val subscriptions: Flow<List<Podcast>> =
        subs.map { m -> m.values.filter { it.subscribed }.map { it.podcast } }.distinctUntilChanged()

    override fun episodes(feedUrl: String): Flow<List<Episode>> =
        episodesByFeed.map { it[feedUrl].orEmpty() }.distinctUntilChanged()

    override fun progress(episodeId: EpisodeId): Flow<PlaybackProgress?> =
        progressById.map { it[episodeId] }.distinctUntilChanged()

    override val allProgress: Flow<List<PlaybackProgress>> = progressById.map { it.values.toList() }

    override val history: Flow<List<HistoryEntry>> =
        historyList.map { list -> list.sortedByDescending { it.playedAtEpochMs } }

    override suspend fun subscribe(podcast: Podcast) {
        subs.update { it + (podcast.feedUrl to SubscriptionRecord(podcast, true, clock())) }
    }

    override suspend fun updatePodcastMetadata(podcast: Podcast) {
        subs.update { m ->
            val rec = m[podcast.feedUrl] ?: return@update m
            m + (podcast.feedUrl to rec.copy(podcast = podcast.copy(appleId = podcast.appleId ?: rec.podcast.appleId)))
        }
    }

    override suspend fun rememberPodcast(podcast: Podcast) {
        if (subs.value[podcast.feedUrl]?.subscribed == true) return
        remembered.update { m ->
            val old = m[podcast.feedUrl] ?: subs.value[podcast.feedUrl]?.podcast
            m + (podcast.feedUrl to if (old == null) podcast else podcast.copy(
                title = podcast.title.ifBlank { old.title },
                author = podcast.author ?: old.author,
                description = podcast.description ?: old.description,
                artworkUrl = podcast.artworkUrl ?: old.artworkUrl,
                appleId = podcast.appleId ?: old.appleId,
            ))
        }
    }

    override suspend fun podcast(feedUrl: String): Podcast? =
        subs.value[feedUrl]?.takeIf { it.subscribed }?.podcast ?: remembered.value[feedUrl] ?: subs.value[feedUrl]?.podcast

    override suspend fun unsubscribe(feedUrl: String) {
        subs.update { m ->
            val rec = m[feedUrl] ?: return@update m
            m + (feedUrl to rec.copy(subscribed = false, updatedAtEpochMs = clock()))
        }
    }

    override suspend fun upsertEpisodes(feedUrl: String, episodes: List<Episode>) {
        episodesByFeed.update { m ->
            val merged = (m[feedUrl].orEmpty().associateBy { it.guid } + episodes.associateBy { it.guid }).values
            m + (feedUrl to merged.sortedByDescending { it.publishedAtEpochMs ?: 0L })
        }
    }

    override suspend fun episode(episodeId: EpisodeId): Episode? =
        episodesByFeed.value[episodeId.feedUrl]?.firstOrNull { it.guid == episodeId.guid }

    override suspend fun saveProgress(progress: PlaybackProgress) {
        progressById.update { it + (progress.episodeId to progress) }
    }

    override suspend fun addHistory(entry: HistoryEntry) {
        historyList.update { list ->
            if (list.any { it.episodeId == entry.episodeId && it.playedAtEpochMs == entry.playedAtEpochMs && it.deviceId == entry.deviceId }) list
            else list + entry
        }
    }

    override suspend fun exportSyncState(): SyncState = SyncState(
        subscriptions = subs.value.values.toList(),
        progress = progressById.value.values.toList(),
        history = historyList.value,
    )

    override suspend fun importSyncState(state: SyncState) {
        val local = subs.value
        val merged = SyncMerge.merge(exportSyncState(), state)
        // Same as SqlLibraryRepository: known podcasts keep their own metadata, only blanks are filled.
        subs.value = merged.subscriptions.associate { rec ->
            val mine = local[rec.podcast.feedUrl]?.podcast
            rec.podcast.feedUrl to if (mine == null) rec else rec.copy(podcast = SyncMerge.keepLocalMetadata(mine, rec.podcast))
        }
        progressById.value = merged.progress.associateBy { it.episodeId }
        historyList.value = merged.history
    }
}
