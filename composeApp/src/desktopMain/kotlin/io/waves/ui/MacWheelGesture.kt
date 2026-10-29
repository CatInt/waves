package io.waves.ui

import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import io.waves.player.ObjC
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The trackpad gesture behind scroll events, read from AppKit: AWT's wheel events drop `NSEvent.phase` and
 * `momentumPhase`, so nothing else tells fingers resting on the trackpad from fingers lifted. A local event monitor
 * sees each scroll event before AWT turns it into a wheel event, so the state is current by the time Compose gets it.
 */
internal object MacWheelGesture {
    private val state = MutableStateFlow(WheelGesture.Unknown)
    private var started = false

    /** The gesture state, watched from the first call on; null off macOS. */
    fun start(): StateFlow<WheelGesture>? {
        if (!System.getProperty("os.name").orEmpty().startsWith("Mac")) return null
        synchronized(this) {
            if (!started) {
                started = true
                Native.install()
            }
        }
        return state.asStateFlow()
    }

    fun gestureOf(phase: Long, momentumPhase: Long): WheelGesture = when {
        momentumPhase and (PHASE_ENDED or PHASE_CANCELLED) != 0L -> WheelGesture.Lifted
        momentumPhase != 0L -> WheelGesture.Momentum
        phase and (PHASE_ENDED or PHASE_CANCELLED) != 0L -> WheelGesture.Lifted
        phase != 0L -> WheelGesture.Touching
        else -> WheelGesture.Unknown
    }

    /** `NSEvent *(^)(NSEvent *)`: notes the event's phase and passes the event on untouched. */
    private fun interface MonitorImp : Callback {
        fun invoke(block: Pointer?, event: Pointer?): Pointer?
    }

    private fun interface DispatchImp : Callback {
        fun invoke(context: Pointer?)
    }

    /** Loaded on macOS only. Process-wide, so its memory and callbacks are never collected. */
    private object Native {
        private val libSystem = NativeLibrary.getInstance("System")
        private var monitor: Pointer? = null

        private val handler = MonitorImp { _, event ->
            if (event != null) {
                runCatching { state.value = gestureOf(ObjC.sendLong(event, "phase"), ObjC.sendLong(event, "momentumPhase")) }            }
            event
        }

        private val descriptor = Memory(16).apply {
            setLong(0, 0) // reserved
            setLong(8, 32) // sizeof(block literal)
        }

        /** [handler] as a hand-built global block (no captures, so copying it hands back the same block). */
        private val block = Memory(32).apply {
            setPointer(0, libSystem.getGlobalVariableAddress("_NSConcreteGlobalBlock"))
            setInt(8, BLOCK_IS_GLOBAL)
            setInt(12, 0)
            setPointer(16, CallbackReference.getFunctionPointer(handler))
            setPointer(24, descriptor)
        }

        private val addMonitor = DispatchImp {
            ObjC.autoreleasePool {
                monitor = ObjC.send(ObjC.cls("NSEvent"), "addLocalMonitorForEventsMatchingMask:handler:", SCROLL_WHEEL_MASK, block)
                    ?.also { ObjC.send(it, "retain") }            }
        }

        // AppKit wants monitors added on its main thread, which isn't AWT's event thread.
        fun install() {
            val mainQueue = libSystem.getGlobalVariableAddress("_dispatch_main_q")
            libSystem.getFunction("dispatch_async_f").invokeVoid(arrayOf(mainQueue, null, addMonitor))
        }
    }

    private const val SCROLL_WHEEL_MASK = 1L shl 22 // NSEventMaskScrollWheel
    private const val PHASE_ENDED = 1L shl 3 // NSEventPhaseEnded
    private const val PHASE_CANCELLED = 1L shl 4 // NSEventPhaseCancelled
    private const val BLOCK_IS_GLOBAL = 1 shl 28
}
