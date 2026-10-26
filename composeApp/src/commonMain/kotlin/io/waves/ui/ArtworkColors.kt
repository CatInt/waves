package io.waves.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.compose.asPainter
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import io.waves.data.ArtworkColorStore
import io.waves.data.InMemoryArtworkColorStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Makes the decoded image readable from a software canvas (Android decodes to hardware bitmaps by default). */
internal expect fun ImageRequest.Builder.readablePixels(): ImageRequest.Builder

/** Where picked cover colours are kept; the app provides its persistent store. */
internal val LocalArtworkColors = staticCompositionLocalOf<ArtworkColorStore> { InMemoryArtworkColors }

private val InMemoryArtworkColors = InMemoryArtworkColorStore()

/**
 * Dominant colour of the cover at [url], or null until it has loaded (or when there is none). Picked once per URL,
 * when the image first loads, and then always taken from [LocalArtworkColors].
 */
@Composable
internal fun rememberArtworkSeed(url: String?): Color? {
    val context = LocalPlatformContext.current
    val store = LocalArtworkColors.current
    var seed by remember(url, store) {
        mutableStateOf(url?.takeIf { it.isNotBlank() }?.let { store.get(it) }?.let { Color(it) })
    }
    LaunchedEffect(url, store) {
        if (url.isNullOrBlank() || seed != null) return@LaunchedEffect
        val picked = artworkSeed(context, url) ?: return@LaunchedEffect
        seed = picked
        store.put(url, picked.toArgb())
    }
    return seed
}

private suspend fun artworkSeed(context: PlatformContext, url: String): Color? {
    val image = artworkPixels(context, url) ?: return null
    return withContext(Dispatchers.Default) {
        val step = max(1, image.argb.size / MAX_SAMPLES)
        dominantColor(IntArray((image.argb.size + step - 1) / step) { image.argb[it * step] })
    }
}

/** A decoded cover's ARGB pixels, row by row. */
internal class ArtworkPixels(val argb: IntArray, val width: Int, val height: Int)

/** The cover at [url], decoded at [DECODE_SIZE]; null when it can't be loaded. */
internal suspend fun artworkPixels(context: PlatformContext, url: String): ArtworkPixels? {
    val request = ImageRequest.Builder(context).data(url).size(DECODE_SIZE).readablePixels().build()
    val result = SingletonImageLoader.get(context).execute(request)
    val image = (result as? SuccessResult)?.image ?: return null
    return withContext(Dispatchers.Default) {
        runCatching { pixels(image.asPainter(context), image.width, image.height) }.getOrNull()
    }
}

/** Player colours toned from an artwork seed so they sit with the cover in both light and dark themes. */
@Immutable
internal class PlayerColors(
    val container: Color,
    val onContainer: Color,
    val accent: Color,
    val onAccent: Color,
    val backdrop: Color,
) {
    fun applyTo(scheme: ColorScheme): ColorScheme = scheme.copy(
        primary = accent,
        onPrimary = onAccent,
        secondaryContainer = container,
        onSecondaryContainer = onContainer,
    )
}

/** The current episode's [PlayerColors], so screens can echo the player's tint (e.g. on the playing row). */
internal val LocalPlayerColors = compositionLocalOf<PlayerColors?> { null }

@Composable
internal fun rememberPlayerColors(seed: Color?): PlayerColors {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f
    val target = remember(seed, scheme) {
        if (seed == null) {
            PlayerColors(scheme.secondaryContainer, scheme.onSecondaryContainer, scheme.primary, scheme.onPrimary, scheme.secondaryContainer)
        } else {
            playerPalette(seed, dark)
        }
    }
    val spec = tween<Color>(450)
    val container by animateColorAsState(target.container, spec)
    val onContainer by animateColorAsState(target.onContainer, spec)
    val accent by animateColorAsState(target.accent, spec)
    val onAccent by animateColorAsState(target.onAccent, spec)
    val backdrop by animateColorAsState(target.backdrop, spec)
    return PlayerColors(container, onContainer, accent, onAccent, backdrop)
}

