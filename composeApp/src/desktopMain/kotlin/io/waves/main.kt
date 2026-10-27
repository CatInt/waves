package io.waves

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.waves.player.JavaFxRuntime
import io.waves.player.MacNowPlaying
import io.waves.player.currentCoverArt
import io.waves.refresh.DesktopRefresh
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Desktop
import java.awt.Frame
import java.awt.desktop.AppReopenedListener
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

private const val SHUTDOWN_TIMEOUT_MS = 5_000L

fun main() {
    var quit: () -> Unit = {}
    val graph = createDesktopAppGraph(quitApp = { SwingUtilities.invokeLater { quit() } })
    graph.start()
    // Background feed refresh (on start, then hourly) sharing the app's database and cached HTTP client.
    // Pauses while offline; episodes a feed no longer lists are reported so their local audio can be cleaned up.
    val refresher = DesktopRefresh.createRefresher(graph.library, graph.feeds)
    refresher.onEpisodesAbsent = graph::noteEpisodesAbsent
    val refresh = DesktopRefresh.start(
        graph.library,
        refresher = refresher,
        enabled = graph.offline.map { !it },
        initiallyEnabled = !graph.offline.value,
    )
    var shutDown = false
    // Runs on the EDT; nothing in shutdown needs the EDT (DB writes run on Dispatchers.IO), so blocking is safe.
    fun shutdown() {
        if (shutDown) return
        shutDown = true
        refresh.stop()
        runBlocking { withTimeoutOrNull(SHUTDOWN_TIMEOUT_MS) { graph.shutdown() } }
        if (JavaFxRuntime.isStarted) javafx.application.Platform.exit()
    }

    quit = {
        shutdown()
        exitProcess(0)
    }

    // Cmd+Q / Dock "Quit" bypass the window close request; route them through the same shutdown.
    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_QUIT_HANDLER)) {
        Desktop.getDesktop().setQuitHandler { _, response ->
            shutdown()
            response.performQuit()
        }
    }

    // On macOS closing the window only hides it: playback, the menu bar item and Now Playing carry on, and the
    // Dock icon, the menu bar popover or Cmd+Q bring it back or quit. Elsewhere closing the window quits.
    val keepRunningWhenClosed = System.getProperty("os.name").orEmpty().startsWith("Mac")
    var mainVisible by mutableStateOf(true)
    var mainWindow: ComposeWindow? = null
    fun showMain() {
        mainVisible = true
        mainWindow?.apply {
            if (extendedState and Frame.ICONIFIED != 0) extendedState = extendedState and Frame.ICONIFIED.inv()
            isVisible = true
            toFront()
            requestFocus()
        }
    }
    if (keepRunningWhenClosed && Desktop.isDesktopSupported()) {
        Desktop.getDesktop().addAppEventListener(AppReopenedListener { showMain() })
    }

    application {
        val session by graph.session.collectAsState()
        val close = {
            if (keepRunningWhenClosed) {
                mainVisible = false
            } else {
                shutdown()
                exitApplication()
            }
        }
        Window(
            onCloseRequest = close,
            visible = mainVisible,
            title = "Waves",
            icon = appIcon,
            state = rememberWindowState(width = 1100.dp, height = 720.dp),
            onKeyEvent = { event ->
                val closeShortcut = event.type == KeyEventType.KeyDown && event.isMetaPressed && event.key == Key.W
                closeShortcut.also { if (it) close() }
            },
        ) {
            LaunchedEffect(window) { mainWindow = window }
            session?.let { App(it.deps, it.state) }
        }
        session?.let { s ->
            val episodeCover = remember(s) { currentCoverArt(s.state.playback, graph.http, graph.appScope) }
            DisposableEffect(s) {
                val nowPlaying = MacNowPlaying.start(s.state.playback, episodeCover, graph.appScope)
                onDispose { nowPlaying?.stop() }
            }
            // The show's own cover, as the show page's collapsed chip has; episode artwork is often a wide still.
            val showCover = remember(s) { currentCoverArt(s.state.playback, graph.http, graph.appScope, preferShow = true) }
            MenuBar(s.state, showCover, onOpenApp = ::showMain, onQuit = { quit() })
        }
    }
    shutdown()
    exitProcess(0)
}
