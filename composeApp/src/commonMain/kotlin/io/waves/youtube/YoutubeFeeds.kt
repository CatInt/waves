package io.waves.youtube

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.waves.feed.Feed
import io.waves.feed.FeedFetchException
import io.waves.feed.FeedRepository
import io.waves.feed.RssFeedRepository
import io.waves.model.Podcast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * [FeedRepository] for the whole app: ordinary RSS goes to [RssFeedRepository], and a YouTube channel or playlist
 * feed URL is parsed from YouTube's Atom feed so it shows up as a podcast.
 */
class YoutubeFeedRepository(private val client: HttpClient) : FeedRepository {
    private val rss = RssFeedRepository(client)
    private val channels = YoutubeChannels(client)
    private val web = YoutubeWebFeed(channels)
    private val playlists = YoutubePlaylists(client, channels, web)

    /**
     * The subscription stays keyed by the channel feed URL, but episodes come from the channel's regular uploads
     * so Shorts stay out. A channel with no regular videos falls back to its channel feed, minus Shorts. When the
     * Atom feeds fail, the Videos tab stands in for them ([YoutubeWebFeed]).
     */
    override suspend fun fetch(feedUrl: String): Feed {
        YoutubeUrls.playlistIdOfFeed(feedUrl)?.let { return playlists.feed(feedUrl, it) }
        val channelId = YoutubeUrls.channelIdOfFeed(feedUrl) ?: return rss.fetch(feedUrl)
        var fromAtom = true
        val parsed = try {
            atomFeed(feedUrl, channelId)
        } catch (e: FeedFetchException) {
            fromAtom = false
            channels.videosTab(channelId)?.let { web.feed(feedUrl, parseListedVideos(it)) } ?: throw e
        }
        val (meta, durations) = coroutineScope {
            val meta = async { metadataOrNull(channelId) }
            // The Videos tab already gave the stand-in its lengths.
            val durations = async { if (fromAtom) durations(channelId, parsed) else emptyMap() }
            meta.await() to durations.await()
        }
        return parsed.copy(
            podcast = meta?.let { withMetadata(parsed.podcast, it) } ?: parsed.podcast,
            episodes = parsed.episodes.map { e ->
                if (e.durationSeconds != null) e else e.copy(durationSeconds = durations[e.guid])
            },
        )
    }

    private suspend fun atomFeed(feedUrl: String, channelId: String): Feed {
        val uploads = download(feedUrl, YoutubeUrls.uploadsFeedUrl(channelId))
        if (uploads != null) {
            // The playlist feed is titled "Videos"; its author is the channel.
            return YoutubeFeedParser.parse(feedUrl, uploads).let { feed ->
                feed.copy(podcast = feed.podcast.copy(title = feed.podcast.author ?: feed.podcast.title, author = null))
            }
        }
        val xml = download(feedUrl, YoutubeUrls.feedUrl(channelId))
            ?: throw FeedFetchException("Failed to download $feedUrl: HTTP 404")
        return YoutubeFeedParser.parse(feedUrl, xml)
    }

    /**
     * Lengths by video ID from the channel's Videos tab. A premiere shows a label there instead of a length, so the
     * newest few episodes still without one are asked for one by one.
     */
    private suspend fun durations(channelId: String, feed: Feed): Map<String, Long> = coroutineScope {
        val fromTab = channels.videoDurations(channelId)
        val missing = feed.episodes.take(PLAYER_LOOKUPS).map { it.guid }.filter { it !in fromTab }
        val looked = missing.map { id -> async { channels.videoLength(id)?.let { id to it } } }.awaitAll().filterNotNull()
        fromTab + looked
    }

    private suspend fun download(feedUrl: String, url: String): String? = client.youtubeFeed(feedUrl, url)

    /** Channel art and description. A failure here still leaves the episodes usable. */
    private suspend fun metadataOrNull(channelId: String): ChannelMetadata? = try {
        channels.channelMetadata(channelId)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun withMetadata(podcast: Podcast, meta: ChannelMetadata): Podcast {
        val title = meta.title?.takeIf { it.isNotBlank() } ?: podcast.title
        return podcast.copy(
            title = title,
            description = meta.description?.takeIf { it.isNotBlank() } ?: podcast.description,
            artworkUrl = meta.artworkUrl ?: podcast.artworkUrl,
        )
    }
}

/** Newest episodes whose length is looked up one by one when the Videos tab has none. */
internal const val PLAYER_LOOKUPS = 3

/** The Atom feed at [url] (for the subscription [feedUrl]), or null when YouTube has no such feed (404). */
internal suspend fun HttpClient.youtubeFeed(feedUrl: String, url: String): String? {
    val response = try {
        get(url) {
            header(HttpHeaders.UserAgent, RssFeedRepository.USER_AGENT)
            header(HttpHeaders.Accept, "application/atom+xml, application/xml;q=0.9, text/xml;q=0.8, */*;q=0.7")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw FeedFetchException("Failed to download $feedUrl: ${e.message}", e)
    }
    if (response.status == HttpStatusCode.NotFound) return null
    if (!response.status.isSuccess()) {
        throw FeedFetchException("Failed to download $feedUrl: HTTP ${response.status.value}")
    }
    return RssFeedRepository.decode(response.bodyAsBytes(), response.contentType()?.parameter("charset"))
}
