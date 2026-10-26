package io.waves.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.waves.data.db.WavesDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SqlSettingsStoreTest {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { WavesDatabase.Schema.create(it) }
    private val queries = WavesDatabase(driver).settingQueries

    @AfterTest fun close() = driver.close()

    private fun store() = SqlSettingsStore(driver, Dispatchers.IO)

    @Test fun defaultsWhenNothingStored() {
        assertEquals(UserSettings(), store().settings.value)
    }

    @Test fun roundTrip() = runBlocking {
        val changed = UserSettings(
            country = "gb", defaultSpeed = 1.25f, skipBackSeconds = 10, skipForwardSeconds = 45,
            autoDeleteDownloads = true, autoDownloadNewEpisodes = true, syncEnabled = false,
        )
        val first = store()
        first.update { changed }
        assertEquals(changed, first.settings.value)
        assertEquals(changed, store().settings.value, "a new store on the same database reads it back")

        first.update { it.copy(country = null) }
        assertEquals(changed.copy(country = null), store().settings.value)
    }

    @Test fun storedAsOneJsonValueAlongsideOtherSettings() = runBlocking {
        val repo = SqlLibraryRepository(driver, Dispatchers.IO)
        val deviceId = repo.deviceId()
        store().update { it.copy(skipBackSeconds = 5) }
        val json = queries.get(SqlSettingsStore.KEY).executeAsOne()
        assertTrue(json.startsWith("{") && "\"skipBackSeconds\":5" in json, json)
        assertEquals(deviceId, repo.deviceId())
    }

    @Test fun unknownKeysAreIgnoredAndMissingOnesDefault() {
        queries.put(SqlSettingsStore.KEY, """{"skipForwardSeconds":60,"someFutureSetting":"x"}""")
        assertEquals(UserSettings(skipForwardSeconds = 60), store().settings.value)
    }

    @Test fun malformedValueFallsBackToDefaults() = runBlocking {
        queries.put(SqlSettingsStore.KEY, "not json")
        val s = store()
        assertEquals(UserSettings(), s.settings.value)
        s.update { it.copy(syncEnabled = false) }
        assertEquals(UserSettings(syncEnabled = false), store().settings.value)
    }
}
