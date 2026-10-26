package io.waves.data

import app.cash.sqldelight.db.SqlDriver
import io.waves.data.db.WavesDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** When show pages last fetched each feed (epoch ms). Local only (not synced). */
interface FeedFetchLog {
    suspend fun fetchedAt(feedUrl: String): Long?
    suspend fun record(feedUrl: String, epochMs: Long)
}

class InMemoryFeedFetchLog : FeedFetchLog {
    private val times = MutableStateFlow<Map<String, Long>>(emptyMap())
    override suspend fun fetchedAt(feedUrl: String): Long? = times.value[feedUrl]
    override suspend fun record(feedUrl: String, epochMs: Long) = times.update { it + (feedUrl to epochMs) }
}

/** [FeedFetchLog] in the `feed_fetch` table; shares the library's driver. */
class SqlFeedFetchLog(
    driver: SqlDriver,
    private val ioContext: CoroutineContext = Dispatchers.IO,
) : FeedFetchLog {
    private val queries = WavesDatabase(driver).feedFetchQueries

    override suspend fun fetchedAt(feedUrl: String): Long? =
        withContext(ioContext) { queries.fetchedAt(feedUrl).executeAsOneOrNull() }

    override suspend fun record(feedUrl: String, epochMs: Long) {
        withContext(ioContext) { queries.put(feedUrl, epochMs) }
    }
}
