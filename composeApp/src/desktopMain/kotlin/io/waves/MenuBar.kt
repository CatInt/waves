package io.waves

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import io.waves.player.CoverArt
import io.waves.ui.AppState
import io.waves.ui.LocalWheelGesture
import io.waves.ui.MenuBarPanel
import io.waves.ui.WavesTheme
import io.waves.ui.WheelGesture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
/** Unseen (the popover has no title bar), but how [MacPopoverSpaces] finds its window. */
private const val POPOVER_TITLE = "Waves menu"
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
internal fun MenuBar(
    app: AppState,
    cover: StateFlow<CoverArt?>,
    onOpenApp: () -> Unit,
    onQuit: () -> Unit,
    /** The trackpad gesture behind scroll events, so two fingers pull the popover's list down as a finger would. */
    wheelGesture: StateFlow<WheelGesture>? = null,
) {
    if (!menuBarSupported) return
    var popover by remember { mutableStateOf<Point?>(null) }
    val dismissedAt = remember { longArrayOf(0L) }
    val tray = remember { MenuBarTray.icon }

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
        MenuBarTray.show()
        onDispose {
            tray.removeMouseListener(listener)
            SystemTray.getSystemTray().remove(tray)
        }
    }

    LaunchedEffect(tray) {
        val pillCover = cover.mapLatest { art -> art?.let { withContext(Dispatchers.Default) { PillCover.decode(it.bytes) } } }
        combine(app.playback.state, pillCover) { s, artwork ->
            val loaded = s.episode != null
            PillContent(cover = artwork.takeIf { loaded }, dimmed = loaded && !s.isPlaying, dev = DevBuild)
        }
            .distinctUntilChanged()
            .collect { tray.image = MenuBarPill.image(it) }
    }

    popover?.let { position ->
        Window(
            onKeyEvent = { event ->
                (event.key == Key.Escape).also { if (it) popover = null }
            },
            create = { popoverWindow(position, onClose = { popover = null }) },
            dispose = ComposeWindow::dispose,
        ) {
            DisposableEffect(window) {
                val focus = object : WindowAdapter() {
                    override fun windowLostFocus(e: WindowEvent) {
                        dismissedAt[0] = System.currentTimeMillis()
                        popover = null
                    }
                }
                window.addWindowFocusListener(focus)
                // Like the system's dropdowns, it goes when the user moves to another desktop or full-screen app.
                MacPopoverSpaces.onSpaceChanged = { popover = null }
                // In front over whatever Space is showing, a full-screen app's too, without making Waves the active
                // app (AWT's toFront would, and macOS would follow it to Waves' desktop).
                MacPopoverSpaces.show(POPOVER_TITLE)
                onDispose {
                    window.removeWindowFocusListener(focus)
                    MacPopoverSpaces.onSpaceChanged = null
                }
            }
            WavesTheme(app.artworkColors) {
                CompositionLocalProvider(LocalWheelGesture provides (wheelGesture ?: NoWheelGesture)) {
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
}

/**
 * The menu bar item: the cover pill, and where new-episode banners are posted from, so Waves keeps a single item in
 * the menu bar. Touch on the AWT event thread.
 */
internal object MenuBarTray {
    val icon: TrayIcon by lazy {
        TrayIcon(MenuBarPill.image(PillContent(dev = DevBuild))).apply {
            isImageAutoSize = false
            toolTip = if (DevBuild) "Waves (development build)" else "Waves"
        }
    }

    /** Puts the item in the menu bar if it isn't there yet. */
    fun show() {
        val tray = SystemTray.getSystemTray()
        if (icon !in tray.trayIcons) tray.add(icon)
    }

    /**
     * The item to post a banner from, put up first if a banner comes before the item does (a refresh finding new
     * episodes as the app starts); null off macOS, where Waves has no menu bar item.
     */
    fun forBanners(): TrayIcon? {
        if (!menuBarSupported) return null
        show()
        return icon
    }
}

/** Below the menu bar, centred on the click and kept on the clicked screen. */
private fun panelPosition(click: Point): Point {
    val config = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
        .map { it.defaultConfiguration }
        .firstOrNull { it.bounds.contains(click) }
    val width = PanelSize.width.value.toInt()
    if (config == null) return Point(click.x - width / 2, click.y + PANEL_GAP)
    val bounds = config.bounds
    val insets = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(config)
    val x = (click.x - width / 2).coerceIn(bounds.x + PANEL_GAP, bounds.x + bounds.width - width - PANEL_GAP)
    val y = bounds.y + insets.top + PANEL_GAP
    return Point(x, y)
}

/**
 * The popover's window: a utility window, which AWT makes an NSPanel, the kind of window that can show over a
 * full-screen app (see [MacPopoverSpaces]). Built by hand, as the window type must be set before AWT creates it.
 */
private fun popoverWindow(position: Point, onClose: () -> Unit) = ComposeWindow().apply {
    type = java.awt.Window.Type.UTILITY
    title = POPOVER_TITLE
    isUndecorated = true
    isTransparent = true
    isResizable = false
    isAlwaysOnTop = true
    setBounds(position.x, position.y, PanelSize.width.value.toInt(), PanelSize.height.value.toInt())
    defaultCloseOperation = javax.swing.WindowConstants.DO_NOTHING_ON_CLOSE
    addWindowListener(object : WindowAdapter() {
        override fun windowClosing(e: WindowEvent) = onClose()
    })
}

/** Off macOS, or before AppKit says: wheel events alone, as for a mouse. */
private val NoWheelGesture: StateFlow<WheelGesture> = MutableStateFlow(WheelGesture.Unknown)

/** A build without a release version: its menu bar item wears the window's "DEV" corner. */
private val DevBuild = !BuildInfo.IS_RELEASE
