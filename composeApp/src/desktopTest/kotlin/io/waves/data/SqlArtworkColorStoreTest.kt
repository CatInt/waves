package io.waves.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.waves.data.db.WavesDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SqlArtworkColorStoreTest {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { WavesDatabase.Schema.create(it) }

    @AfterTest fun close() = driver.close()

    @Test fun pickedColoursAreKeptAcrossLaunches() = runBlocking {
        val store = SqlArtworkColorStore(driver, Dispatchers.Unconfined)
        assertNull(store.get("https://a/cover.jpg"))
        store.put("https://a/cover.jpg", 0xFF3D8BD4.toInt())
        store.put("https://b/cover.png", 0x80FF0000.toInt())
        assertEquals(0xFF3D8BD4.toInt(), store.get("https://a/cover.jpg"))

        val relaunched = SqlArtworkColorStore(driver, Dispatchers.Unconfined)
        assertEquals(0xFF3D8BD4.toInt(), relaunched.get("https://a/cover.jpg"))
        assertEquals(0x80FF0000.toInt(), relaunched.get("https://b/cover.png"))
        assertNull(relaunched.get("https://c/cover.jpg"))
    }
}
