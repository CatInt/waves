package io.waves.refresh

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PropertiesRefreshStateStoreTest {
    @Test
    fun persistsAcrossInstances() = runBlocking {
        val dir = Files.createTempDirectory("waves-refresh").toFile()
        val file = File(dir, "sub/refresh-state.properties")
        val store = PropertiesRefreshStateStore(file)
        assertNull(store.lastRefreshedAt("https://x/feed?a=1&b=2"))
        store.setLastRefreshedAt("https://x/feed?a=1&b=2", 42L)
        store.setLastRefreshedAt("https://y/feed", 7L)

        val reopened = PropertiesRefreshStateStore(file)
        assertEquals(42L, reopened.lastRefreshedAt("https://x/feed?a=1&b=2"))
        assertEquals(7L, reopened.lastRefreshedAt("https://y/feed"))
        dir.deleteRecursively()
        Unit
    }
}
