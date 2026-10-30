package io.waves.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import io.waves.model.Podcast
import io.waves.youtube.YoutubeUrls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Remembers a state holder bound to its own child [CoroutineScope], cancelled when it leaves composition
 * or when [keys] change.
 */
@Composable
fun <T> rememberHolder(vararg keys: Any?, factory: (CoroutineScope) -> T): T {
    val parent = rememberCoroutineScope()
    val holder = remember(*keys) {
        val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
        scope to factory(scope)
    }
    DisposableEffect(holder) { onDispose { holder.first.cancel() } }
    return holder.second
}

private val placeholderColors = listOf(
    Color(0xFF5B6CFF), Color(0xFFE85D75), Color(0xFF2EAD8B), Color(0xFFF2A541),
    Color(0xFF8E5BD9), Color(0xFF3A9BD9), Color(0xFFD96C3A), Color(0xFF5E8C31),
)

/**
 * Square artwork with a coloured initials placeholder underneath (shown while loading / when absent).
 * [alignment] picks which part of the image stays visible when the box's shape crops it.
 */
@Composable
fun Artwork(
    url: String?,
    title: String,
    modifier: Modifier = Modifier,
    size: Dp? = null,
    corner: Dp = 8.dp,
    alignment: Alignment = Alignment.Center,
    placeholderKey: MemoryCache.Key? = null,
    onLoaded: ((MemoryCache.Key?) -> Unit)? = null,
    /** A YouTube channel's live archive, whose cover is the channel's own: a red corner tells the two apart. */
    liveArchive: Boolean = false,
    /**
     * A YouTube playlist's ID, whose cover is its channel's too: a corner tells it apart, in its own colour once it's
     * followed ([PlaylistColors]). Null for no corner.
     */
    playlist: String? = null,
    /**
     * The cover size the live archive's corner is drawn for; null for this cover's own. The show page's cover keeps
     * its corner as it collapses into the bar's chip, which clips it, but scales it on its flight between screens.
     */
    ribbonFor: (() -> Dp)? = null,
) {
    val sized = if (size != null) modifier.size(size) else modifier
    Box(
        sized.clip(RoundedCornerShape(corner))
            .background(placeholderColors[(title.hashCode() and 0x7fffffff) % placeholderColors.size]),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initials(title),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = when {
                size == null || size >= 64.dp -> 28.sp
                size >= 32.dp -> 16.sp
                else -> 8.sp
            },
        )
        if (!url.isNullOrBlank()) {
            val context = LocalPlatformContext.current
            // A new request starts empty, even when cached, so it stands on the last image shown (or [placeholderKey]).
            var shown by remember { mutableStateOf<MemoryCache.Key?>(null) }
            val request = remember(context, url, placeholderKey) {
                ImageRequest.Builder(context).data(url).placeholderMemoryCacheKey(shown ?: placeholderKey).build()
            }
            AsyncImage(
                model = request,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                alignment = alignment,
                modifier = Modifier.fillMaxSize(),
                onSuccess = {
                    shown = it.result.memoryCacheKey
                    onLoaded?.invoke(it.result.memoryCacheKey)
                },
            )
        }
        when {
            liveArchive -> CornerRibbon(
                "LIVED", LiveRed, ribbonFor,
                coverLikeRibbon = rememberCornerColor(url)?.looksLike(LiveRed) == true,
            )
            playlist != null -> CornerRibbon(null, LocalPlaylistColors.current.color(playlist), ribbonFor)
        }
    }
}

/**
 * A ribbon of [color] filling the cover's top-left corner, sized to the cover, with [label] along its edge in white at
 * 30%, so the letters are mostly the cover, set apart from the ribbon by the tint; where the cover there is much the
 * ribbon's colour ([coverLikeRibbon]) and would blend in, white at 85%, with only a little of the cover showing
 * through: "LIVED" (the streams are over) for a live archive, nothing for a playlist, whose colour says it. The smaller
 * the cover the more of it the corner takes, so the word still reads on a list's cover; on the smallest (a menu's) the
 * corner goes without it.
 */
