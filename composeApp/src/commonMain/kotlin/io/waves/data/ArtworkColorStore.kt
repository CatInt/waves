package io.waves.data

import app.cash.sqldelight.db.SqlDriver
import io.waves.data.db.WavesDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Colours picked from cover art (ARGB), by image URL. A colour is stored once its image has loaded and is kept
 * for good: the same URL is never analysed again. Local only (not synced).
 */
interface ArtworkColorStore {
    /** The stored colour for [url], or null when none has been picked yet. Never blocks. */
    fun get(url: String): Int?
    suspend fun put(url: String, argb: Int)
}

class InMemoryArtworkColorStore : ArtworkColorStore {
    private val colors = MutableStateFlow<Map<String, Int>>(emptyMap())
    override fun get(url: String): Int? = colors.value[url]
    override suspend fun put(url: String, argb: Int) = colors.update { it + (url to argb) }
}

/**
 * [ArtworkColorStore] in the `artwork_color` table; shares the library's driver. Every stored colour is read
 * synchronously at construction so [get] answers from memory, and covers are tinted from their first frame.
 */
class SqlArtworkColorStore(
    driver: SqlDriver,
    private val ioContext: CoroutineContext = Dispatchers.IO,
) : ArtworkColorStore {
    private val queries = WavesDatabase(driver).artworkColorQueries
    private val colors = MutableStateFlow(queries.all().executeAsList().associate { it.url to it.argb.toInt() })

    override fun get(url: String): Int? = colors.value[url]

    override suspend fun put(url: String, argb: Int) {
        if (colors.value[url] == argb) return
        colors.update { it + (url to argb) }
        withContext(ioContext) { queries.put(url, argb.toLong()) }
    }
}
