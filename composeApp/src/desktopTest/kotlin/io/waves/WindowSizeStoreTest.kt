package io.waves

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class WindowSizeStoreTest {
    private val dir = createTempDirectory("window-size").toFile()
    private val file = File(dir, "window.properties")

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun aFirstLaunchOpensAtTheDefaultSize() {
        assertEquals(DpSize(960.dp, 720.dp), DefaultWindowSize)
        assertEquals(DefaultWindowSize, WindowSizeStore(file).load(screen = null))
    }

    @Test
    fun theNextLaunchOpensAtTheSizeKept() {
        WindowSizeStore(file).save(DpSize(1234.5.dp, 678.dp))
        assertEquals(DpSize(1234.5.dp, 678.dp), WindowSizeStore(file).load(screen = null))
    }

    @Test
    fun aSizeKeptOnABiggerScreenIsCutDownToFit() {
        WindowSizeStore(file).save(DpSize(2400.dp, 1300.dp))
        assertEquals(DpSize(1512.dp, 900.dp), WindowSizeStore(file).load(screen = DpSize(1512.dp, 900.dp)))
        assertEquals(DpSize(1200.dp, 1300.dp), WindowSizeStore(file).load(screen = DpSize(1200.dp, 1600.dp)))
    }

    @Test
    fun aDamagedOrTinySizeGivesWayToTheDefault() {
        file.writeText("width=wide\nheight=720\n")
        assertEquals(DefaultWindowSize, WindowSizeStore(file).load(screen = null))
        file.writeText("width=40\nheight=30\n")
        assertEquals(DefaultWindowSize, WindowSizeStore(file).load(screen = null))
        file.writeText("height=720\n")
        assertEquals(DefaultWindowSize, WindowSizeStore(file).load(screen = null))
    }
}
