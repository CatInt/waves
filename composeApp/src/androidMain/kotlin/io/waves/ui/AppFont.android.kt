package io.waves.ui

import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight

private val SansSerif = DeviceFontFamilyName("sans-serif")

/**
 * The system declares sans-serif only at whole hundreds, so a weight like 350 would snap to 300 or 400. Setting the
 * variable font's weight axis renders the exact weight (devices without a variable sans-serif get the nearest).
 */
internal actual val AppFontFamily: FontFamily = FontFamily(
    (100..900 step 50).map { w ->
        Font(SansSerif, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    },
)
