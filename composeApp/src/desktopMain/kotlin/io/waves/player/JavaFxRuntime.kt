package io.waves.player

import javafx.application.Platform
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Boots the JavaFX toolkit (needed by javafx.scene.media) once per process, alongside Compose/AWT.
 * No JavaFX window is ever shown; the toolkit only provides the media pipeline and its event thread.
 *
 * On macOS, AWT must own the NSApplication: JavaFX's glass layer detects an existing AWT app and runs
 * inside AWT's run loop. Compose has normally initialized AWT long before the first play(), but we
 * force it anyway so the order never depends on call timing.
 */
internal object JavaFxRuntime {
    private const val STARTUP_TIMEOUT_SECONDS = 15L

    @Volatile
    var isStarted = false
        private set

    @Volatile
    private var failure: Throwable? = null

    /** Starts the toolkit or throws if it can't (e.g. no window server); subsequent calls rethrow fast. */
    @Synchronized
    fun ensureStarted() {
        if (isStarted) return
        failure?.let { throw it }
        if (!GraphicsEnvironment.isHeadless()) Toolkit.getDefaultToolkit()

        val latch = CountDownLatch(1)
        var startupError: Throwable? = null
        // Platform.startup can block indefinitely when no display is reachable, so never call it on the
        // caller's thread.
        Thread({
            try {
                Platform.startup { latch.countDown() }
            } catch (e: IllegalStateException) {
                latch.countDown() // already running in this process
            } catch (t: Throwable) {
                startupError = t
                latch.countDown()
            }
        }, "waves-javafx-startup").apply { isDaemon = true }.start()

        if (!latch.await(STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw IllegalStateException("JavaFX toolkit did not start within ${STARTUP_TIMEOUT_SECONDS}s").also { failure = it }
        }
        startupError?.let { failure = it; throw it }
        Platform.setImplicitExit(false)
        isStarted = true
    }

    /** Runs [block] on the JavaFX application thread (inline if already on it). */
    fun runOnFx(block: () -> Unit) {
        if (Platform.isFxApplicationThread()) block() else Platform.runLater(block)
    }
}
