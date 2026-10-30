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
 * (oldest first) stops showing new videos once it has more than 15. When it fails, the same first videos on the
 * playlist's page stand in for it ([YoutubeWebFeed]).
 *
 * A channel's live streams list ([YoutubeUrls.liveArchiveFeedUrl]) is its live archive: titled after the channel, and
 * only streams that have ended, so a stream still live or scheduled waits until there's a recording to play.
 */
class YoutubePlaylists internal constructor(
    private val client: HttpClient,
    private val channels: YoutubeChannels,
    private val web: YoutubeWebFeed = YoutubeWebFeed(channels),
) {
    constructor(client: HttpClient) : this(client, YoutubeChannels(client))

    suspend fun feed(feedUrl: String, playlistId: String): Feed = coroutineScope {
        val live = YoutubeUrls.channelIdOfLiveList(playlistId) != null
        val pending = async { page(playlistId) }
        val (fetched, owner) = try {
            val xml = client.youtubeFeed(feedUrl, YoutubeUrls.playlistFeedUrl(playlistId))
                ?: throw FeedFetchException(
                    if (live) "This channel hasn't streamed live" else "This YouTube playlist is private or no longer exists",
                )
            YoutubeFeedParser.parse(feedUrl, xml) to YoutubeFeedParser.owner(xml)
        } catch (e: FeedFetchException) {
            val page = pending.await() ?: throw e
            val listed = parseListedVideos(page).let { videos -> if (live) videos.filter { it.seconds != null } else videos }
            (web.feed(feedUrl, listed) ?: throw e) to parsePlaylistOwner(page)
        }
        // The page shows a stream that's live or scheduled with a word badge where an ended one has its length.
        val unfinished = if (live) {
            pending.await()?.let(::parseListedVideos).orEmpty().filter { it.seconds == null }.map { it.id }.toSet()
        } else {
            emptySet()
        }
        val parsed = fetched.copy(episodes = fetched.episodes.filter { it.guid !in unfinished })
        val channelArt = async { owner?.let { channels.channelMetadata(it.channelId)?.artworkUrl } }
        val details = pending.await()?.let(::parsePlaylistDetails)
        val known = details?.durations.orEmpty()
        // A premiere still playing as live has no length on the playlist page yet.
        val looked = parsed.episodes.take(PLAYER_LOOKUPS)
            .filter { it.durationSeconds == null && it.guid !in known }
            .map { e -> async { channels.videoLength(e.guid)?.let { e.guid to it } } }
            .awaitAll().filterNotNull()
        val durations = known + looked
        val podcast = parsed.podcast.copy(
            // A live archive's own title is just "Live streams".
            title = owner?.name?.takeIf { live }?.let(::liveArchiveTitle)
                ?: details?.title?.takeIf { it.isNotBlank() } ?: parsed.podcast.title,
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

    /** The channel that made the playlist, as a show to open. Null when neither the feed nor the page says. */
    suspend fun owner(playlistId: String): Podcast? {
        val url = YoutubeUrls.playlistFeedUrl(playlistId)
        val fromFeed = try {
            client.youtubeFeed(url, url)?.let(YoutubeFeedParser::owner)
        } catch (_: FeedFetchException) {
            null
        }
        val owner = fromFeed ?: page(playlistId)?.let(::parsePlaylistOwner) ?: return null
        return Podcast(feedUrl = YoutubeUrls.feedUrl(owner.channelId), title = owner.name ?: owner.channelId)
    }

    /** The playlist's page, as `browse` returns it. Null when YouTube doesn't answer. */
    internal suspend fun page(playlistId: String): JsonObject? {
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
        return jsonObject(text)
    }
}

/** A channel's live archive's title, after the channel: "NBC News · Lived". */
fun liveArchiveTitle(channel: String) = "$channel · Lived"

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
