package io.waves.download

import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.waves.model.Episode
import io.waves.youtube.YoutubeUrls

/** HTTP client settings for downloads: follow (also https->http) tracking redirects, no overall request timeout. */
fun HttpClientConfig<*>.downloadDefaults() {
    followRedirects = true
    expectSuccess = false
    install(HttpRedirect) {
        checkHttpMethod = false
        // Podcast tracking prefixes (podtrac, chartable, ...) sometimes bounce through plain http.
        allowHttpsDowngrade = true
    }
    install(HttpTimeout) {
        connectTimeoutMillis = 30_000
        socketTimeoutMillis = 60_000
    }
    install(UserAgent) { agent = "Waves/1.0 (podcast player)" }
}

/** Runtime copy of the `download` table from Download.sq, for databases created before that table existed. */
internal const val CREATE_DOWNLOAD_TABLE = """
CREATE TABLE IF NOT EXISTS download (
    feedUrl TEXT NOT NULL,
    guid TEXT NOT NULL,
    title TEXT NOT NULL,
    audioUrl TEXT NOT NULL,
    status TEXT NOT NULL,
    bytesDownloaded INTEGER NOT NULL DEFAULT 0,
    totalBytes INTEGER,
    filePath TEXT NOT NULL,
    error TEXT,
    createdAt INTEGER NOT NULL,
    updatedAt INTEGER NOT NULL,
    completedAt INTEGER,
    PRIMARY KEY (feedUrl, guid)
)"""

internal const val PART_SUFFIX = ".part"

/** Stable, filesystem-safe file name: 64-bit FNV-1a of the episode id plus the enclosure's extension. */
internal fun downloadFileName(episode: Episode): String =
    fnv1a64(episode.id.toString()) + "." + audioExtension(episode.audioUrl)

internal fun fnv1a64(s: String): String {
    var h = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
    for (b in s.encodeToByteArray()) {
        h = h xor (b.toLong() and 0xff)
        h *= 0x100000001b3L
    }
    return h.toULong().toString(16).padStart(16, '0')
}

/** Extension from the URL path (`.../ep.m4a?x=1` -> `m4a`), defaulting to `mp3`. Players pick demuxers by it. */
internal fun audioExtension(url: String): String {
    // Watch URLs have no file extension; playback resolves them to AAC in an MP4 container.
    if (YoutubeUrls.videoId(url) != null) return "m4a"
    return explicitAudioExtension(url) ?: "mp3"
}

/** The extension the URL path spells out, or null when it has none (e.g. `/videoplayback?…`). */
internal fun explicitAudioExtension(url: String): String? {
    val path = url.substringBefore('#').substringBefore('?').substringAfterLast('/')
    val ext = path.substringAfterLast('.', "").lowercase()
    return ext.takeIf { it.length in 2..5 && it.all { c -> c in 'a'..'z' || c in '0'..'9' } }
}

/** `file://` URI for an absolute path, percent-encoding everything outside the unreserved set (spaces etc.). */
internal fun fileUri(absolutePath: String): String {
    val sb = StringBuilder("file://")
    if (!absolutePath.startsWith('/')) sb.append('/')
    for (b in absolutePath.replace('\\', '/').encodeToByteArray()) {
        val c = (b.toInt() and 0xff)
        val ch = c.toChar()
        if (c < 0x80 && (ch.isLetterOrDigit() || ch in "-._~/")) sb.append(ch)
        else sb.append('%').append(HEX[c shr 4]).append(HEX[c and 0xf])
    }
    return sb.toString()
}

private const val HEX = "0123456789ABCDEF"

internal data class ContentRange(val start: Long?, val total: Long?)

// Parses "bytes 100-199/1000", "bytes 100-199/<star>" and "bytes <star>/1000" (star = unknown / unsatisfied range).
internal fun parseContentRange(value: String?): ContentRange? {
    val v = value?.trim()?.removePrefix("bytes")?.trim() ?: return null
    val range = v.substringBefore('/', "")
    val total = v.substringAfter('/', "").trim().toLongOrNull()
    val start = if (range == "*") null else range.substringBefore('-').trim().toLongOrNull()
    return ContentRange(start, total)
}

class DownloadException(message: String) : Exception(message)
