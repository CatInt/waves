package io.waves.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.waves.data.db.WavesDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SqlFeedFetchLogTest {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { WavesDatabase.Schema.create(it) }

    @AfterTest fun close() = driver.close()

    @Test fun fetchTimesAreKeptAcrossLaunches() = runBlocking {
        val log = SqlFeedFetchLog(driver, Dispatchers.Unconfined)
        assertNull(log.fetchedAt("https://a/feed"))
        log.record("https://a/feed", 1_000)
        log.record("https://a/feed", 2_000)

        val relaunched = SqlFeedFetchLog(driver, Dispatchers.Unconfined)
        assertEquals(2_000, relaunched.fetchedAt("https://a/feed"))
        assertNull(relaunched.fetchedAt("https://b/feed"))
    }
}
