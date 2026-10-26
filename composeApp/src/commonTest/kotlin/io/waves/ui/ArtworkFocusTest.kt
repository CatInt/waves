package io.waves.ui

import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArtworkFocusTest {
    /** A [size]² opaque image, white except a black-and-white striped band over [rows]. */
    private fun imageWithStripes(size: Int, rows: IntRange): IntArray = IntArray(size * size) { i ->
        val x = i % size
        val y = i / size
        if (y in rows && x % 2 == 0) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }

    @Test
    fun detailSitsWhereTheImageChanges() {
        val rows = rowDetail(imageWithStripes(20, 2..4), 20, 20)
        assertTrue(rows[3] > 0f)
        assertEquals(0f, rows[15])
    }

    @Test
    fun focusFollowsTheDetailedBand() {
        val rows = rowDetail(imageWithStripes(100, 5..20), 100, 100)
        val focus = detailFocus(rows, band = 0.3f)
        assertTrue(focus < 0.3f, "focus $focus should be near the top band")
    }

    @Test
    fun boldShapeBeatsFineTexture() {
        // Top: one bold white block on black, like a title. Bottom half: pixel-level speckle, like foliage or gravel.
        val size = 256
        val image = IntArray(size * size) { i ->
            val x = i % size
            val y = i / size
            when {
                y in 40..70 && x in 60..200 -> 0xFFFFFFFF.toInt()
                y >= 128 && (x * 7 + y * 13) % 3 == 0 -> 0xFFFFFFFF.toInt()
                else -> 0xFF000000.toInt()
            }
        }
        val focus = detailFocus(rowDetail(image, size, size), band = 0.3f)
        assertTrue(focus < 0.35f, "focus $focus should be on the bold block, not the speckle")
    }

    @Test
    fun bandEdgesFallBetweenLinesOfText() {
        // Lines of text 8 rows tall with 4-row gaps, top to bottom: every band holds about the same detail.
        val rows = FloatArray(96) { if (it % 12 < 8) 1f else 0f }
        val h = 26
        val focus = detailFocus(rows, band = h / 96f)
        val start = (focus * 96 - h / 2f).roundToInt()
        assertEquals(0f, rows[start], "band starts mid-line at $start")
        assertEquals(0f, rows[start + h - 1], "band ends mid-line at ${start + h - 1}")
    }

    @Test
    fun flatImageStaysCentred() {
        val rows = rowDetail(IntArray(100 * 100) { 0xFF336699.toInt() }, 100, 100)
        assertEquals(0.5f, detailFocus(rows, band = 0.3f))
    }

    @Test
    fun biasCentresTheWindowOnTheFocusWithinTheImage() {
        assertEquals(0f, cropBias(0.5f, 0.3f))
        assertEquals(-1f, cropBias(0.05f, 0.3f))
        assertEquals(1f, cropBias(0.95f, 0.3f))
        assertEquals(0f, cropBias(0.1f, 1f))
    }
}