/**
 * Player colours in the seed's hue at fixed perceptual lightness (OKLCH), so every hue gets the same contrast:
 * an HSL lightness turns yellow into olive and leaves blue too dark. Chroma follows the seed's, within limits,
 * and is reduced where needed to stay inside sRGB. A near-grey seed gives a grey palette.
 */
internal fun playerPalette(seed: Color, dark: Boolean): PlayerColors {
    val lch = Oklch.of(seed)
    val vivid = if (lch.c < GREY_CHROMA) 0f else (lch.c / 0.10f).coerceIn(0.65f, 1f)
    fun tone(l: Float, c: Float) = Oklch(l, c * vivid, lch.h).toColor()
    return if (dark) {
        PlayerColors(
            container = tone(0.32f, 0.05f),
            onContainer = tone(0.94f, 0.025f),
            accent = tone(0.80f, 0.13f),
            onAccent = tone(0.24f, 0.05f),
            backdrop = tone(0.36f, 0.07f),
        )
    } else {
        PlayerColors(
            container = tone(0.92f, 0.045f),
            onContainer = tone(0.26f, 0.04f),
            accent = tone(0.52f, 0.16f),
            onAccent = Color.White,
            backdrop = tone(0.86f, 0.07f),
        )
    }
}

/** Decoded well above the sampling grid so each platform's resampling filter barely affects the result. */
private const val DECODE_SIZE = 256
private const val MAX_SAMPLES = 128 * 128

/** OKLCH chroma below which a colour reads as grey. */
private const val GREY_CHROMA = 0.03f

/** Share of a cover's pixels a hue (with its neighbouring bins) needs before it can be picked. */
private const val MIN_HUE_SHARE = 0.03f

/** The image is drawn at its own size, never rescaled, so Android and desktop see the same pixels. */
private fun pixels(painter: Painter, width: Int, height: Int): ArtworkPixels {
    val bitmap = ImageBitmap(width, height)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(width.toFloat(), height.toFloat())) {
        with(painter) { draw(size) }
    }
    val all = IntArray(width * height)
    bitmap.readPixels(all)
    return ArtworkPixels(all, width, height)
}

/**
 * The cover's primary vivid colour among ARGB [pixels]: they are binned by OKLCH hue (15° bins) and weighted by
 * chroma cubed, so a strong colour beats a larger muted one, while greys, near-black and near-white count nothing.
 * A hue needs [MIN_HUE_SHARE] of the pixels so a speck can't win; each bin also counts half of its neighbours so a
 * hue straddling a bin edge isn't split. The winner is the weighted OKLab mean of its bin, leaning to its most vivid
 * pixels. Transparent pixels are skipped. A cover without enough colour gives its average (a grey).
 */
internal fun dominantColor(pixels: IntArray): Color {
    val bins = 24
    val weight = FloatArray(bins)
    val sumL = FloatArray(bins)
    val sumA = FloatArray(bins)
    val sumB = FloatArray(bins)
    val members = IntArray(bins)
    var count = 0
    var meanL = 0f; var meanA = 0f; var meanB = 0f
    for (argb in pixels) {
        if ((argb ushr 24) < 128) continue
        val lab = Oklab.of(Color(argb))
        count++
        meanL += lab.l; meanA += lab.a; meanB += lab.b
        val c = sqrt(lab.a * lab.a + lab.b * lab.b)
        if (c < GREY_CHROMA || lab.l < 0.18f || lab.l > 0.97f) continue
        val hue = (atan2(lab.b, lab.a) * RAD_TO_DEG).mod(360f)
        val i = (hue / (360f / bins)).toInt().coerceIn(0, bins - 1)
        val w = c * c * c
        members[i]++
        weight[i] += w; sumL[i] += lab.l * w; sumA[i] += lab.a * w; sumB[i] += lab.b * w
    }
    if (count == 0) return Color.Gray
    fun around(values: IntArray, i: Int) = values[i] + values[(i + bins - 1) % bins] + values[(i + 1) % bins]
    val score = FloatArray(bins) { weight[it] + 0.5f * (weight[(it + bins - 1) % bins] + weight[(it + 1) % bins]) }
    val best = score.indices.filter { around(members, it) >= count * MIN_HUE_SHARE && members[it] > 0 }
        .maxByOrNull { score[it] }
        ?: return Oklab(meanL / count, meanA / count, meanB / count).toColor()
    val w = weight[best]
    return Oklab(sumL[best] / w, sumA[best] / w, sumB[best] / w).toColor()
}

