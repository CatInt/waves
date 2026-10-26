package io.waves.youtube

import io.ktor.client.HttpClient
import io.waves.feed.Feed
import io.waves.feed.FeedFetchException
import io.waves.model.Podcast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * A YouTube playlist as a show: the playlist's title and description, its videos as episodes, and the channel that
 * made it as the creator, whose avatar is the cover. The subscription is keyed by the playlist's Atom feed URL.
 *
 * The Atom feed lists the playlist's first 15 videos in playlist order, so a playlist that grows at the end
 * (oldest first) stops showing new videos once it has more than 15.
 */
class YoutubePlaylists internal constructor(
    private val client: HttpClient,
    private val channels: YoutubeChannels,
) {
    constructor(client: HttpClient) : this(client, YoutubeChannels(client))

    suspend fun feed(feedUrl: String, playlistId: String): Feed = coroutineScope {
        val pending = async { details(playlistId) }
        val xml = client.youtubeFeed(feedUrl, YoutubeUrls.playlistFeedUrl(playlistId))
            ?: throw FeedFetchException("This YouTube playlist is private or no longer exists")
        val parsed = YoutubeFeedParser.parse(feedUrl, xml)
        val owner = YoutubeFeedParser.owner(xml)
        val channelArt = async { owner?.let { channels.channelMetadata(it.channelId)?.artworkUrl } }
        val details = pending.await()
        val known = details?.durations.orEmpty()
        // A premiere still playing as live has no length on the playlist page yet.
        val looked = parsed.episodes.take(PLAYER_LOOKUPS)
            .filter { it.durationSeconds == null && it.guid !in known }
            .map { e -> async { channels.videoLength(e.guid)?.let { e.guid to it } } }
            .awaitAll().filterNotNull()
        val durations = known + looked
        val podcast = parsed.podcast.copy(
            title = details?.title?.takeIf { it.isNotBlank() } ?: parsed.podcast.title,
            // Always the channel, even when the playlist shares its name.
            author = owner?.name ?: parsed.podcast.author,
            description = details?.description?.takeIf { it.isNotBlank() } ?: parsed.podcast.description,
            // The channel's avatar keeps a creator's playlists looking like theirs; the playlist's own cover otherwise.
            artworkUrl = channelArt.await() ?: details?.artworkUrl ?: parsed.episodes.firstOrNull()?.artworkUrl
                ?: parsed.podcast.artworkUrl,
        )
        parsed.copy(
            podcast = podcast,
            episodes = parsed.episodes.map { e ->
                if (e.durationSeconds != null) e else e.copy(durationSeconds = durations[e.guid])
            },
        )
    }

    /** The channel that made the playlist, as a show to open. Null when the feed is gone or doesn't say. */
    suspend fun owner(playlistId: String): Podcast? {
        val url = YoutubeUrls.playlistFeedUrl(playlistId)
        val xml = client.youtubeFeed(url, url) ?: return null
        val owner = YoutubeFeedParser.owner(xml) ?: return null
        return Podcast(feedUrl = YoutubeUrls.feedUrl(owner.channelId), title = owner.name ?: owner.channelId)
    }

    /** Title, cover, description and video lengths from the playlist page. Null when YouTube doesn't answer. */
    internal suspend fun details(playlistId: String): PlaylistDetails? {
        val body = buildJsonObject {
            putJsonObject("context") { put("client", webClient()) }
            put("browseId", "VL$playlistId")
        }.toString()
        val text = try {
            client.postJson("https://www.youtube.com/youtubei/v1/browse?prettyPrint=false", body, WEB_USER_AGENT)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        return jsonObject(text)?.let(::parsePlaylistDetails)
    }
}

internal data class PlaylistDetails(
    val title: String?,
    val description: String?,
    val artworkUrl: String?,
    val durations: Map<String, Long>,
)

/**
 * A playlist `browse` response → its details. The cover is the microformat thumbnail, which YouTube serves square
 * for playlists (like a podcast cover); videos are `lockupViewModel`s, read like a channel's Videos tab.
 */
internal fun parsePlaylistDetails(root: JsonObject): PlaylistDetails? {
    val meta = root.obj("metadata")?.obj("playlistMetadataRenderer")
    val micro = root.obj("microformat")?.obj("microformatDataRenderer")
    if (meta == null && micro == null) return null
    val thumbs = micro?.obj("thumbnail")?.get("thumbnails") as? JsonArray
    return PlaylistDetails(
        title = meta?.string("title") ?: micro?.string("title"),
        description = meta?.string("description") ?: micro?.string("description"),
        artworkUrl = thumbs?.mapNotNull { (it as? JsonObject)?.string("url") }?.lastOrNull(),
        durations = parseVideoDurations(root),
    )
}
