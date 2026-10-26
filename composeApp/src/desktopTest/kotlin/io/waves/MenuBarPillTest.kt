package io.waves

import java.awt.Color
import java.awt.image.BufferedImage
import java.awt.image.MultiResolutionImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MenuBarPillTest {
    @Test
    fun widthGrowsWithTextUpToCap() {
        val short = MenuBarPill.width("Hi")
        val long = MenuBarPill.width("A very long text that certainly won't fit in the menu bar at all")
        assertTrue(short < long)
        assertEquals(MenuBarPill.MAX_WIDTH, long)
    }

    @Test
    fun widthStaysPutAsTheTimeTicks() {
        assertEquals(MenuBarPill.width("1:11 / 45:00"), MenuBarPill.width("8:08 / 45:00"))
    }

    @Test
    fun rendersRetinaPillWithTransparentCornersOnIconBlue() {
        val image = MenuBarPill.image(PillContent("1:23 / 45:00", playing = true))
        val variants = (image as MultiResolutionImage).resolutionVariants.map { it as BufferedImage }
        assertEquals(listOf(1, 2), variants.map { it.height / MenuBarPill.HEIGHT })
        val retina = variants[1]
        assertEquals(0, retina.getRGB(0, 0) ushr 24, "corner outside the pill is transparent")
        val middle = Color(retina.getRGB(retina.width - 12, retina.height * 3 / 4), true)
        assertEquals(255, middle.alpha)
        assertTrue(middle.blue > middle.red + 60, "the icon's blue: $middle")
    }

    @Test
    fun drawsTheIconLines() {
        val path = MenuBarPill.parsePath("M-3.5,20 L30,40.25 C1,2 3,4 5,6")
        assertEquals(-3.5, path.bounds2D.minX)
        assertEquals(40.25, path.bounds2D.maxY)
        assertEquals(java.awt.geom.Point2D.Double(5.0, 6.0), path.currentPoint)
        // The pill's right end carries the crests: white lines lift some pixels well above the plain blue.
        val retina = (MenuBarPill.image(PillContent("Waves", playing = false)) as MultiResolutionImage)
            .resolutionVariants.map { it as BufferedImage }[1]
        val column = retina.width - 30
        val reds = (4 until retina.height - 4).map { Color(retina.getRGB(column, it)).red }
        assertTrue(reds.max() - reds.min() > 25, "wave lines in $reds")
    }
}
