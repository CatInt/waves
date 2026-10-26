package io.waves.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.toSize
import coil3.memory.MemoryCache
import kotlin.math.abs
import kotlin.math.roundToInt

/** The tapped cover a show page grows its own cover out of, and shrinks it back into on the way out. */
data class CoverOrigin(
    val feedUrl: String,
    /** In the root, unclipped: a tile half scrolled out of view still gives its whole square. */
    val bounds: Rect,
    val corner: Dp,
    /** The tile's loaded image, shown by the flying cover until the show page's own request lands. */
    val image: MemoryCache.Key?,
)

/** Hands the cover tapped to open a show to that show's page, and empties its tile while the cover is away. */
class CoverOrigins {
    private var pending: CoverOrigin? = null

    /** The cover out of its tile: from when its flying copy shows until the show page has closed back into it. */
    var away: CoverOrigin? by mutableStateOf(null)
        private set

    fun mark(origin: CoverOrigin) { pending = origin }

    /** The show page's copy of [origin] is on screen over the tile, so the tile can go blank without a flash. */
    fun depart(origin: CoverOrigin) { away = origin }

    /** The cover marked for [feedUrl], if the show is being opened from one; either way, nothing is left pending. */
    fun take(feedUrl: String): CoverOrigin? = pending?.takeIf { it.feedUrl == feedUrl }.also { pending = null }

    /** The page opened from [origin] has gone, its cover back in the tile. */
    fun landed(origin: CoverOrigin?) { if (origin != null && away === origin) away = null }
}

val LocalCoverOrigins = staticCompositionLocalOf { CoverOrigins() }

/**
 * Tracks one cover tile: put [modifier] and [onLoaded] on its [Artwork], and call [mark] as it opens the show.
 * While that show's page is open the tile is left blank.
 */
class CoverAnchor internal constructor(
    private val origins: CoverOrigins,
    private val feedUrl: String,
    private val corner: Dp,
) {
    private var coordinates: LayoutCoordinates? = null
    private var image: MemoryCache.Key? = null

    val modifier: Modifier = Modifier
        .onPlaced { coordinates = it }
        .drawWithContent { if (!isAway()) drawContent() }
    val onLoaded: (MemoryCache.Key?) -> Unit = { image = it }

    fun mark() {
        val c = coordinates?.takeIf { it.isAttached } ?: return
        origins.mark(CoverOrigin(feedUrl, Rect(c.positionInRoot(), c.size.toSize()), corner, image))
    }

    /** The same show can have several tiles on screen; only the one it opened from sits where the cover left. */
    private fun isAway(): Boolean {
        val away = origins.away ?: return false
        if (away.feedUrl != feedUrl) return false
        val c = coordinates?.takeIf { it.isAttached } ?: return false
        val at = c.positionInRoot()
        return abs(at.x - away.bounds.left) < 1f && abs(at.y - away.bounds.top) < 1f
    }
}

@Composable
fun rememberCoverAnchor(feedUrl: String, corner: Dp): CoverAnchor {
    val origins = LocalCoverOrigins.current
    return remember(origins, feedUrl, corner) { CoverAnchor(origins, feedUrl, corner) }
}

/**
 * How long a cover takes to fly between [origin] and [rest], both in the root: longer the further it travels or
 * grows, so near and far tiles move at much the same speed. The page's background fades in [BackdropEnterMillis]
 * (out in [BackdropExitMillis]) wherever it came from.
 */
internal fun Density.coverFlightMillis(origin: Rect, rest: Rect, closing: Boolean): Int {
    val travel = maxOf((rest.center - origin.center).getDistance(), abs(rest.width - origin.width)).toDp().value
    // Long flights (from low on the screen) slow further, so they don't sweep across it.
    val distance = travel * FlightMillisPerDp + (travel - LongFlightDp).coerceAtLeast(0f) * LongFlightMillisPerDp
    // Opening, the flight lengthens more with distance, so the eye can follow the cover out of a far tile; closing,
    // it is longer throughout, leaving time for the cover to settle into its tile.
    if (closing) return ((FlightBaseMillis + distance) * ReturnShare).roundToInt().coerceIn(FlightMinMillis, ReturnMaxMillis)
    return (FlightBaseMillis + distance * OpeningDistanceShare).roundToInt().coerceIn(FlightMinMillis, OpeningMaxMillis)
}

/** Opening and closing a screen: quick off the mark, settling slowly (Material's emphasized curve). */
internal val ScreenTransitionEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/**
 * A cover flying back into its tile: off at a steady pace, then easing into place over a longer tail. Starting much
 * faster would make the first frames jump a long way and look choppy.
 */
internal val CoverReturnEasing = CubicBezierEasing(0.3f, 0.5f, 0f, 1f)
internal const val BackdropEnterMillis = 280
internal const val BackdropExitMillis = 280

/**
 * When a show page's text, buttons and episodes start coming in: once its background has, and once a cover flying in
 * for [flightMillis] is most of the way there, so a long flight isn't overtaken by the page filling in around it.
 */
internal fun contentEnterDelayMillis(flightMillis: Int?): Int =
    maxOf(ContentEnterDelayMillis, flightMillis?.let { (it * ContentFlightShare).roundToInt() } ?: 0)

internal const val ContentEnterMillis = 200
internal const val ContentExitMillis = 120
private const val ContentEnterDelayMillis = 160
/** How far through its flight the cover has covered nine tenths of the way, on [ScreenTransitionEasing]. */
private const val ContentFlightShare = 0.55f
private const val FlightBaseMillis = 190
private const val FlightMillisPerDp = 0.3f
private const val LongFlightDp = 300f
private const val LongFlightMillisPerDp = 0.25f
private const val FlightMinMillis = 240
private const val OpeningDistanceShare = 1.5f
private const val OpeningMaxMillis = 640
private const val ReturnShare = 1.25f
private const val ReturnMaxMillis = 600
