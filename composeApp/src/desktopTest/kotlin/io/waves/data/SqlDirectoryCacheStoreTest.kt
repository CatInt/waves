package io.waves.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.waves.data.db.WavesDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SqlDirectoryCacheStoreTest {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { WavesDatabase.Schema.create(it) }

    @AfterTest fun close() = driver.close()

    @Test fun roundTripsUnderItsOwnPrefix() = runBlocking {
        val store = SqlDirectoryCacheStore(driver, Dispatchers.IO)
        assertNull(store.read("chart-us-100"))
        store.write("chart-us-100", "{}")
        store.write("chart-us-100", "[1]")
        assertEquals("[1]", SqlDirectoryCacheStore(driver, Dispatchers.IO).read("chart-us-100"))
        assertEquals("[1]", WavesDatabase(driver).settingQueries.get("directory:chart-us-100").executeAsOne())
        assertNull(store.read(SqlSettingsStore.KEY))
    }
}
