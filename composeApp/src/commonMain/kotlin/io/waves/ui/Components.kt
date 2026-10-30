package io.waves.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
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
            liveArchive -> CornerRibbon("LIVED", LiveRed, ribbonFor)
            playlist != null -> CornerRibbon(null, LocalPlaylistColors.current.color(playlist), ribbonFor)
        }
    }
}

/**
 * A ribbon of [color] filling the cover's top-left corner, sized to the cover, reading [label] along its edge:
 * "LIVED" (the streams are over) for a live archive, nothing for a playlist, whose colour says it. The smaller the
 * cover the more of it the corner takes, so the word still reads on a list's cover; on the smallest (a menu's) the
 * corner goes without it.
 */
@Composable
private fun BoxScope.CornerRibbon(label: String?, color: Color, fixedSide: (() -> Dp)?) {
    BoxWithConstraints(
        Modifier.matchParentSize().drawBehind {
            val leg = Ribbon.of(fixedSide?.invoke() ?: minOf(size.width, size.height).toDp()).leg.toPx()
            val corner = Path().apply {
                moveTo(0f, 0f)
                lineTo(leg, 0f)
                lineTo(0f, leg)
                close()
            }
            drawPath(corner, color)
        },
    ) {
        val side = fixedSide?.invoke() ?: minOf(maxWidth, maxHeight)
        if (label == null || side < 36.dp) return@BoxWithConstraints
        val ribbon = Ribbon.of(side)
        val length = ribbon.line * SQRT2
        // The word runs along the corner's long edge, from [Ribbon.line] along the top to as far down the side.
        Box(
            Modifier.align(Alignment.TopStart)
                .offset(x = ribbon.line / 2 - length / 2, y = ribbon.line / 2 - ribbon.text / 2)
                .requiredSize(length, ribbon.text)
                .graphicsLayer { rotationZ = -45f },
            contentAlignment = Alignment.Center,
        ) {
            val text = with(LocalDensity.current) { ribbon.text.toSp() }
            Text(
                label,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = text,
                // Glyphs sit in a box of their own size, so the word fills the ribbon without line spacing.
                lineHeight = text,
                letterSpacing = (0.5f * ribbon.large).sp,
                maxLines = 1,
                softWrap = false,
                // Font padding may make the line a touch taller than the ribbon; let it overhang rather than clip.
                modifier = Modifier.wrapContentSize(unbounded = true),
            )
        }
    }
}

/**
 * The corner ribbon on a cover whose shorter side is [side]: how far along each edge the corner reaches ([leg]), the
 * word's size ([text]), and where the word's middle crosses the edges ([line]): a little in from the long edge,
 * toward the corner.
 */
private class Ribbon(val large: Float, val leg: Dp, val text: Dp) {
    val line: Dp get() = leg - text * 0.95f

    companion object {
        /**
         * List-sized covers (48 dp and under) give the corner more of themselves than large ones (80 dp and up), and
         * in between it eases from one to the other, so a cover flying between screens grows its corner smoothly.
         */
        fun of(side: Dp): Ribbon {
            val large = ((side - 48.dp) / 32.dp).coerceIn(0f, 1f)
            return Ribbon(large, side * lerp(0.42f, 0.34f, large), side * lerp(0.12f, 0.1f, large))
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
