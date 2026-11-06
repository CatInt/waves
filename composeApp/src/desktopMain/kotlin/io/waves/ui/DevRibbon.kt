package io.waves.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * "DEV" on a dark ribbon across the window's top-left corner, the one on the Android debug build's icon
 * (`ic_launcher_dev_banner`), so a development build (one without a release version) can't be mistaken for the
 * installed app. Drawn over the screens and taking no clicks: whatever is under it still answers.
 */
@Composable
internal fun BoxScope.DevRibbon() {
    val measurer = rememberTextMeasurer()
    val word = remember(measurer) {
        measurer.measure(
            "DEV",
            TextStyle(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 9.sp, letterSpacing = 1.sp),
            softWrap = false,
            maxLines = 1,
        )
    }
    Canvas(
        Modifier.align(Alignment.TopStart).size(DevRibbonReach).clipToBounds()
            .semantics { contentDescription = "Development build" },
    ) {
        // A band across the corner, through ([middle], [middle]), cut off by the window's edges.
        val middle = DevRibbonMiddle.toPx()
        val thickness = DevRibbonThickness.toPx()
        val length = size.width * 3
        rotate(-45f, pivot = Offset(middle, middle)) {
            drawRect(DevRibbonColor, Offset(middle - length / 2, middle - thickness / 2), Size(length, thickness))
            drawText(word, topLeft = Offset(middle - word.size.width / 2f, middle - word.size.height / 2f))
        }
    }
}

/** The corner the ribbon may cover: clear of the show page's Back button. */
private val DevRibbonReach = 48.dp
/** How far in from each edge the band's middle crosses the corner's diagonal. */
private val DevRibbonMiddle = 16.dp
private val DevRibbonThickness = 14.dp

/** The Android debug icon's ribbon colour. */
internal val DevRibbonColor = Color(0xFF1A2238)
