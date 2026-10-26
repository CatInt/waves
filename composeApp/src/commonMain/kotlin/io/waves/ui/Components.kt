package io.waves.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
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
    }
}

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
