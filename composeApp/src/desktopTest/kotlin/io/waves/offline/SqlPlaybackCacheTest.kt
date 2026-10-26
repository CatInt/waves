package io.waves.offline

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.waves.data.db.WavesDatabase
import io.waves.model.EpisodeId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqlPlaybackCacheTest {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { WavesDatabase.Schema.create(it) }
    private val removed = mutableListOf<Pair<EpisodeId, String?>>()

    @AfterTest fun close() = driver.close()

    private fun cache() = SqlPlaybackCache(driver, Dispatchers.IO) { id, url -> removed += id to url }

    @Test fun roundTripAndRemoveNotifiesWithTheStoredUrl() = runBlocking {
        val id = EpisodeId("https://a.example/feed", "g1")
        val store = cache()
        store.upsert(PlaybackCacheEntry(id, "https://cdn/a.mp3", 40, 100, 5))
        assertEquals(listOf(id), store.entries.first().map { it.episodeId })
        assertTrue(!store.entries.first().single().isFullyCached)

        store.upsert(PlaybackCacheEntry(id, "https://cdn/a.mp3", 100, 100, 9))
        val stored = store.entries.first().single()
        assertTrue(stored.isFullyCached)
        assertEquals(9, stored.updatedAtEpochMs)

        store.remove(id)
        assertEquals(emptyList(), store.entries.first())
        assertEquals(id to "https://cdn/a.mp3", removed.single())
    }

    @Test fun removeOfUnknownEpisodeStillNotifies() = runBlocking {
        val id = EpisodeId("https://a.example/feed", "missing")
        cache().remove(id)
        assertNull(removed.single().second)
        assertEquals(id, removed.single().first)
    }
}
