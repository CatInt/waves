package io.waves

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import io.waves.player.CoverArt
import io.waves.ui.AppState
import io.waves.ui.MenuBarPanel
import io.waves.ui.WavesTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Point
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

private val PanelSize = DpSize(380.dp, 600.dp)
private const val PANEL_GAP = 6
/** A tray click that also took focus from the panel closes it; this keeps that same click from reopening it. */
private const val REOPEN_GUARD_MS = 300L

private val menuBarSupported: Boolean =
    System.getProperty("os.name").orEmpty().startsWith("Mac") && SystemTray.isSupported()

/**
 * macOS menu bar item: a pill of [cover], the playing show's artwork, dimmed while paused. Clicking it toggles a
 * popover with the transport controls and Home's "Up next" list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
internal fun MenuBar(app: AppState, cover: StateFlow<CoverArt?>, onOpenApp: () -> Unit, onQuit: () -> Unit) {
    if (!menuBarSupported) return
    var popover by remember { mutableStateOf<WindowPosition?>(null) }
    val dismissedAt = remember { longArrayOf(0L) }
    val tray = remember {
        TrayIcon(MenuBarPill.image(PillContent())).apply {
            isImageAutoSize = false
            toolTip = "Waves"
        }
    }

    DisposableEffect(tray) {
        val listener = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                when {
                    popover != null -> popover = null
                    System.currentTimeMillis() - dismissedAt[0] > REOPEN_GUARD_MS ->
                        popover = panelPosition(MouseInfo.getPointerInfo()?.location ?: e.locationOnScreen)
                }
            }
        }
        tray.addMouseListener(listener)
        SystemTray.getSystemTray().add(tray)
        onDispose {
            tray.removeMouseListener(listener)
            SystemTray.getSystemTray().remove(tray)
        }
    }

    LaunchedEffect(tray) {
        val pillCover = cover.mapLatest { art -> art?.let { withContext(Dispatchers.Default) { PillCover.decode(it.bytes) } } }
        combine(app.playback.state, pillCover) { s, artwork ->
            val loaded = s.episode != null
            PillContent(cover = artwork.takeIf { loaded }, dimmed = loaded && !s.isPlaying)
        }
            .distinctUntilChanged()
            .collect { tray.image = MenuBarPill.image(it) }
    }

    popover?.let { position ->
        Window(
            onCloseRequest = { popover = null },
            state = rememberWindowState(position = position, size = PanelSize),
            title = "Waves",
            undecorated = true,
            transparent = true,
            resizable = false,
            alwaysOnTop = true,
            onKeyEvent = { event ->
                (event.key == Key.Escape).also { if (it) popover = null }
            },
        ) {
            DisposableEffect(window) {
                val focus = object : WindowAdapter() {
                    override fun windowLostFocus(e: WindowEvent) {
                        dismissedAt[0] = System.currentTimeMillis()
                        popover = null
                    }
                }
                window.addWindowFocusListener(focus)
                window.toFront()
                window.requestFocus()
                onDispose { window.removeWindowFocusListener(focus) }
            }
            WavesTheme(app.artworkColors) {
                MenuBarPanel(
                    app,
                    onOpenApp = {
                        popover = null
                        onOpenApp()
                    },
                    onQuit = {
                        popover = null
                        onQuit()
                    },
                )
            }
        }
    }
}

/** Below the menu bar, centred on the click and kept on the clicked screen. */
private fun panelPosition(click: Point): WindowPosition {
    val config = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
        .map { it.defaultConfiguration }
        .firstOrNull { it.bounds.contains(click) }
    val width = PanelSize.width.value.toInt()
    if (config == null) return WindowPosition((click.x - width / 2).dp, (click.y + PANEL_GAP).dp)
    val bounds = config.bounds
    val insets = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(config)
    val x = (click.x - width / 2).coerceIn(bounds.x + PANEL_GAP, bounds.x + bounds.width - width - PANEL_GAP)
    val y = bounds.y + insets.top + PANEL_GAP
    return WindowPosition(x.dp, y.dp)
}
