package io.waves

import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.Image
import java.awt.RenderingHints
import io.waves.ui.cropBias
import io.waves.ui.detailFocus
import io.waves.ui.rowDetail
import java.awt.geom.AffineTransform
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BaseMultiResolutionImage
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.math.max

/**
 * What the menu bar pill shows: [cover], the playing show's cover, or the app icon's waves without one; [dimmed]
 * while that show is paused.
 */
internal data class PillContent(
    val cover: PillCover? = null,
    val dimmed: Boolean = false,
)

/** A cover ready for the pill: shrunk to about the pill's width, with how much detail each of its rows holds. */
internal class PillCover private constructor(val image: BufferedImage, val rows: FloatArray) {
    companion object {
        /** Image files ImageIO can read (JPEG, PNG, GIF, BMP); null for anything else. Slow: call off the UI thread. */
        fun decode(bytes: ByteArray): PillCover? =
            runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()?.let(::of)

        fun of(source: BufferedImage): PillCover {
            val width = minOf(source.width, COVER_WIDTH)
            val height = maxOf(1, (source.height.toLong() * width / source.width).toInt())
            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            image.createGraphics().apply {
                drawImage(source.getScaledInstance(width, height, Image.SCALE_AREA_AVERAGING), 0, 0, null)
                dispose()
            }
            val argb = image.getRGB(0, 0, width, height, null, 0, width)
            return PillCover(image, rowDetail(argb, width, height))
        }

        /** Twice the pill's width, for Retina. */
        private const val COVER_WIDTH = 2 * MenuBarPill.WIDTH
    }
}

/**
 * Draws the menu bar item as the show page's collapsed cover chip: the playing show's cover filling a rounded
 * rectangle, cropped around its most detailed band and framed by an inner shadow and a white border, dimmed to black
 * and white while paused. Without a cover the app icon's blue and white wave lines stand in. Sizes are in points; [image] bundles 1x
 * and 2x renderings for Retina.
 */
internal object MenuBarPill {
    /** macOS caps status item images at the bar's thickness minus 1 pt (21 pt on a 22 pt bar), else it rescales. */
    const val HEIGHT = 21
    /** The show page chip's proportions (160 by 52 dp, corners 16, shadow 3 and border 2), nudged up where they'd vanish at this size. */
    const val WIDTH = 64
    private const val CORNER = 6.5f
    private const val SHADOW_DEPTH = 1.5f
    private const val BORDER = 1f
    /** The icon tile's blue. */
    private val Blue = Color(0x76, 0xA2, 0xE1)
    private const val LINE_ALPHA = 0.38f
    private val PausedDim = Color(0, 0, 0, 150)
    /** The icon's 1024 canvas: the band from [ART_TOP] to [ART_BOTTOM] fills the pill's height, its right end at [ART_RIGHT]. */
    private const val ART_TOP = 420f
    private const val ART_BOTTOM = 880f
    private const val ART_RIGHT = 1010f
    private const val ART_LINE_WIDTH = 15f

    /** The icon's lines (see art/IconGen.java), in its 1024 canvas. */
    private val waves: List<Path2D> by lazy {
        runCatching {
            MenuBarPill::class.java.getResourceAsStream("/menu-bar-waves.txt")?.bufferedReader()?.use { reader ->
                reader.readLines().filter { it.isNotBlank() }.map(::parsePath)
            }
        }.getOrNull().orEmpty()
    }

    fun image(content: PillContent): Image =
        BaseMultiResolutionImage(render(content, 1), render(content, 2))

    fun render(content: PillContent, scale: Int): BufferedImage {
        val image = BufferedImage(WIDTH * scale, HEIGHT * scale, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.scale(scale.toDouble(), scale.toDouble())
            val w = WIDTH.toFloat()
            val h = HEIGHT.toFloat()

            val cover = content.cover
            if (cover != null) {
                drawCover(g, cover, w, h)
            } else {
                g.paint = GradientPaint(0f, 0f, Blue, 0f, h, Color(0x5E, 0x92, 0xD8))
                g.fill(Rectangle2D.Float(0f, 0f, w, h))
                drawWaves(g, w, h)
            }
            if (content.dimmed) {
                desaturate(image)
                g.color = PausedDim
                g.fill(Rectangle2D.Float(0f, 0f, w, h))
            }
            drawFrame(g, w, h)
        } finally {
            g.dispose()
        }
        applyPillMask(image)
        return image
    }

