package io.waves.youtube

/** A YouTube link reduced to the thing Waves can turn into a show. */
sealed interface YoutubeTarget {
    /** Channel id (`UC` + 22 chars), already known. */
    data class Channel(val id: String) : YoutubeTarget

    /**
     * `/@handle`, `/c/` or `/user/` — the channel id still has to be looked up. [live] when the link is to the
     * channel's Live tab, which stands for its [LiveArchive].
     */
    data class Vanity(val url: String, val live: Boolean = false) : YoutubeTarget

    /** A single video. The show is that video's channel. */
    data class Video(val id: String) : YoutubeTarget

    /** A public playlist. It becomes its own show, with the channel that made it as the creator. */
    data class Playlist(val id: String) : YoutubeTarget

    /** A channel's past live streams, a show of their own apart from the channel's regular uploads. */
    data class LiveArchive(val channelId: String) : YoutubeTarget
}

/**
 * YouTube link shapes. Channel identity in the library is the public Atom feed
 * `https://www.youtube.com/feeds/videos.xml?channel_id=UC…` (a playlist's is `…?playlist_id=…`), and each
 * episode's audio URL is the stable watch URL (the real audio stream expires and is resolved at play time).
 */
object YoutubeUrls {
    fun feedUrl(channelId: String) = "https://www.youtube.com/feeds/videos.xml?channel_id=$channelId"

    fun playlistFeedUrl(playlistId: String) = "https://www.youtube.com/feeds/videos.xml?playlist_id=$playlistId"

    /**
     * Feed of the channel's regular uploads: its `UULF` playlist, which leaves out Shorts (and live streams)
     * that the channel feed mixes in. YouTube answers 404 for a channel with no regular videos.
     */
    fun uploadsFeedUrl(channelId: String) =
        "https://www.youtube.com/feeds/videos.xml?playlist_id=UULF${channelId.removePrefix("UC")}"

    /**
     * Feed of the channel's live streams, its `UULV` playlist: past streams, plus any live or scheduled right now.
     * Keyed like any playlist, so it's a show of its own. YouTube answers 404 for a channel that never streamed.
     */
    fun liveArchiveFeedUrl(channelId: String) = playlistFeedUrl(liveListId(channelId))

    /** The channel's live streams list, `UULV` + the channel id after its `UC`. */
    fun liveListId(channelId: String) = LIVE_PREFIX + channelId.removePrefix("UC")

    fun isLiveArchive(url: String) = channelIdOfLiveArchive(url) != null

    /** A channel's, playlist's or live archive's feed: a show from YouTube rather than from an RSS feed. */
    fun isYoutubeShow(url: String) = channelIdOfFeed(url) != null || playlistIdOfFeed(url) != null

    /** The channel whose live archive the feed at [url] is, or null for any other feed. */
    fun channelIdOfLiveArchive(url: String): String? =
        playlistIdOfFeed(url)?.let(::channelIdOfLiveList)

    fun watchUrl(videoId: String) = "https://www.youtube.com/watch?v=$videoId"

    fun looksLikeUrl(text: String): Boolean {
        val s = text.trim()
        return s.contains("youtube.com", ignoreCase = true) ||
            s.contains("youtu.be/", ignoreCase = true) ||
            s.startsWith("youtu.be", ignoreCase = true)
    }

    /** Channel id from a `feeds/videos.xml?channel_id=` URL, or null. */
    fun channelIdOfFeed(url: String): String? {
        val parts = partsOf(url) ?: return null
        if (!isYoutubeHost(parts.host)) return null
        if (!parts.path.equals("/feeds/videos.xml", ignoreCase = true)) return null
        return queryParam(parts.query, "channel_id")?.takeIf { isChannelId(it) }
    }

    /** Playlist id from a `feeds/videos.xml?playlist_id=` URL, or null. */
    fun playlistIdOfFeed(url: String): String? {
        val parts = partsOf(url) ?: return null
        if (!isYoutubeHost(parts.host)) return null
        if (!parts.path.equals("/feeds/videos.xml", ignoreCase = true)) return null
        return queryParam(parts.query, "playlist_id")?.takeIf { isPlaylistId(it) }
    }

