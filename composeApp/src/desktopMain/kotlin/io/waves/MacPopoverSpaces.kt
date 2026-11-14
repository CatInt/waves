package io.waves

import com.sun.jna.Callback
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import io.waves.player.ObjC
import java.util.concurrent.ConcurrentLinkedQueue
import javax.swing.SwingUtilities

/**
 * Lets the menu bar popover open over a full-screen app, the way the system's own menu bar dropdowns do. Clicked from a
 * full-screen Space, a window of Waves (an app with a Dock icon) went to Waves' desktop instead, out of sight, as
 * bringing it forward made Waves the active app and macOS followed it there. The popover is an NSPanel (AWT makes one
 * for a utility window); this makes it a non-activating panel allowed on a full-screen app's Space, put in front there
 * without activating Waves, and tells when the user moves to another Space, where the system's dropdowns close. AWT
 * has no say in any of it.
 */
internal object MacPopoverSpaces {
    private val pending = ConcurrentLinkedQueue<String>()

    /**
     * Called on the AWT event thread when the user has moved to another Space (desktop or full-screen app). The window
     * stays on the Space it was shown on, so it slides away with it; this is the moment to close it.
     */
    @Volatile var onSpaceChanged: (() -> Unit)? = null

    /** Shows the utility window titled [title] over whatever Space is active, without activating Waves. */
    fun show(title: String) {
        if (!isMac) return
        pending += title
        Native.dispatch()
    }

    private val isMac = System.getProperty("os.name").orEmpty().startsWith("Mac")

    private fun interface DispatchImp : Callback {
        fun invoke(context: Pointer?)
    }

    /** `-(void)spaceChanged:(NSNotification *)note` */
    private fun interface NotificationImp : Callback {
        fun invoke(self: Pointer?, cmd: Pointer?, note: Pointer?)
    }

    /** Loaded on macOS only. Process-wide, so its callbacks are never collected. */
    private object Native {
        private val libSystem = NativeLibrary.getInstance("System")
        private var observing = false

        private val spaceChanged = NotificationImp { _, _, _ ->
            onSpaceChanged?.let { SwingUtilities.invokeLater(it) }
        }

        private val apply = DispatchImp {
            runCatching {
                ObjC.autoreleasePool {
                    observeSpaces()
                    while (true) {
                        val title = pending.poll() ?: break
                        val windows = ObjC.send(ObjC.send(ObjC.cls("NSApplication"), "sharedApplication"), "windows")
                        for (i in 0 until ObjC.sendLong(windows, "count")) {
                            val window = ObjC.send(windows, "objectAtIndex:", i)
                            if (ObjC.stringValue(ObjC.send(window, "title")) != title) continue
                            ObjC.send(window, "setStyleMask:", ObjC.sendLong(window, "styleMask") or NONACTIVATING_PANEL)
                            // The style alone doesn't reach the window server once the window exists.
                            if (ObjC.sendLong(window, "respondsToSelector:", ObjC.sel("_setPreventsActivation:")) and 0xff != 0L) {
                                ObjC.send(window, "_setPreventsActivation:", 1)
                            }
                            ObjC.send(window, "setCollectionBehavior:", BEHAVIOR)
                            ObjC.send(window, "setLevel:", POP_UP_MENU_LEVEL)
                            ObjC.send(window, "orderFrontRegardless")
                            ObjC.send(window, "makeKeyWindow")
                        }
                    }
                }
            }
        }

        private fun observeSpaces() {
            if (observing) return
            observing = true
            val cls = ObjC.defineClass("WavesSpaceObserver", listOf("spaceChanged:"), spaceChanged, "v@:@")
            val observer = ObjC.send(ObjC.send(cls, "alloc"), "init") // kept for the process lifetime
            val center = ObjC.send(ObjC.send(ObjC.cls("NSWorkspace"), "sharedWorkspace"), "notificationCenter")
            ObjC.send(
                center, "addObserver:selector:name:object:",
                observer, ObjC.sel("spaceChanged:"), ObjC.string("NSWorkspaceActiveSpaceDidChangeNotification"), null,
            )
        }

        // NSWindow wants its main thread, which isn't AWT's event thread.
        fun dispatch() {
            val mainQueue = libSystem.getGlobalVariableAddress("_dispatch_main_q")
            libSystem.getFunction("dispatch_async_f").invokeVoid(arrayOf(mainQueue, null, apply))
        }
    }

    private const val NONACTIVATING_PANEL = 1L shl 7 // NSWindowStyleMaskNonactivatingPanel

    // NSWindowCollectionBehaviorManaged | IgnoresCycle | FullScreenAuxiliary: on the Space it's shown on, a full-screen
    // app's too, and only there (joining every Space, it showed on the next one until macOS said the user had moved).
    private const val BEHAVIOR = (1L shl 2) or (1L shl 6) or (1L shl 8)

    private const val POP_UP_MENU_LEVEL = 101L // NSPopUpMenuWindowLevel
}