    /** The icon's lines, faded, scaled so [ART_TOP]..[ART_BOTTOM] spans the pill and anchored to its right edge. */
    private fun drawWaves(g: Graphics2D, w: Float, h: Float) {
        val scale = h / (ART_BOTTOM - ART_TOP)
        val saved = g.transform
        val savedComposite = g.composite
        g.translate((w - ART_RIGHT * scale).toDouble(), (-ART_TOP * scale).toDouble())
        g.scale(scale.toDouble(), scale.toDouble())
        g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, LINE_ALPHA)
        g.color = Color.WHITE
        g.stroke = BasicStroke(ART_LINE_WIDTH, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        waves.forEach(g::draw)
        g.composite = savedComposite
        g.transform = saved
    }

    /** [cover] filling the pill, its width spanning it and the band of it with the most detail showing. */
    private fun drawCover(g: Graphics2D, cover: PillCover, w: Float, h: Float) {
        val image = cover.image
        val scale = max(w / image.width, h / image.height)
        val drawn = image.height * scale
        val band = (h / drawn).coerceIn(0f, 1f)
        val bias = cropBias(detailFocus(cover.rows, band), band)
        val x = (w - image.width * scale) / 2f
        val y = (h - drawn) * (bias + 1f) / 2f
        g.drawImage(image, AffineTransform(scale, 0f, 0f, scale, x, y), null)
    }

    /**
     * The show page chip's frame: an inner shadow darkening in from the edges, then a white border. Concentric strokes
     * fading inward stand in for a blurred shadow.
     */
    private fun drawFrame(g: Graphics2D, w: Float, h: Float) {
        val steps = 6
        val step = SHADOW_DEPTH / steps
        g.stroke = BasicStroke(step)
        for (i in 0 until steps) {
            val inset = i * step + step / 2
            val falloff = 1f - i / steps.toFloat()
            g.color = Color(0f, 0f, 0f, 0.22f * falloff * falloff)
            val arc = 2 * (CORNER - inset).coerceAtLeast(0f)
            g.draw(RoundRectangle2D.Float(inset, inset, w - 2 * inset, h - 2 * inset, arc, arc))
        }
        // Centred on the edge at twice the width: the pill mask trims the outer half, and the white covers the edge's
        // anti-aliasing so no sliver of the cover shows at the corners.
        g.stroke = BasicStroke(2 * BORDER)
        g.color = Color(255, 255, 255, 230)
        g.draw(RoundRectangle2D.Float(0f, 0f, w, h, 2 * CORNER, 2 * CORNER))
    }

    /** Turns [image] to greys of the same brightness, keeping its alpha. */
    private fun desaturate(image: BufferedImage) {
        val argb = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        for (i in argb.indices) {
            val p = argb[i]
            val luma = (0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)).toInt()
            argb[i] = (p and 0xFF000000.toInt()) or (luma shl 16) or (luma shl 8) or luma
        }
        image.setRGB(0, 0, image.width, image.height, argb, 0, image.width)
    }

    /** Shape clips aren't antialiased in Java2D, so the rounded corners come from compositing an antialiased mask. */
    private fun applyPillMask(image: BufferedImage) {
        val mask = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        val h = image.height.toFloat()
        val arc = 2 * CORNER * h / HEIGHT
        mask.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            color = Color.WHITE
            fill(RoundRectangle2D.Float(0f, 0f, image.width.toFloat(), h, arc, arc))
            dispose()
        }
        image.createGraphics().apply {
            composite = AlphaComposite.DstIn
            drawImage(mask, 0, 0, null)
            dispose()
        }
    }

    /** `M x,y L x,y C x1,y1 x2,y2 x,y …` as written by art/IconGen.java. */
    internal fun parsePath(data: String): Path2D {
        val path = Path2D.Float()
        val tokens = Regex("[MLCZ]|-?\\d*\\.?\\d+").findAll(data).map { it.value }.iterator()
        var command = 'M'
        val numbers = ArrayList<Float>(6)
        fun take(n: Int): Boolean {
            numbers.clear()
            repeat(n) { if (tokens.hasNext()) numbers += tokens.next().toFloat() else return false }
            return true
        }
        while (tokens.hasNext()) {
            val token = tokens.next()
            if (token[0].isLetter()) {
                command = token[0]
                if (command == 'Z') path.closePath()
                continue
            }
            numbers.clear()
            numbers += token.toFloat()
            when (command) {
                'M', 'L' -> if (tokens.hasNext()) {
                    val y = tokens.next().toFloat()
                    if (command == 'M') path.moveTo(numbers[0], y) else path.lineTo(numbers[0], y)
                    command = if (command == 'M') 'L' else command
                }
                'C' -> {
                    val x1 = numbers[0]
                    if (!take(5)) break
                    path.curveTo(x1, numbers[0], numbers[1], numbers[2], numbers[3], numbers[4])
                }
            }
        }
        return path
    }
}