    /** Video id when [url] is a watch / shorts / youtu.be link, or null for ordinary enclosures. */
    fun videoId(url: String): String? = when (val target = parse(url)) {
        is YoutubeTarget.Video -> target.id
        // A watch link opened from a playlist is still a playable video.
        is YoutubeTarget.Playlist, is YoutubeTarget.LiveArchive -> partsOf(url)?.let { videoIdIn(it) }
        else -> null
    }

    fun isChannelId(id: String) = channelId.matches(id)
    fun isVideoId(id: String) = videoIdPattern.matches(id)

    /**
     * A playlist that can have a public feed. Mixes (`RD…`) are generated per viewer; Watch later and Liked videos
     * (`WL`, `LL`) are private and too short to match anyway.
     */
    fun isPlaylistId(id: String) = playlistIdPattern.matches(id) && !id.startsWith("RD")

    fun parse(raw: String): YoutubeTarget? {
        val parts = partsOf(raw) ?: return null
        if (!isYoutubeHost(parts.host)) return null
        // A video opened from a playlist (`watch?v=…&list=…`) stands for the playlist: that's what the link shares.
        playlistIn(parts)?.let { return it }
        val host = parts.host.removePrefix("www.")
        if (host == "youtu.be" || host.endsWith(".youtu.be")) {
            val id = parts.path.trim('/').substringBefore('/')
            return id.takeIf { isVideoId(it) }?.let { YoutubeTarget.Video(it) }
        }
        val segments = parts.path.trim('/').split('/').filter { it.isNotEmpty() }.map { decode(it, plusIsSpace = false) }
        if (segments.isEmpty()) {
            return queryParam(parts.query, "v")?.takeIf { isVideoId(it) }?.let { YoutubeTarget.Video(it) }
        }
        val root = siteRoot(host)
        // A channel's Live tab (`/@name/streams`) stands for its live archive.
        val live = segments.getOrNull(if (segments[0].startsWith("@")) 1 else 2).equals("streams", ignoreCase = true)
        return when (segments[0].lowercase()) {
            "channel" -> segments.getOrNull(1)?.takeIf { isChannelId(it) }?.let {
                if (live) YoutubeTarget.LiveArchive(it) else YoutubeTarget.Channel(it)
            }
            "watch", "watch_popup" -> queryParam(parts.query, "v")?.takeIf { isVideoId(it) }?.let { YoutubeTarget.Video(it) }
            "shorts", "embed", "live", "v" -> segments.getOrNull(1)?.takeIf { isVideoId(it) }?.let { YoutubeTarget.Video(it) }
            "feeds" -> if (segments.getOrNull(1).equals("videos.xml", ignoreCase = true)) {
                queryParam(parts.query, "channel_id")?.takeIf { isChannelId(it) }?.let { YoutubeTarget.Channel(it) }
            } else null
            "c" -> segments.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { YoutubeTarget.Vanity("$root/c/$it", live) }
            "user" -> segments.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { YoutubeTarget.Vanity("$root/user/$it", live) }
            else -> when {
                segments[0].startsWith("@") -> {
                    val handle = segments[0].removePrefix("@")
                    handle.takeIf { it.isNotBlank() }?.let { YoutubeTarget.Vanity("$root/@$it", live) }
                }
                else -> queryParam(parts.query, "v")?.takeIf { isVideoId(it) }?.let { YoutubeTarget.Video(it) }
            }
        }
    }

    /**
     * The playlist a link names through `list=` (watch, `/playlist`, youtu.be) or `playlist_id=` (its feed). A
     * channel's uploads list (`UU…`, `UULF…`) is that channel; its live streams list (`UULV…`), its live archive.
     */
    private fun playlistIn(parts: Parts): YoutubeTarget? {
        val id = queryParam(parts.query, "list") ?: queryParam(parts.query, "playlist_id") ?: return null
        channelIdOfLiveList(id)?.let { return YoutubeTarget.LiveArchive(it) }
        val uploads = uploadsListPattern.matchEntire(id)
        if (uploads != null) return YoutubeTarget.Channel("UC" + uploads.groupValues[1])
        return id.takeIf { isPlaylistId(it) }?.let { YoutubeTarget.Playlist(it) }
    }

