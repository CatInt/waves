package io.waves.data

import app.cash.sqldelight.db.SqlDriver
import io.waves.data.db.WavesDatabase
import io.waves.search.DirectoryCacheStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** [DirectoryCacheStore] in the `setting` table, under [PREFIX]ed keys so they can't clash with settings. */
class SqlDirectoryCacheStore(
    driver: SqlDriver,
    private val ioContext: CoroutineContext = Dispatchers.IO,
) : DirectoryCacheStore {
    private val queries = WavesDatabase(driver).settingQueries

    override suspend fun read(key: String): String? =
        withContext(ioContext) { queries.get(PREFIX + key).executeAsOneOrNull() }

    override suspend fun write(key: String, value: String) {
        withContext(ioContext) { queries.put(PREFIX + key, value) }
    }

    companion object {
        const val PREFIX = "directory:"
    }
}
