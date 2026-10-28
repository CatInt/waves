package io.waves.youtube

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.waves.feed.FeedDates
import io.waves.feed.FeedFetchException
import io.waves.model.Podcast
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Turns a pasted YouTube link into a [Podcast] whose feed URL is YouTube's public Atom feed. Handles, `/c/` and
 * `/user/` names, and single videos (the show is that video's channel) become the channel; a playlist link becomes
 * the playlist. Either way it's the same kind of subscription as an Apple show.
 */
class YoutubeChannels(private val client: HttpClient) {

    suspend fun resolve(input: String): Podcast {
        val target = YoutubeUrls.parse(input.trim())
            ?: throw YoutubeException("Paste a YouTube channel or playlist link, like youtube.com/@name")
        val channelId = when (target) {
            is YoutubeTarget.Channel -> target.id
            is YoutubeTarget.Vanity -> browseId(target.url)
            is YoutubeTarget.Video -> channelOfVideo(target.id)
            is YoutubeTarget.Playlist -> return playlist(target.id)
        }
        return podcast(channelId)
    }

    /**
     * The channel behind a YouTube show, to open from its creator line: for a playlist, the channel that made it.
     * Null for other shows, or when YouTube doesn't say.
     */
    suspend fun creator(feedUrl: String): Podcast? {
        val playlistId = YoutubeUrls.playlistIdOfFeed(feedUrl) ?: return null
        return try {
            YoutubePlaylists(client, this).owner(playlistId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun playlist(playlistId: String): Podcast {
        val feedUrl = YoutubeUrls.playlistFeedUrl(playlistId)
        return try {
            YoutubePlaylists(client, this).feed(feedUrl, playlistId).podcast
        } catch (e: FeedFetchException) {
            throw YoutubeException(e.message ?: "Couldn't load that playlist", e)
        }
    }

    private suspend fun channelOfVideo(videoId: String): String {
        val author = authorUrl(videoId) ?: throw YoutubeException("Couldn't find the channel for that video")
        return when (val target = YoutubeUrls.parse(author)) {
            is YoutubeTarget.Channel -> target.id
            is YoutubeTarget.Vanity -> browseId(target.url)
            else -> throw YoutubeException("Couldn't find the channel for that video")
        }
    }

    private suspend fun authorUrl(videoId: String): String? {
        val response = client.get("https://www.youtube.com/oembed") {
            parameter("url", YoutubeUrls.watchUrl(videoId))
            parameter("format", "json")
            header(HttpHeaders.UserAgent, WEB_USER_AGENT)
        }
        if (!response.status.isSuccess()) return null
        return jsonObject(response.bodyAsText())?.string("author_url")
    }

    private suspend fun browseId(url: String): String {
        val body = buildJsonObject {
            putJsonObject("context") { put("client", webClient()) }
            put("url", url)
        }.toString()
        val text = client.postJson(
            "https://www.youtube.com/youtubei/v1/navigation/resolve_url?prettyPrint=false",
            body,
            WEB_USER_AGENT,
        )
        val id = jsonObject(text)
            ?.obj("endpoint")?.obj("browseEndpoint")?.string("browseId")
            ?.takeIf { YoutubeUrls.isChannelId(it) }
        return id ?: throw YoutubeException("That link isn't a YouTube channel")
    }

    private suspend fun podcast(channelId: String): Podcast {
        val meta = channelMetadata(channelId)
        val title = meta?.title?.takeIf { it.isNotBlank() } ?: channelId
        return Podcast(
            feedUrl = YoutubeUrls.feedUrl(channelId),
            title = title,
            description = meta?.description?.takeIf { it.isNotBlank() },
            artworkUrl = meta?.artworkUrl,
        )
    }

    internal suspend fun channelMetadata(channelId: String): ChannelMetadata? {
        val body = buildJsonObject {
            putJsonObject("context") { put("client", webClient()) }
            put("browseId", channelId)
        }.toString()
        val text = try {
            client.postJson(
                "https://www.youtube.com/youtubei/v1/browse?prettyPrint=false",
                body,
                WEB_USER_AGENT,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        val meta = jsonObject(text)?.obj("metadata")?.obj("channelMetadataRenderer") ?: return null
        val thumbs = meta.obj("avatar")?.get("thumbnails") as? JsonArray
        val art = thumbs?.mapNotNull { (it as? JsonObject)?.string("url") }?.lastOrNull()
        return ChannelMetadata(
            title = meta.string("title"),
            description = meta.string("description"),
            artworkUrl = art,
        )
    }

    /**
     * Lengths in seconds of the channel's newest regular uploads (about 30, more than the Atom feed's 15), by video
     * ID, from its Videos tab. The feed itself carries no durations. Empty when YouTube doesn't answer.
     */
    internal suspend fun videoDurations(channelId: String): Map<String, Long> =
        videosTab(channelId)?.let(::parseVideoDurations).orEmpty()

    /** The channel's Videos tab, newest first, as `browse` returns it. Null when YouTube doesn't answer. */
    internal suspend fun videosTab(channelId: String): JsonObject? {
        val body = buildJsonObject {
            putJsonObject("context") { put("client", webClient()) }
            put("browseId", channelId)
            put("params", VIDEOS_TAB_PARAMS)
        }.toString()
        val text = try {
            client.postJson("https://www.youtube.com/youtubei/v1/browse?prettyPrint=false", body, WEB_USER_AGENT)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        return jsonObject(text)
    }

    /**
     * One video's length in seconds from the player API, which knows it even while a premiere still plays as live
     * (the Videos tab shows "PREMIERE" then). Null for a stream in progress or when YouTube doesn't answer.
     */
    internal suspend fun videoLength(videoId: String): Long? = videoDetails(videoId)?.seconds

    /** One video's publish time, description, length and channel from the player API; null when YouTube doesn't answer. */
    internal suspend fun videoDetails(videoId: String): VideoDetails? {
        val body = buildJsonObject {
            putJsonObject("context") { put("client", webClient()) }
            put("videoId", videoId)
        }.toString()
        val text = try {
            client.postJson("https://www.youtube.com/youtubei/v1/player?prettyPrint=false", body, WEB_USER_AGENT)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        return jsonObject(text)?.let(::parseVideoDetails)
    }
}

internal fun webClient() = buildJsonObject {
    put("clientName", "WEB")
    put("clientVersion", WEB_CLIENT_VERSION)
    put("hl", "en")
    put("gl", "US")
}

internal data class ChannelMetadata(val title: String?, val description: String?, val artworkUrl: String?)

/** `browse` params that select a channel's Videos tab. */
private const val VIDEOS_TAB_PARAMS = "EgZ2aWRlb3PyBgQKAjoA"

internal val CLOCK_TIME = Regex("""\d{1,2}(:\d{2}){1,2}""")

/**
 * Video ID → seconds from a Videos tab `browse` response. Videos come as `lockupViewModel` (the length is the
 * thumbnail badge, e.g. "43:23") or, in older layouts, `videoRenderer` with `lengthText`. Live and upcoming
 * videos have a word badge instead and are left out.
 */
internal fun parseVideoDurations(root: JsonElement): Map<String, Long> {
    val out = LinkedHashMap<String, Long>()
    fun add(id: String?, length: String?) {
        val seconds = length?.takeIf { CLOCK_TIME.matches(it) }?.let(FeedDates::parseDuration) ?: return
        if (id != null && id !in out) out[id] = seconds
    }
    fun walk(node: JsonElement) {
        when (node) {
            is JsonObject -> {
                val lockup = node.obj("lockupViewModel")
                val renderer = node.obj("videoRenderer")
                when {
                    lockup != null ->
                        add(lockup.string("contentId"), badgeTexts(lockup).firstOrNull { CLOCK_TIME.matches(it) })
                    renderer != null ->
                        add(renderer.string("videoId"), renderer.obj("lengthText")?.string("simpleText"))
                    else -> node.values.forEach(::walk)
                }
            }
            is JsonArray -> node.forEach(::walk)
            else -> Unit
        }
    }
    walk(root)
    return out
}

internal fun badgeTexts(node: JsonElement): Sequence<String> = when (node) {
    is JsonObject -> {
        val own = node.obj("thumbnailBadgeViewModel")?.string("text")
        (if (own != null) sequenceOf(own) else emptySequence()) + node.values.asSequence().flatMap(::badgeTexts)
    }
    is JsonArray -> node.asSequence().flatMap(::badgeTexts)
    else -> emptySequence()
}

internal const val WEB_USER_AGENT =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

internal const val WEB_CLIENT_VERSION = "2.20260708.00.00"
