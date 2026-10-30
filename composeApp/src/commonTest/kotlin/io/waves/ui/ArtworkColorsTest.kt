package io.waves.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArtworkColorsTest {
    private fun pixels(vararg parts: Pair<Color, Int>): IntArray =
        parts.flatMap { (color, n) -> List(n) { color.toArgb() } }.toIntArray()

    private fun hueOf(color: Color) = Oklch.of(color).h

    private fun hueDistance(a: Float, b: Float) = abs(a - b).let { min(it, 360f - it) }

    private fun contrast(a: Color, b: Color): Float {
        val la = a.luminance(); val lb = b.luminance()
        return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
    }

    @Test
    fun oklabRoundTrips() {
        for (c in listOf(Color.Red, Color(0xFF1E88E5), Color(0xFFFFD54F), Color.White, Color.Black)) {
            val back = Oklab.of(c).toColor()
            assertEquals(c.red, back.red, 0.01f)
            assertEquals(c.green, back.green, 0.01f)
            assertEquals(c.blue, back.blue, 0.01f)
        }
    }

    @Test
    fun aVividColourBeatsALargerMutedOne() {
        val mutedTeal = Color(0xFF4F7472)
        val red = Color(0xFFE53935)
        val seed = dominantColor(pixels(mutedTeal to 800, red to 60, Color.White to 140))
        assertTrue(hueDistance(hueOf(seed), hueOf(red)) < 10f, "seed $seed")
    }

    @Test
    fun aVividSpeckDoesNotWin() {
        val mutedTeal = Color(0xFF4F7472)
        val seed = dominantColor(pixels(mutedTeal to 900, Color.Red to 10, Color.White to 90))
        assertTrue(hueDistance(hueOf(seed), hueOf(mutedTeal)) < 10f, "seed $seed")
    }

    @Test
    fun greysAndTransparencyDoNotCount() {
        val orange = Color(0xFFEF6C00)
        val seed = dominantColor(
            pixels(Color(0xFF808080) to 700, Color.Black to 200, orange to 100) +
                IntArray(500) { Color.Blue.copy(alpha = 0f).toArgb() },
        )
        assertTrue(hueDistance(hueOf(seed), hueOf(orange)) < 10f, "seed $seed")
    }

    @Test
    fun aGreyCoverGivesAGreyPalette() {
        val seed = dominantColor(pixels(Color(0xFF222222) to 500, Color(0xFFDDDDDD) to 500))
        assertTrue(Oklch.of(seed).c < 0.03f)
        val palette = playerPalette(seed, dark = false)
        assertTrue(Oklch.of(palette.accent).c < 0.01f)
    }

    @Test
    fun everyHueGetsAReadableAccent() {
        val lightBackground = Color(0xFFFFFBFE)
        val darkBackground = Color(0xFF1C1B1F)
        for (seed in listOf(Color(0xFFFFEB3B), Color(0xFF2962FF), Color(0xFFD50000), Color(0xFF00C853), Color(0xFFAA00FF))) {
            val light = playerPalette(seed, dark = false)
            val dark = playerPalette(seed, dark = true)
            assertTrue(contrast(light.accent, lightBackground) >= 3f, "light accent for $seed: ${light.accent}")
            assertTrue(contrast(dark.accent, darkBackground) >= 3f, "dark accent for $seed: ${dark.accent}")
            assertTrue(contrast(light.onAccent, light.accent) >= 4.5f, "light onAccent for $seed")
            assertTrue(contrast(dark.onAccent, dark.accent) >= 4.5f, "dark onAccent for $seed")
            assertTrue(contrast(light.onContainer, light.container) >= 7f, "light container for $seed")
            assertTrue(contrast(dark.onContainer, dark.container) >= 7f, "dark container for $seed")
            assertTrue(hueDistance(hueOf(light.accent), hueOf(seed)) < 12f, "light hue for $seed: ${light.accent}")
        }
    }

    @Test
    fun cornerColourIsTheTopLeftTrianglesAverage() {
        // Red over the corner a ribbon covers, blue elsewhere.
        val side = 100
        val argb = IntArray(side * side) { i -> if (i % side + i / side < 50) 0xFFE62117.toInt() else 0xFF0000FF.toInt() }
        val corner = cornerColor(ArtworkPixels(argb, side, side))!!
        assertTrue(corner.looksLike(Color(0xFFE62117)), "$corner")
        assertFalse(Color(0xFF0000FF).looksLike(Color(0xFFE62117)))
        assertTrue(Color(0xFFD42A1C).looksLike(Color(0xFFE62117)))
    }
}