    /** The channel whose live streams list (`UULV…`) [playlistId] is, or null for any other playlist. */
    internal fun channelIdOfLiveList(playlistId: String): String? =
        playlistId.takeIf { it.startsWith(LIVE_PREFIX) }?.let { "UC" + it.removePrefix(LIVE_PREFIX) }?.takeIf { isChannelId(it) }

    private fun videoIdIn(parts: Parts): String? {
        val host = parts.host.removePrefix("www.")
        val fromPath = if (host == "youtu.be" || host.endsWith(".youtu.be")) parts.path.trim('/').substringBefore('/') else null
        return (fromPath ?: queryParam(parts.query, "v"))?.takeIf { isVideoId(it) }
    }

    private fun siteRoot(hostWithoutWww: String): String =
        if (hostWithoutWww == "music.youtube.com") "https://music.youtube.com" else "https://www.youtube.com"

    internal fun isYoutubeHost(host: String): Boolean {
        val h = host.removePrefix("www.")
        return h == "youtube.com" || h.endsWith(".youtube.com") || h == "youtu.be" || h.endsWith(".youtu.be")
    }

    private data class Parts(val host: String, val path: String, val query: String)

    private fun partsOf(raw: String): Parts? {
        var s = raw.trim()
        if (s.isEmpty()) return null
        if (!s.contains("://")) {
            val head = s.substringBefore('/').substringBefore('?').substringBefore('#')
            if (!head.contains('.')) return null
            s = "https://$s"
        }
        val afterScheme = s.substringAfter("://", "")
        if (afterScheme.isEmpty()) return null
        val noFragment = afterScheme.substringBefore('#')
        val beforeQuery = noFragment.substringBefore('?')
        val query = if ('?' in noFragment) noFragment.substringAfter('?') else ""
        val hostPort = beforeQuery.substringBefore('/')
        val host = hostPort.substringAfter('@').substringBefore(':').lowercase()
        if (host.isEmpty() || host.contains(' ')) return null
        val path = if ('/' in beforeQuery) "/" + beforeQuery.substringAfter('/') else "/"
        return Parts(host, if (path.length > 1) path.trimEnd('/') else path, query)
    }

    private fun queryParam(query: String, key: String): String? =
        query.split('&').firstNotNullOfOrNull { part ->
            if (part.substringBefore('=') != key) null
            else decode(part.substringAfter('=', ""), plusIsSpace = true).takeIf { it.isNotEmpty() }
        }

    private fun decode(s: String, plusIsSpace: Boolean): String {
        val src = if (plusIsSpace) s.replace('+', ' ') else s
        if ('%' !in src) return src
        // Escapes are UTF-8 bytes (a non-Latin handle is several per character), so decode them as a byte run.
        val out = ArrayList<Byte>(src.length)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            if (c == '%' && i + 2 < src.length) {
                val code = src.substring(i + 1, i + 3).toIntOrNull(16)
                if (code != null) {
                    out += code.toByte()
                    i += 3
                    continue
                }
            }
            c.toString().encodeToByteArray().forEach { out += it }
            i++
        }
        return out.toByteArray().decodeToString()
    }

    private const val LIVE_PREFIX = "UULV"
    private val channelId = Regex("""UC[0-9A-Za-z_-]{22}""")
    private val videoIdPattern = Regex("""[0-9A-Za-z_-]{11}""")
    private val playlistIdPattern = Regex("""[0-9A-Za-z_-]{12,64}""")
    private val uploadsListPattern = Regex("""UU(?:[A-Z]{2})?([0-9A-Za-z_-]{22})""")
}

class YoutubeException(message: String, cause: Throwable? = null) : Exception(message, cause)
