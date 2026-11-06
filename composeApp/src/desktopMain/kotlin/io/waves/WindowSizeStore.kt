package io.waves

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import io.waves.data.appDataDirectory
import java.awt.GraphicsEnvironment
import java.io.File
import java.util.Properties

/** The main window's size on a first launch, before any size has been kept. */
val DefaultWindowSize = DpSize(960.dp, 720.dp)

/** The smallest size kept: a size below it (a damaged file) gives way to [DefaultWindowSize]. */
private val SmallestKeptSize = DpSize(200.dp, 200.dp)

/**
 * The main window's size between launches, in `window.properties` in [appDataDirectory] (a development build
 * keeps its own, as it does its library). Only this device's: it isn't synced.
 */
class WindowSizeStore(private val file: File = File(appDataDirectory(), "window.properties")) {
    /**
     * The kept size, cut down to fit [screen] (the space for windows on the screen, when known) so a size kept on
     * a bigger display still fits this one; [DefaultWindowSize] when nothing (usable) is kept.
     */
    fun load(screen: DpSize? = usableScreen()): DpSize {
        val kept = runCatching {
            if (!file.isFile) return@runCatching null
            val p = Properties().apply { file.inputStream().use { load(it) } }
            val width = p.getProperty("width")?.toFloatOrNull() ?: return@runCatching null
            val height = p.getProperty("height")?.toFloatOrNull() ?: return@runCatching null
            DpSize(width.dp, height.dp).takeIf { it.width >= SmallestKeptSize.width && it.height >= SmallestKeptSize.height }
        }.getOrNull() ?: DefaultWindowSize
        if (screen == null) return kept
        return DpSize(minOf(kept.width, screen.width), minOf(kept.height, screen.height))
    }

    /** Keeps [size] for the next launch; a failed write only means the next launch opens at the old size. */
    fun save(size: DpSize) {
        if (!size.width.value.isFinite() || !size.height.value.isFinite()) return
        runCatching {
            file.parentFile?.mkdirs()
            val p = Properties().apply {
                setProperty("width", size.width.value.toString())
                setProperty("height", size.height.value.toString())
            }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.outputStream().use { p.store(it, "Waves main window") }
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    private companion object {
        /** The main screen less the menu bar and Dock, in points (which are dp on macOS); null when headless. */
        fun usableScreen(): DpSize? = runCatching {
            val bounds = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
            DpSize(bounds.width.dp, bounds.height.dp)
        }.getOrNull()
    }
}