@Composable
private fun BoxScope.CornerRibbon(
    label: String?,
    color: Color,
    fixedSide: (() -> Dp)?,
    coverLikeRibbon: Boolean = false,
) {
    BoxWithConstraints(Modifier.matchParentSize()) {
        val side = fixedSide?.invoke() ?: minOf(maxWidth, maxHeight)
        val ribbon = Ribbon.of(side)
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val word = if (label == null || side < 36.dp) null else {
            val size = with(density) { ribbon.text.toSp() }
            measurer.measure(
                label,
                TextStyle(
                    // As heavy as the font goes: cut out of the ribbon, thicker letters show more of the cover.
                    fontWeight = FontWeight.Black,
                    fontSize = size,
                    // Glyphs sit in a box of their own size, so the word fills the ribbon without line spacing.
                    lineHeight = size,
                    letterSpacing = (0.5f * ribbon.large).sp,
                ),
                softWrap = false,
                maxLines = 1,
            )
        }
        // Drawn on a layer of its own, so the word clears the ribbon rather than the cover under it.
        Canvas(Modifier.matchParentSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
            val leg = ribbon.leg.toPx()
            val corner = Path().apply {
                moveTo(0f, 0f)
                lineTo(leg, 0f)
                lineTo(0f, leg)
                close()
            }
            drawPath(corner, color)
            if (word != null) {
                // The word runs along the corner's long edge, its middle half [Ribbon.line] in along each edge.
                val middle = ribbon.line.toPx() / 2
                rotate(-45f, pivot = Offset(middle, middle)) {
                    drawText(
                        word,
                        // In place of the ribbon, not over it: white letters with the cover showing through, most of
                        // it, or (on a cover the ribbon's colour, where it would blend in) only a little.
                        color = Color.White.copy(alpha = if (coverLikeRibbon) 0.85f else 0.3f),
                        topLeft = Offset(middle - word.size.width / 2f, middle - word.size.height / 2f),
                        blendMode = BlendMode.Src,
                    )
                }
            }
        }
    }
}

/**
 * The corner ribbon on a cover whose shorter side is [side]: how far along each edge the corner reaches ([leg]), the
 * word's size ([text]), and where the word's middle crosses the edges ([line]): a little in from the long edge,
 * toward the corner.
 */
private class Ribbon(val large: Float, val leg: Dp, val text: Dp) {
    val line: Dp get() = leg - text * 1.15f

    companion object {
        /**
         * List-sized covers (48 dp and under) give the corner more of themselves than large ones (80 dp and up), and
         * in between it eases from one to the other, so a cover flying between screens grows its corner smoothly.
         */
        fun of(side: Dp): Ribbon {
            val large = ((side - 48.dp) / 32.dp).coerceIn(0f, 1f)
            return Ribbon(large, side * lerp(0.42f, 0.34f, large), side * lerp(0.132f, 0.11f, large))
        }
    }
}

private const val SQRT2 = 1.4142135f

/** YouTube's live red, a touch see-through so the art still shows under it. */
internal val LiveRed = Color(0xE6E62117)


/** The show is a YouTube channel's live archive; its cover gets [Artwork]'s red corner. */
internal val Podcast.isLiveArchive: Boolean get() = YoutubeUrls.isLiveArchive(feedUrl)

/** The show's playlist ID when it's a YouTube playlist (other than a live archive), for [Artwork]'s corner; else null. */
internal val Podcast.playlistId: String?
    get() = YoutubeUrls.playlistIdOfFeed(feedUrl)?.takeIf { !isLiveArchive }

internal fun initials(title: String): String =
    title.split(' ', '-', '_').filter { it.firstOrNull()?.isLetterOrDigit() == true }
        .take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "♪" }

/** 75_000 -> "1:15", 3_725_000 -> "1:02:05". */
fun formatDuration(ms: Long): String {
    val totalSec = (ms.coerceAtLeast(0)) / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "$h:${m.pad()}:${s.pad()}" else "$m:${s.pad()}"
}

/** Human duration for lists: "45 min", "1 h 5 min". */
fun formatMinutes(ms: Long): String {
    val totalMin = (ms / 60_000).coerceAtLeast(1)
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h == 0L -> "$m min"
        m == 0L -> "$h h"
        else -> "$h h $m min"
    }
}

private fun Long.pad() = toString().padStart(2, '0')
private fun Int.pad() = toString().padStart(2, '0')

private val monthNames = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

fun formatDate(epochMs: Long, timeZone: TimeZone = TimeZone.currentSystemDefault()): String {
    val d = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(timeZone).date
    return "${monthNames[d.monthNumber - 1]} ${d.dayOfMonth}, ${d.year}"
}

fun formatTime(epochMs: Long, timeZone: TimeZone = TimeZone.currentSystemDefault()): String {
    val t = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(timeZone)
    return "${t.hour.pad()}:${t.minute.pad()}"
}

fun formatDateTime(epochMs: Long, timeZone: TimeZone = TimeZone.currentSystemDefault()): String =
    "${formatDate(epochMs, timeZone)} ${formatTime(epochMs, timeZone)}"

@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier) = SectionLabel(text, modifier)

/** Strips HTML tags/entities commonly found in feed descriptions. */
internal fun plainText(html: String): String =
    html.replace(Regex("<br\\s*/?>|</p>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
