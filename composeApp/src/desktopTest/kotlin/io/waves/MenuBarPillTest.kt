package io.waves

import java.awt.Color
import java.awt.image.BufferedImage
import java.awt.image.MultiResolutionImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MenuBarPillTest {
    private fun retina(content: PillContent): BufferedImage =
        (MenuBarPill.image(content) as MultiResolutionImage).resolutionVariants.map { it as BufferedImage }[1]

    /** A flat red cover, but for black and white stripes along its bottom quarter: the band the pill should show. */
    private fun stripedCover(): PillCover {
        val source = BufferedImage(600, 600, BufferedImage.TYPE_INT_RGB)
        source.createGraphics().apply {
            color = Color.RED
            fillRect(0, 0, 600, 600)
            for (y in 450 until 600 step 20) {
                color = Color.BLACK
                fillRect(0, y, 600, 10)
                color = Color.WHITE
                fillRect(0, y + 10, 600, 10)
            }
            dispose()
        }
        return PillCover.of(source)
    }

    @Test
    fun rendersRetinaPillWithTransparentCornersOnIconBlue() {
        val variants = (MenuBarPill.image(PillContent()) as MultiResolutionImage).resolutionVariants.map { it as BufferedImage }
        assertEquals(listOf(1, 2), variants.map { it.height / MenuBarPill.HEIGHT })
        assertEquals(listOf(MenuBarPill.WIDTH, 2 * MenuBarPill.WIDTH), variants.map { it.width })
        val retina = variants[1]
        assertEquals(0, retina.getRGB(0, 0) ushr 24, "corner outside the pill is transparent")
        val middle = Color(retina.getRGB(retina.width - 12, retina.height * 3 / 4), true)
        assertEquals(255, middle.alpha)
        assertTrue(middle.blue > middle.red + 60, "the icon's blue: $middle")
    }

    @Test
    fun coverFillsThePillInsideAWhiteBorderCroppedToItsDetail() {
        val retina = retina(PillContent(cover = stripedCover()))
        assertEquals(0, retina.getRGB(0, 0) ushr 24, "corner outside the pill is transparent")
        val border = Color(retina.getRGB(retina.width / 2, 0), true)
        assertTrue(border.red > 200 && border.green > 200 && border.blue > 200, "white border: $border")
        val column = retina.width / 2
        val lumas = (5 until retina.height - 5).map { Color(retina.getRGB(column, it)).green }
        assertTrue(lumas.max() > 200 && lumas.min() < 40, "the striped band shows undimmed, not the flat red: $lumas")
    }

    @Test
    fun pausedDimsTheCoverToBlackAndWhite() {
        val cover = stripedCover()
        val column = MenuBarPill.WIDTH
        val brightest = { content: PillContent ->
            val retina = retina(content)
            (5 until retina.height - 5).maxOf { Color(retina.getRGB(column, it)).green }
        }
        val playing = brightest(PillContent(cover = cover))
        val paused = brightest(PillContent(cover = cover, dimmed = true))
        assertTrue(paused < playing - 100, "paused $paused against playing $playing")
        // The red the striped band sits on comes out grey.
        val retina = retina(PillContent(cover = cover, dimmed = true))
        val pixels = (5 until retina.height - 5).map { Color(retina.getRGB(column, it)) }
        assertTrue(pixels.all { maxOf(it.red, it.green, it.blue) - minOf(it.red, it.green, it.blue) <= 2 }, "greys: $pixels")
    }

    @Test
    fun drawsTheIconLines() {
        val path = MenuBarPill.parsePath("M-3.5,20 L30,40.25 C1,2 3,4 5,6")
        assertEquals(-3.5, path.bounds2D.minX)
        assertEquals(40.25, path.bounds2D.maxY)
        assertEquals(java.awt.geom.Point2D.Double(5.0, 6.0), path.currentPoint)
        // The pill's right end carries the crests: white lines lift some pixels well above the plain blue.
        val retina = retina(PillContent())
        val column = retina.width - 30
        val reds = (4 until retina.height - 4).map { Color(retina.getRGB(column, it)).red }
        assertTrue(reds.max() - reds.min() > 25, "wave lines in $reds")
    }
}
