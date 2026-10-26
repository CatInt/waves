package io.waves

import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.GradientPaint
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.Image
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BaseMultiResolutionImage
import java.awt.image.BufferedImage
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** What the menu bar pill shows: [text] is the playback time ("12:34 / 45:00"), or the app name when idle. */
internal data class PillContent(
    val text: String,
    val playing: Boolean,
)

/**
 * Draws the menu bar item in the app icon's style: a soft blue rounded rectangle with the icon's white wave lines
 * faintly behind a play/pause glyph and the playback time. Digits are set in fixed-width slots so the time doesn't
 * jiggle or resize the pill as it ticks. Sizes are in points; [image] bundles 1x and 2x renderings for Retina.
 */
internal object MenuBarPill {
    /** macOS caps status item images at the bar's thickness minus 1 pt (21 pt on a 22 pt bar), else it rescales. */
    const val HEIGHT = 21
    private const val CORNER = 6f
    const val MAX_WIDTH = 120
    private const val MIN_WIDTH = 2 * HEIGHT
    private const val TEXT_START = 21f
    private const val END_PADDING = 10f
    /** The icon tile's blue, and a deeper shade of it along the bottom and for the outline. */
    private val Blue = Color(0x76, 0xA2, 0xE1)
    private val DeepBlue = Color(0x3D, 0x7A, 0xC8)
    private const val LINE_ALPHA = 0.38f
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

    private val font: Font by lazy {
        val families = runCatching { GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet() }
            .getOrDefault(emptySet())
        val family = listOf("SF Pro Text", "Helvetica Neue").firstOrNull { it in families } ?: Font.SANS_SERIF
        Font(family, Font.PLAIN, 12).deriveFont(12.5f)
    }

    private val metrics: FontMetrics by lazy {
        val scratch = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics()
        try {
            scratch.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
            scratch.getFontMetrics(font)
        } finally {
            scratch.dispose()
        }
    }

    private val digitWidth: Int by lazy { ('0'..'9').maxOf { metrics.charWidth(it) } }

    /** Digits one by one, everything else as runs so it keeps its kerning. */
    private fun runs(text: String): List<String> = Regex("\\d|\\D+").findAll(text).map { it.value }.toList()

    private fun advance(run: String) = if (run[0].isDigit()) digitWidth else metrics.stringWidth(run)

    fun width(text: String): Int =
        ceil(TEXT_START + runs(text).sumOf { advance(it) } + END_PADDING).toInt().coerceIn(MIN_WIDTH, MAX_WIDTH)

    fun image(content: PillContent): Image {
        val width = width(content.text)
        return BaseMultiResolutionImage(render(content, width, 1), render(content, width, 2))
    }

    fun render(content: PillContent, width: Int, scale: Int): BufferedImage {
        val image = BufferedImage(width * scale, HEIGHT * scale, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.scale(scale.toDouble(), scale.toDouble())
            val w = width.toFloat()
            val h = HEIGHT.toFloat()

            g.paint = GradientPaint(0f, 0f, Blue, 0f, h, Color(0x5E, 0x92, 0xD8))
            g.fill(Rectangle2D.Float(0f, 0f, w, h))
            drawWaves(g, w, h)

            drawGlyph(g, content.playing, h, Color(DeepBlue.red, DeepBlue.green, DeepBlue.blue, 150), offset = 0.5f)
            drawGlyph(g, content.playing, h, Color.WHITE, offset = 0f)
            g.font = font
            val baseline = (h - metrics.ascent - metrics.descent) / 2f + metrics.ascent - 0.5f
            g.color = Color(DeepBlue.red, DeepBlue.green, DeepBlue.blue, 150)
            drawTabular(g, content.text, TEXT_START, baseline + 0.5f)
            g.color = Color.WHITE
            drawTabular(g, content.text, TEXT_START, baseline)
            drawBorder(g, w, h)
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

    /** A deeper blue outline with a hairline highlight inside it along the top. */
    private fun drawBorder(g: Graphics2D, w: Float, h: Float) {
        val line = 0.75f
        g.stroke = BasicStroke(line)
        g.color = Color(DeepBlue.red, DeepBlue.green, DeepBlue.blue, 220)
        g.draw(RoundRectangle2D.Float(line / 2, line / 2, w - line, h - line, 2 * CORNER - line, 2 * CORNER - line))
        val rim = 0.5f
        val inset = line + rim / 2
        g.stroke = BasicStroke(rim)
        g.paint = GradientPaint(0f, 0f, Color(255, 255, 255, 110), 0f, h / 2, Color(255, 255, 255, 0))
        g.draw(RoundRectangle2D.Float(inset, inset, w - 2 * inset, h - 2 * inset, 2 * (CORNER - inset), 2 * (CORNER - inset)))
    }

    private fun drawGlyph(g: Graphics2D, playing: Boolean, h: Float, color: Color, offset: Float) {
        g.color = color
        val size = 9f
        val top = (h - size) / 2f + offset
        if (playing) {
            val bar = 2.4f
            g.fill(RoundRectangle2D.Float(8.5f, top, bar, size, 1f, 1f))
            g.fill(RoundRectangle2D.Float(8.5f + bar + 2.2f, top, bar, size, 1f, 1f))
        } else {
            g.fill(Path2D.Float().apply {
                moveTo(9.0, top.toDouble())
                lineTo(9.0 + size * 0.9, top + size / 2.0)
                lineTo(9.0, (top + size).toDouble())
                closePath()
            })
        }
    }

    /** Draws [text] with each digit centred in a slot as wide as the widest digit. */
    private fun drawTabular(g: Graphics2D, text: String, x: Float, baseline: Float) {
        var pen = x
        for (run in runs(text)) {
            val slot = advance(run)
            g.drawString(run, pen + (slot - metrics.stringWidth(run)) / 2f, baseline)
            pen += slot
        }
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
