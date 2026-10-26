package io.waves.offline

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.db.SqlDriver
import io.waves.data.db.WavesDatabase
import io.waves.model.EpisodeId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * [PlaybackCache] in the `playback_cache` table, sharing the library database.
 * [onResourceRemoved] runs after the row is deleted so the platform can drop the cached bytes
 * (the second argument is the stored audio URL, when the row existed).
 */
class SqlPlaybackCache(
    driver: SqlDriver,
    private val ioContext: CoroutineContext = Dispatchers.IO,
    private val onResourceRemoved: (EpisodeId, String?) -> Unit = { _, _ -> },
) : PlaybackCache {
    private val queries = WavesDatabase(driver).playbackCacheQueries

    override val entries: Flow<List<PlaybackCacheEntry>> =
        queries.all().asFlow().mapToList(ioContext).map { rows -> rows.map { it.toEntry() } }

    override suspend fun upsert(entry: PlaybackCacheEntry) = withContext(ioContext) {
        queries.upsert(
            entry.episodeId.feedUrl,
            entry.episodeId.guid,
            entry.audioUrl,
            entry.bytesCached,
            entry.totalBytes,
            entry.updatedAtEpochMs,
        )
    }

    override suspend fun remove(episodeId: EpisodeId) = withContext(ioContext) {
        val audioUrl = queries.byId(episodeId.feedUrl, episodeId.guid).executeAsOneOrNull()?.audioUrl
        queries.remove(episodeId.feedUrl, episodeId.guid)
        onResourceRemoved(episodeId, audioUrl)
    }
}

private fun io.waves.data.db.Playback_cache.toEntry() = PlaybackCacheEntry(
    episodeId = EpisodeId(feedUrl, guid),
    audioUrl = audioUrl,
    bytesCached = bytesCached,
    totalBytes = totalBytes,
    updatedAtEpochMs = updatedAt,
)
