package io.waves.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Lets a two-finger (wheel) drag keep its gesture wherever the pointer goes, as a finger drag keeps the touch it
 * started with: scroll events land on whatever is under the pointer, so a sheet sliding out from under it would
 * otherwise lose them to the page behind. [owner] sees every scroll event in the window first and says whether it
 * took it.
 */
internal class WheelCapture {
    var owner: ((PointerInputChange) -> Boolean)? = null
}

internal val LocalWheelCapture = staticCompositionLocalOf<WheelCapture?> { null }

/** On the window's root, ahead of everything in it: offers each scroll event to [capture]'s owner. */
internal fun Modifier.wheelCaptureRoot(capture: WheelCapture): Modifier = pointerInput(capture) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type != PointerEventType.Scroll) continue
            val change = event.changes.firstOrNull()?.takeIf { !it.isConsumed } ?: continue
            if (capture.owner?.invoke(change) == true) change.consume()
        }
    }
}
