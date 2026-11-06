package io.waves.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

@OptIn(ExperimentalComposeUiApi::class)
class ZigzagRippleUiTest {
    /**
     * A first row under a divider drawn by the item before it (the show page's pull gap): pressed, its ripple
     * reaches up to the divider but stops at its zigzag, leaving the band above the line, where the zigzag dips,
     * as it was.
     */
    @Test
    fun aFirstRowsRippleStopsAtTheZigzagOfTheDividerAboveIt() {
        val scale = 4f
        val scene = ImageComposeScene(width = 480, height = 240, density = Density(scale)) {
            MaterialTheme {
                Column(Modifier.fillMaxSize().background(Color.White)) {
                    Box(Modifier.fillMaxWidth().height(ListDividerHeight).topDivider(true))
                    Box(Modifier.fillMaxWidth().zigzagClickable(top = false, bottom = false, above = true) {}) {
                        Spacer(Modifier.fillMaxWidth().height(40.dp))
                    }
                }
            }
        }
        try {
            fun frame(ms: Long): PixelMap = scene.render(ms * 1_000_000).toComposeImageBitmap().toPixelMap()
            val before = frame(0)
            val finger = Offset(240f, 120f)
            scene.sendPointerEvent(PointerEventType.Enter, finger, type = PointerType.Touch)
            scene.sendPointerEvent(PointerEventType.Press, finger, type = PointerType.Touch)
            // Frame by frame, as on screen, so the ripple's animations run.
            for (t in 1..60) frame(t * 16L)
            val pressed = frame(1_000)

            // Two whole periods in, where the zigzag dips to its lowest.
            val dip = (24 * scale).toInt()
            val inside = ((ListDividerHeight.value + 20) * scale).toInt()
            assertNotEquals(before[dip, inside], pressed[dip, inside], "the row shows the press")
            val aboveLine = (1.5f * scale).toInt()
            assertEquals(before[dip, aboveLine], pressed[dip, aboveLine], "above the divider's line")
        } finally {
            scene.close()
        }
    }
}
