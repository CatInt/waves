package io.waves.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import coil3.compose.LocalPlatformContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/** How much detail each row of the cover at [url] holds ([rowDetail]), or null until it has loaded. */
@Composable
internal fun rememberRowDetail(url: String?): FloatArray? {
    val context = LocalPlatformContext.current
    var rows by remember(url) { mutableStateOf<FloatArray?>(null) }
    LaunchedEffect(url) {
        if (url.isNullOrBlank()) return@LaunchedEffect
        val image = artworkPixels(context, url) ?: return@LaunchedEffect
        rows = withContext(Dispatchers.Default) { rowDetail(image.argb, image.width, image.height) }
    }
    return rows
}

/**
 * Detail per row of ARGB [argb], top to bottom, on a grid of about [DETAIL_GRID] cells across: each cell's mean luma is
 * compared with its right and lower neighbours and the differences squared. The coarse grid blurs fine texture
 * (foliage, gravel, grain) away while lettering, faces and bold shapes keep their edges, and squaring favours strong
 * contrast over many faint changes. Transparent pixels count as black.
 */
internal fun rowDetail(argb: IntArray, width: Int, height: Int): FloatArray {
    val cell = maxOf(1, width / DETAIL_GRID)
    val cols = width / cell
    val rows = height / cell
    if (cols == 0 || rows == 0) return FloatArray(0)
    val luma = FloatArray(cols * rows)
    for (y in 0 until rows * cell) {
        for (x in 0 until cols * cell) {
            val p = argb[y * width + x]
            if ((p ushr 24) < 128) continue
            luma[(y / cell) * cols + x / cell] +=
                0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)
        }
    }
    val area = (cell * cell).toFloat()
    for (i in luma.indices) luma[i] /= area
    return FloatArray(rows) { y ->
        var sum = 0f
        for (x in 0 until cols) {
            val here = luma[y * cols + x]
            if (x + 1 < cols) sum += (here - luma[y * cols + x + 1]).let { it * it }
            if (y + 1 < rows) sum += (here - luma[(y + 1) * cols + x]).let { it * it }
        }
        sum
    }
}

private const val DETAIL_GRID = 64

/** How many rows' worth of detail cutting through a busy row costs a band. */
private const val EDGE_PENALTY = 2f

/**
 * Centre (0 top, 1 bottom) of the horizontal band [band] of the image's height tall that holds the most detail in
 * [rows]. Detail on the band's first and last rows counts against it, so when a title is taller than the band the
 * cut falls between lines rather than through letters. A mild pull toward the middle settles near-ties there, and a
 * flat image stays centred.
 */
internal fun detailFocus(rows: FloatArray, band: Float): Float {
    val n = rows.size
    val h = (band * n).roundToInt().coerceIn(1, n)
    if (h >= n) return 0.5f
    val prefix = FloatArray(n + 1)
    for (i in 0 until n) prefix[i + 1] = prefix[i] + rows[i]
    if (prefix[n] <= 0f) return 0.5f
    var best = 0.5f
    var bestScore = Float.NEGATIVE_INFINITY
    for (start in 0..n - h) {
        val centre = (start + h / 2f) / n
        val cut = EDGE_PENALTY * (rows[start] + rows[start + h - 1])
        val score = (prefix[start + h] - prefix[start] - cut) * (1f - 0.25f * abs(centre - 0.5f) * 2f)
        if (score > bestScore) {
            bestScore = score
            best = centre
        }
    }
    return best
}

/**
 * Vertical alignment bias (-1 top, 1 bottom) that, when a square image is cropped to a window [band] of its height
 * tall, centres the window on [focus] as far as the image allows.
 */
internal fun cropBias(focus: Float, band: Float): Float =
    if (band >= 1f) 0f else ((2f * focus - 1f) / (1f - band)).coerceIn(-1f, 1f)
