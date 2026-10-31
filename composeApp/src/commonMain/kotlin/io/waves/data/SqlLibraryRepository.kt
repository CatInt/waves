package io.waves.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import app.cash.sqldelight.db.SqlDriver
import io.waves.currentTimeMillis
import io.waves.data.db.WavesDatabase
import io.waves.model.Episode
import io.waves.model.EpisodeId
import io.waves.model.HistoryEntry
import io.waves.model.PlaybackProgress
import io.waves.model.Podcast
import io.waves.sync.ShowSettingsRecord
import io.waves.sync.SubscriptionRecord
import io.waves.sync.SyncMerge
import io.waves.sync.SyncState
import io.waves.youtube.YoutubeUrls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import io.waves.data.db.Episode as EpisodeRow
import io.waves.data.db.History as HistoryRow
import io.waves.data.db.Progress as ProgressRow

/**
 * SQLDelight-backed [LibraryRepository]. The [driver] must already have the schema created
 * (use [WavesDatabase.Schema] / the platform `createLibraryRepository` factories).
 */
class SqlLibraryRepository(
    /** The underlying driver, exposed so other stores (e.g. downloads) share this connection. */
    val driver: SqlDriver,
    private val ioContext: CoroutineContext = Dispatchers.IO,
    /**
     * Feeds that only ever list their newest few episodes (a YouTube live archive), so the older ones stored here
     * are the archive. A followed one's episodes go out with sync ([SyncState.archive]).
     */
    private val archivedFeed: (String) -> Boolean = YoutubeUrls::isLiveArchive,
    private val clock: () -> Long = ::currentTimeMillis,
) : LibraryRepository {

    private val db = WavesDatabase(driver)
    private val podcasts = db.podcastQueries
    private val episodesQ = db.episodeQueries
    private val progressQ = db.progressQueries
    private val historyQ = db.historyQueries
    private val settings = db.settingQueries

    /**
     * Serializes write transactions. The desktop JDBC driver uses one SQLite connection per thread, and two
     * concurrent read-then-write transactions on different connections fail with SQLITE_BUSY (lock upgrade
     * deadlock, which SQLite reports immediately instead of waiting).
     */
    private val writeLock = Mutex()

    private suspend fun <T> write(block: () -> T): T = writeLock.withLock { withContext(ioContext) { block() } }

    override val subscriptions: Flow<List<Podcast>> =
        podcasts.subscribed(::podcastFromColumns).asFlow().mapToList(ioContext)

    override fun episodes(feedUrl: String): Flow<List<Episode>> =
        episodesQ.byFeed(feedUrl).asFlow().mapToList(ioContext).mapList { it.toModel() }

    override fun progress(episodeId: EpisodeId): Flow<PlaybackProgress?> =
        progressQ.byId(episodeId.feedUrl, episodeId.guid).asFlow().mapToOneOrNull(ioContext).map { it?.toModel() }

    override val allProgress: Flow<List<PlaybackProgress>> =
        progressQ.all().asFlow().mapToList(ioContext).mapList { it.toModel() }

    override val history: Flow<List<HistoryEntry>> =
        historyQ.all().asFlow().mapToList(ioContext).mapList { it.toModel() }

    override val showSettings: Flow<Map<String, ShowSettingsRecord>> =
        settings.get(SHOW_SETTINGS_KEY).asFlow().mapToOneOrNull(ioContext).map { decodeShowSettings(it).associateBy { r -> r.feedUrl } }

    override suspend fun setShowSettings(record: ShowSettingsRecord) = write {
        settings.transaction { putShowSettings(listOf(record), force = true) }
    }

    /** Stores [records] over the stored ones, each only if it wins under [SyncMerge.supersedes] unless [force]d. */
    private fun putShowSettings(records: List<ShowSettingsRecord>, force: Boolean) {
        if (records.isEmpty()) return
        val stored = decodeShowSettings(settings.get(SHOW_SETTINGS_KEY).executeAsOneOrNull()).associateBy { it.feedUrl }.toMutableMap()
        for (r in records) {
            val old = stored[r.feedUrl]
            if (force || old == null || SyncMerge.supersedes(r, old)) stored[r.feedUrl] = r
        }
        settings.put(SHOW_SETTINGS_KEY, showSettingsJson.encodeToString(showSettingsSerializer, stored.values.sortedBy { it.feedUrl }))
    }

    override suspend fun subscribe(podcast: Podcast) = write {
        podcasts.upsertPodcast(podcast, subscribed = true, updatedAt = clock())
    }

    override suspend fun updatePodcastMetadata(podcast: Podcast) = write {
        podcasts.updateMetadata(
            title = podcast.title,
            author = podcast.author,
            description = podcast.description,
            artworkUrl = podcast.artworkUrl,
            appleId = podcast.appleId,
            feedUrl = podcast.feedUrl,
        )
    }

    override suspend fun rememberPodcast(podcast: Podcast) = write {
        podcasts.transaction {
            val row = podcasts.byFeedUrl(podcast.feedUrl).executeAsOneOrNull()
            when {
                row == null -> podcasts.insertUnsubscribedIfAbsent(
                    podcast.feedUrl, podcast.title, podcast.author, podcast.description, podcast.artworkUrl, podcast.appleId,
                )
                row.subscribed == 0L -> podcasts.updateMetadata(
                    title = podcast.title.ifBlank { row.title },
                    author = podcast.author ?: row.author,
                    description = podcast.description ?: row.description,
                    artworkUrl = podcast.artworkUrl ?: row.artworkUrl,
                    appleId = podcast.appleId,
                    feedUrl = podcast.feedUrl,
                )
            }
        }
    }

    override suspend fun podcast(feedUrl: String): Podcast? = withContext(ioContext) {
        podcasts.byFeedUrl(feedUrl, ::podcastFromColumns).executeAsOneOrNull()
    }

    override suspend fun unsubscribe(feedUrl: String) = write {
        // Keep the row as a tombstone so the unsubscribe propagates through sync.
        podcasts.markUnsubscribed(updatedAt = clock(), feedUrl = feedUrl)
    }

    override suspend fun upsertEpisodes(feedUrl: String, episodes: List<Episode>) = write {
        episodesQ.transaction {
            for (e in episodes) {
                episodesQ.upsert(
                    feedUrl, e.guid, e.title, e.audioUrl, e.publishedAtEpochMs,
                    e.durationSeconds, e.description, e.artworkUrl,
                )
            }
        }
    }

    override suspend fun episode(episodeId: EpisodeId): Episode? = withContext(ioContext) {
        episodesQ.byId(episodeId.feedUrl, episodeId.guid).executeAsOneOrNull()?.toModel()
    }

    override suspend fun saveProgress(progress: PlaybackProgress) = write {
        progressQ.insertProgress(progress)
    }

    override suspend fun addHistory(entry: HistoryEntry) = write {
        historyQ.insertHistory(entry)
    }

    override suspend fun exportSyncState(): SyncState = withContext(ioContext) {
        db.transactionWithResult {
            SyncState(
                subscriptions = podcasts.subscriptionRecords { feedUrl, title, author, description, artworkUrl, appleId, subscribed, updatedAt ->
                    SubscriptionRecord(
                        podcast = Podcast(feedUrl, title, author, description, artworkUrl, appleId),
                        subscribed = subscribed != 0L,
                        updatedAtEpochMs = updatedAt ?: 0L,
                    )
                }.executeAsList(),
                progress = progressQ.all().executeAsList().map { it.toModel() },
                history = historyQ.all().executeAsList().map { it.toModel() },
                archive = podcasts.subscribed(::podcastFromColumns).executeAsList()
                    .filter { archivedFeed(it.feedUrl) }
                    .flatMap { p -> episodesQ.byFeed(p.feedUrl).executeAsList().map { it.toModel() } },
                showSettings = decodeShowSettings(settings.get(SHOW_SETTINGS_KEY).executeAsOneOrNull()),
            )
        }
    }

    /**
     * Merges [state] into local data (never deletes): subscriptions and progress are applied per key only if
     * they win under [SyncMerge.supersedes] (the same order the sync merge uses), history is unioned.
     * Runs in one transaction, so a concurrent local write either lands before (and is compared against)
     * or after (and is kept as is).
     */
    override suspend fun importSyncState(state: SyncState) = write {
        db.transaction {
            for (record in state.subscriptions) {
                val row = podcasts.byFeedUrl(record.podcast.feedUrl).executeAsOneOrNull()
                if (row == null) {
                    podcasts.upsertPodcast(record.podcast, record.subscribed, record.updatedAtEpochMs)
                    continue
                }
                val local = Podcast(row.feedUrl, row.title, row.author, row.description, row.artworkUrl, row.appleId)
                // A podcast row without subscriptionUpdatedAt has no subscription history, so anything wins.
                val stored = row.subscriptionUpdatedAt?.let { SubscriptionRecord(local, row.subscribed != 0L, it) }
                // Metadata comes from each device's own feed refresh: keep ours, only filling blanks.
                val metadata = SyncMerge.keepLocalMetadata(local, record.podcast)
                if (stored == null || SyncMerge.supersedes(record, stored)) {
                    podcasts.upsertPodcast(metadata, record.subscribed, record.updatedAtEpochMs)
                } else if (metadata != local) {
                    podcasts.upsertPodcast(metadata, row.subscribed != 0L, stored.updatedAtEpochMs)
                }
            }
            for (incoming in state.progress) {
                val stored = progressQ.byId(incoming.episodeId.feedUrl, incoming.episodeId.guid).executeAsOneOrNull()?.toModel()
                if (stored == null || SyncMerge.supersedes(incoming, stored)) progressQ.putProgress(incoming)
            }
            state.history.forEach { historyQ.insertHistory(it) }
            putShowSettings(state.showSettings, force = false)
            for (e in state.archive) {
                if (!archivedFeed(e.podcastFeedUrl)) continue
                episodesQ.insertIfAbsent(
                    e.podcastFeedUrl, e.guid, e.title, e.audioUrl, e.publishedAtEpochMs,
                    e.durationSeconds, e.description, e.artworkUrl,
                )
                e.durationSeconds?.let { episodesQ.fillDuration(it, e.podcastFeedUrl, e.guid) }
            }
        }
    }

    /** Stable per-install identifier (random UUID generated on first call and persisted). */
    @OptIn(ExperimentalUuidApi::class)
    suspend fun deviceId(): String = write {
        settings.transactionWithResult {
            settings.insertIfAbsent(DEVICE_ID_KEY, Uuid.random().toString())
            settings.get(DEVICE_ID_KEY).executeAsOne()
        }
    }

    private fun io.waves.data.db.PodcastQueries.upsertPodcast(p: Podcast, subscribed: Boolean, updatedAt: Long) =
        upsert(p.feedUrl, p.title, p.author, p.description, p.artworkUrl, p.appleId, if (subscribed) 1L else 0L, updatedAt)

    /** Last-writer-wins: overwrite only when the incoming update is at least as new as the stored one. */
    private fun io.waves.data.db.ProgressQueries.insertProgress(p: PlaybackProgress) = transaction {
        val stored = storedUpdatedAt(p.episodeId.feedUrl, p.episodeId.guid).executeAsOneOrNull()
        if (stored == null || p.updatedAtEpochMs >= stored) putProgress(p)
    }

    private fun io.waves.data.db.ProgressQueries.putProgress(p: PlaybackProgress) = put(
        p.episodeId.feedUrl, p.episodeId.guid, p.positionMs, p.durationMs,
        if (p.completed) 1L else 0L, p.updatedAtEpochMs,
    )

    private fun io.waves.data.db.HistoryQueries.insertHistory(h: HistoryEntry) =
        insertIgnore(h.episodeId.feedUrl, h.episodeId.guid, h.episodeTitle, h.podcastTitle, h.playedAtEpochMs, h.deviceId)

    companion object {
        const val DEVICE_ID_KEY = "deviceId"
        const val SHOW_SETTINGS_KEY = "showSettings"

        private val showSettingsJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        private val showSettingsSerializer = kotlinx.serialization.builtins.ListSerializer(ShowSettingsRecord.serializer())

        private fun decodeShowSettings(value: String?): List<ShowSettingsRecord> =
            value?.let { runCatching { showSettingsJson.decodeFromString(showSettingsSerializer, it) }.getOrNull() }.orEmpty()
    }
}

private fun <T, R> Flow<List<T>>.mapList(transform: (T) -> R): Flow<List<R>> = map { it.map(transform) }

@Suppress("UNUSED_PARAMETER")
private fun podcastFromColumns(
    feedUrl: String, title: String, author: String?, description: String?, artworkUrl: String?,
    appleId: Long?, subscribed: Long, subscriptionUpdatedAt: Long?,
) = Podcast(feedUrl, title, author, description, artworkUrl, appleId)

private fun EpisodeRow.toModel() = Episode(
    guid = guid, podcastFeedUrl = feedUrl, title = title, audioUrl = audioUrl,
    publishedAtEpochMs = publishedAt, durationSeconds = durationSeconds,
    description = description, artworkUrl = artworkUrl,
)

private fun ProgressRow.toModel() = PlaybackProgress(
    episodeId = EpisodeId(feedUrl, guid), positionMs = positionMs, durationMs = durationMs,
    completed = completed != 0L, updatedAtEpochMs = updatedAt,
)

private fun HistoryRow.toModel() = HistoryEntry(
    episodeId = EpisodeId(feedUrl, guid), episodeTitle = episodeTitle, podcastTitle = podcastTitle,
    playedAtEpochMs = playedAt, deviceId = deviceId,
)
