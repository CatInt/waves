package io.waves.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class AppDataDirectoryTest {
    @Test
    fun aDevelopmentBuildKeepsItsDataApartFromTheInstalledApp() {
        val release = appDataDirectory(release = true)
        val dev = appDataDirectory(release = false)
        assertNotEquals(release, dev)
        assertEquals(release.parentFile, dev.parentFile, "side by side")
        assertEquals(dev, defaultDatabaseFile().parentFile, "tests run as a development build")
    }
}