private const val RAD_TO_DEG = (180.0 / PI).toFloat()

/** [OKLab](https://bottosson.github.io/posts/oklab/): perceptual lightness [l] 0..1 and opponent axes [a], [b]. */
internal data class Oklab(val l: Float, val a: Float, val b: Float) {
    /** Null outside sRGB. */
    fun toSrgbOrNull(): Color? {
        val l_ = l + 0.3963377774f * a + 0.2158037573f * b
        val m_ = l - 0.1055613458f * a - 0.0638541728f * b
        val s_ = l - 0.0894841775f * a - 1.2914855480f * b
        val lc = l_ * l_ * l_; val mc = m_ * m_ * m_; val sc = s_ * s_ * s_
        val r = 4.0767416621f * lc - 3.3077115913f * mc + 0.2309699292f * sc
        val g = -1.2684380046f * lc + 2.6097574011f * mc - 0.3413193965f * sc
        val bl = -0.0041960863f * lc - 0.7034186147f * mc + 1.7076147010f * sc
        val e = 1e-4f
        if (r < -e || r > 1 + e || g < -e || g > 1 + e || bl < -e || bl > 1 + e) return null
        return Color(encode(r), encode(g), encode(bl))
    }

    fun toColor(): Color = toSrgbOrNull() ?: Oklch(l, sqrt(a * a + b * b), (atan2(b, a) * RAD_TO_DEG).mod(360f)).toColor()

    companion object {
        fun of(color: Color): Oklab {
            val r = decode(color.red); val g = decode(color.green); val b = decode(color.blue)
            val l = cbrt(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b)
            val m = cbrt(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b)
            val s = cbrt(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b)
            return Oklab(
                0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s,
                1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s,
                0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s,
            )
        }

        private fun decode(c: Float) = if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
        private fun encode(c: Float): Float {
            val v = c.coerceIn(0f, 1f)
            return if (v <= 0.0031308f) v * 12.92f else 1.055f * v.pow(1f / 2.4f) - 0.055f
        }
        private fun cbrt(x: Float) = if (x <= 0f) 0f else x.pow(1f / 3f)
    }
}

/** OKLab in polar form: lightness [l], chroma [c], hue [h] in degrees. */
internal data class Oklch(val l: Float, val c: Float, val h: Float) {
    private fun lab(chroma: Float): Oklab {
        val rad = h / RAD_TO_DEG
        return Oklab(l.coerceIn(0f, 1f), chroma * cos(rad), chroma * sin(rad))
    }

    /** Lowers chroma (keeping lightness and hue) until the colour fits in sRGB. */
    fun toColor(): Color {
        lab(c).toSrgbOrNull()?.let { return it }
        var lo = 0f; var hi = c
        repeat(16) {
            val mid = (lo + hi) / 2f
            if (lab(mid).toSrgbOrNull() != null) lo = mid else hi = mid
        }
        return lab(lo).toSrgbOrNull() ?: Color.Gray
    }

    companion object {
        fun of(color: Color): Oklch {
            val lab = Oklab.of(color)
            return Oklch(lab.l, sqrt(lab.a * lab.a + lab.b * lab.b), (atan2(lab.b, lab.a) * RAD_TO_DEG).mod(360f))
        }
    }
}
