package io.waves.data

import app.cash.sqldelight.db.SqlDriver
import io.waves.data.db.WavesDatabase
import io.waves.model.EpisodeId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.coroutines.CoroutineContext

/**
 * What was playing, and when it was last seen playing. Kept while audio plays and cleared when playback stops on
 * purpose, so one surviving a restart means playback was interrupted (quit, crash, process killed). Local only.
 */
@Serializable
data class PlaybackSession(
    val feedUrl: String,
    val guid: String,
    val positionMs: Long,
    val playingAtEpochMs: Long,
) {
    val episodeId: EpisodeId get() = EpisodeId(feedUrl, guid)
}

interface PlaybackSessionStore {
    suspend fun read(): PlaybackSession?
    /** Replace the stored session; null clears it. */
    suspend fun write(session: PlaybackSession?)
}

class InMemoryPlaybackSessionStore(private var session: PlaybackSession? = null) : PlaybackSessionStore {
    override suspend fun read(): PlaybackSession? = session
    override suspend fun write(session: PlaybackSession?) {
        this.session = session
    }
}

/** [PlaybackSessionStore] as one JSON value in the `setting` table (key [KEY]); shares the library's driver. */
class SqlPlaybackSessionStore(
    driver: SqlDriver,
    private val ioContext: CoroutineContext = Dispatchers.IO,
) : PlaybackSessionStore {
    private val queries = WavesDatabase(driver).settingQueries

    override suspend fun read(): PlaybackSession? = withContext(ioContext) {
        queries.get(KEY).executeAsOneOrNull()?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { json.decodeFromString(PlaybackSession.serializer(), it) }.getOrNull() }
    }

    override suspend fun write(session: PlaybackSession?) = withContext(ioContext) {
        queries.put(KEY, session?.let { json.encodeToString(PlaybackSession.serializer(), it) } ?: "")
    }

    companion object {
        const val KEY = "playbackSession"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
