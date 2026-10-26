package io.waves.youtube

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val PLAYLIST = "PLexamplePlaylist000000000000000000"

class YoutubeChannelsTest {
    private val channel = "UCexampleChannel00000000"
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun handleBecomesAPodcastOnTheAtomFeed() = runTest {
        val paths = mutableListOf<String>()
        val client = client { path ->
            paths += path
            when {
                path.endsWith("/navigation/resolve_url") ->
                    """{"endpoint":{"browseEndpoint":{"browseId":"$channel"}}}"""
                path.endsWith("/browse") -> BROWSE
                else -> error(path)
            }
        }
        val podcast = YoutubeChannels(client).resolve("https://www.youtube.com/@examplechannel/videos")
        assertEquals(YoutubeUrls.feedUrl(channel), podcast.feedUrl)
        assertEquals("Example Channel", podcast.title)
        assertEquals("Quality tech videos", podcast.description)
        assertEquals("https://yt3.example/big.jpg", podcast.artworkUrl)
        assertTrue(paths.any { it.endsWith("/navigation/resolve_url") })
    }

    @Test
    fun channelUrlSkipsResolve() = runTest {
        val paths = mutableListOf<String>()
        val client = client { path ->
            paths += path
            BROWSE
        }
        val podcast = YoutubeChannels(client).resolve("https://m.youtube.com/channel/$channel")
        assertEquals("Example Channel", podcast.title)
        assertTrue(paths.none { it.contains("resolve_url") })
    }

    @Test
    fun videoLinkOpensItsChannel() = runTest {
        val client = client { path ->
            when {
                path.endsWith("/oembed") -> """{"author_url":"https://www.youtube.com/@anotherchannel","title":"First video"}"""
                path.endsWith("/navigation/resolve_url") -> """{"endpoint":{"browseEndpoint":{"browseId":"UCanotherChannel00000000"}}}"""
                path.endsWith("/browse") -> BROWSE.replace(channel, "UCanotherChannel00000000")
                else -> error(path)
            }
        }
        val podcast = YoutubeChannels(client).resolve("https://youtu.be/firstVideo1")
        assertEquals(YoutubeUrls.feedUrl("UCanotherChannel00000000"), podcast.feedUrl)
    }

    @Test
    fun playlistLinkBecomesAShowWithItsChannelAsCreator() = runTest {
        val podcast = YoutubeChannels(playlistClient()).resolve("https://www.youtube.com/playlist?list=$PLAYLIST&si=x")
        assertEquals(YoutubeUrls.playlistFeedUrl(PLAYLIST), podcast.feedUrl)
        assertEquals("Best Talks", podcast.title)
        assertEquals("Example Channel", podcast.author)
        assertEquals("The good ones", podcast.description)
        assertEquals("https://yt3.example/big.jpg", podcast.artworkUrl) // the channel's avatar
    }

    @Test
    fun playlistCoverIsUsedWhenTheChannelDoesNotAnswer() = runTest {
        val podcast = YoutubeChannels(playlistClient(channelAnswers = false)).resolve("https://www.youtube.com/playlist?list=$PLAYLIST")
        assertEquals("https://i.ytimg.com/pl_c/$PLAYLIST/studio_square_thumbnail.jpg", podcast.artworkUrl)
        assertEquals("Example Channel", podcast.author)
    }

    @Test
    fun playlistFeedHasEpisodesWithDurationsAndKeepsTheCreatorWhenNamedLikeTheChannel() = runTest {
        val stored = YoutubeUrls.playlistFeedUrl(PLAYLIST)
        val feed = YoutubeFeedRepository(playlistClient(atomTitle = "Example Channel", pageTitle = null)).fetch(stored)
        assertEquals(stored, feed.podcast.feedUrl)
        assertEquals("Example Channel", feed.podcast.title)
        assertEquals("Example Channel", feed.podcast.author)
        assertEquals(listOf("abcdefghijk", "lmnopqrstuv"), feed.episodes.map { it.guid })
        assertEquals(listOf(2603L, 3725L), feed.episodes.map { it.durationSeconds })
        assertTrue(feed.episodes.all { it.podcastFeedUrl == stored })
    }

    @Test
    fun privatePlaylistSaysSo() = runTest {
        val client = HttpClient(MockEngine { respond("no", HttpStatusCode.NotFound) })
        val error = assertFailsWith<YoutubeException> {
            YoutubeChannels(client).resolve("https://www.youtube.com/playlist?list=$PLAYLIST")
        }
        assertTrue(error.message!!.contains("private"), error.message)
    }

    @Test
    fun creatorOfAPlaylistIsItsChannel() = runTest {
        val channels = YoutubeChannels(playlistClient())
        val creator = channels.creator(YoutubeUrls.playlistFeedUrl(PLAYLIST))!!
        assertEquals(YoutubeUrls.feedUrl(channel), creator.feedUrl)
        assertEquals("Example Channel", creator.title)
        assertEquals(null, channels.creator(YoutubeUrls.feedUrl(channel)))
    }

    @Test
    fun linkThatIsNeitherIsRejected() = runTest {
        val client = client { error("unused") }
        val error = assertFailsWith<YoutubeException> {
            YoutubeChannels(client).resolve("https://www.youtube.com/playlist?list=RDmix")
        }
        assertTrue(error.message!!.contains("playlist"))
    }

    private fun playlistClient(
        atomTitle: String = "Best Talks",
        pageTitle: String? = "Best Talks",
        channelAnswers: Boolean = true,
    ) = HttpClient(MockEngine { req ->
        when {
            req.url.parameters["playlist_id"] == PLAYLIST -> respond(
                """
                <feed xmlns:yt="http://www.youtube.com/xml/schemas/2015" xmlns="http://www.w3.org/2005/Atom">
                  <yt:playlistId>$PLAYLIST</yt:playlistId>
                  <yt:channelId>$channel</yt:channelId>
                  <title>$atomTitle</title>
                  <author><name>Example Channel</name><uri>https://www.youtube.com/channel/$channel</uri></author>
                  <entry><yt:videoId>abcdefghijk</yt:videoId><title>First talk</title></entry>
                  <entry><yt:videoId>lmnopqrstuv</yt:videoId><title>Second talk</title></entry>
                </feed>
                """.trimIndent(),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/atom+xml"),
            )
            req.url.encodedPath.endsWith("/browse") && "VL$PLAYLIST" in (req.body as TextContent).text -> respond(
                playlistPage(pageTitle), HttpStatusCode.OK, jsonHeaders,
            )
            channelAnswers && req.url.encodedPath.endsWith("/browse") && "\"$channel\"" in (req.body as TextContent).text ->
                respond(BROWSE, HttpStatusCode.OK, jsonHeaders)
            else -> respond("no", HttpStatusCode.NotFound)
        }
    })

    private fun playlistPage(title: String?) = """
        {"metadata":{"playlistMetadataRenderer":{${title?.let { "\"title\":\"$it\"," } ?: ""}"description":"The good ones"}},
         "microformat":{"microformatDataRenderer":{"thumbnail":{"thumbnails":[
           {"url":"https://i.ytimg.com/pl_c/$PLAYLIST/studio_square_thumbnail.jpg","width":400}]}}},
         "contents":${VIDEOS_TAB.substringAfter("{\"contents\":").dropLast(1)}}
    """.trimIndent()

    @Test
    fun feedRepositoryParsesAtomAndKeepsTheCallersFeedUrl() = runTest {
        val stored = "http://www.youtube.com/feeds/videos.xml?channel_id=$channel"
        val client = HttpClient(MockEngine { req ->
            when {
                req.url.encodedPath.endsWith("/feeds/videos.xml") ->
                    respond(ATOM, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/atom+xml"))
                req.url.encodedPath.endsWith("/browse") -> respond(BROWSE, HttpStatusCode.OK, jsonHeaders)
                else -> respond("no", HttpStatusCode.NotFound)
            }
        })
        val feed = YoutubeFeedRepository(client).fetch(stored)
        assertEquals(stored, feed.podcast.feedUrl)
        assertEquals("Example Channel", feed.podcast.title)
        assertEquals("https://yt3.example/big.jpg", feed.podcast.artworkUrl)
        assertEquals("https://www.youtube.com/watch?v=abcdefghijk", feed.episodes.single().audioUrl)
        assertEquals(stored, feed.episodes.single().podcastFeedUrl)
    }

    @Test
    fun feedRepositoryReadsRegularUploadsNotShorts() = runTest {
        val requested = mutableListOf<String>()
        val uploads = """
            <feed xmlns:yt="http://www.youtube.com/xml/schemas/2015" xmlns="http://www.w3.org/2005/Atom">
              <title>Videos</title>
              <author><name>Example Uploader</name></author>
              <entry><yt:videoId>abcdefghijk</yt:videoId><title>Long one</title></entry>
            </feed>
        """.trimIndent()
        val client = HttpClient(MockEngine { req ->
            requested += req.url.toString()
            if (req.url.parameters["playlist_id"] == "UULF" + channel.removePrefix("UC")) {
                respond(uploads, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/atom+xml"))
            } else {
                respond("no", HttpStatusCode.NotFound)
            }
        })
        val stored = YoutubeUrls.feedUrl(channel)
        val feed = YoutubeFeedRepository(client).fetch(stored)
        assertEquals(stored, feed.podcast.feedUrl)
        assertEquals("Example Uploader", feed.podcast.title) // not the playlist's "Videos"
        assertEquals(listOf("abcdefghijk"), feed.episodes.map { it.guid })
        assertEquals(YoutubeUrls.uploadsFeedUrl(channel), requested.first())
    }

    @Test
    fun videosTabGivesDurations() {
        val parsed = parseVideoDurations(jsonObject(VIDEOS_TAB)!!)
        assertEquals(mapOf("abcdefghijk" to 2603L, "lmnopqrstuv" to 3725L, "oldlayout01" to 95L), parsed)
    }

    @Test
    fun feedEpisodesGetDurationsFromTheVideosTab() = runTest {
        val uploads = """
            <feed xmlns:yt="http://www.youtube.com/xml/schemas/2015" xmlns="http://www.w3.org/2005/Atom">
              <title>Videos</title>
              <author><name>Example Uploader</name></author>
              <entry><yt:videoId>abcdefghijk</yt:videoId><title>Long one</title></entry>
              <entry><yt:videoId>premiere123</yt:videoId><title>Premiering</title></entry>
              <entry><yt:videoId>livelive123</yt:videoId><title>Live now</title></entry>
            </feed>
        """.trimIndent()
        val client = HttpClient(MockEngine { req ->
            when {
                req.url.parameters["playlist_id"] != null ->
                    respond(uploads, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/atom+xml"))
                req.url.encodedPath.endsWith("/browse") -> respond(VIDEOS_TAB, HttpStatusCode.OK, jsonHeaders)
                req.url.encodedPath.endsWith("/player") -> {
                    val body = (req.body as TextContent).text
                    val length = if ("premiere123" in body) "3023" else "0"
                    respond("""{"videoDetails":{"lengthSeconds":"$length"}}""", HttpStatusCode.OK, jsonHeaders)
                }
                else -> respond("no", HttpStatusCode.NotFound)
            }
        })
        val feed = YoutubeFeedRepository(client).fetch(YoutubeUrls.feedUrl(channel))
        // The premiere's length comes from the player; the live stream has none yet.
        assertEquals(listOf(2603L, 3023L, null), feed.episodes.map { it.durationSeconds })
    }

    @Test
    fun channelWithoutRegularUploadsFallsBackToItsFeedMinusShorts() = runTest {
        val channelFeed = """
            <feed xmlns:yt="http://www.youtube.com/xml/schemas/2015" xmlns="http://www.w3.org/2005/Atom">
              <title>Shorts Only</title>
              <entry>
                <yt:videoId>shortshort1</yt:videoId><title>Short</title>
                <link rel="alternate" href="https://www.youtube.com/shorts/shortshort1"/>
              </entry>
              <entry>
                <yt:videoId>abcdefghijk</yt:videoId><title>Premiere</title>
                <link rel="alternate" href="https://www.youtube.com/watch?v=abcdefghijk"/>
              </entry>
            </feed>
        """.trimIndent()
        val client = HttpClient(MockEngine { req ->
            if (req.url.parameters["channel_id"] == channel) {
                respond(channelFeed, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/atom+xml"))
            } else {
                respond("no", HttpStatusCode.NotFound)
            }
        })
        val feed = YoutubeFeedRepository(client).fetch(YoutubeUrls.feedUrl(channel))
        assertEquals("Shorts Only", feed.podcast.title)
        assertEquals(listOf("abcdefghijk"), feed.episodes.map { it.guid })
    }

    @Test
    fun feedRepositoryLeavesOrdinaryRssAlone() = runTest {
        var fetched: String? = null
        val client = HttpClient(MockEngine { req ->
            fetched = req.url.toString()
            respond(
                """<?xml version="1.0"?><rss><channel><title>Show</title></channel></rss>""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/rss+xml"),
            )
        })
        val feed = YoutubeFeedRepository(client).fetch("https://example.com/feed.xml")
        assertEquals("Show", feed.podcast.title)
        assertEquals("https://example.com/feed.xml", fetched)
    }

    @Test
    fun playerResponseIsTurnedIntoTheAudioUrl() = runTest {
        val client = client { _ ->
            """
            {"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[
              {"mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","bitrate":128000,"url":"https://rr.googlevideo.com/videoplayback?id=aac"}
            ]}}
            """.trimIndent()
        }
        assertEquals(
            "https://rr.googlevideo.com/videoplayback?id=aac",
            YoutubeAudio.streamUrl(client, "abcdefghijk"),
        )
    }

    private fun client(bodyFor: (String) -> String) = HttpClient(MockEngine { req ->
        respond(bodyFor(req.url.encodedPath), HttpStatusCode.OK, jsonHeaders)
    })

    private val BROWSE = """
        {"metadata":{"channelMetadataRenderer":{
          "title":"Example Channel",
          "description":"Quality tech videos",
          "avatar":{"thumbnails":[
            {"url":"https://yt3.example/small.jpg","width":88},
            {"url":"https://yt3.example/big.jpg","width":900}
          ]}
        }}}
    """.trimIndent()

    private val VIDEOS_TAB = """
        {"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"richGridRenderer":{"contents":[
          {"richItemRenderer":{"content":{"lockupViewModel":{"contentId":"abcdefghijk","contentImage":{"thumbnailViewModel":{
            "overlays":[{"thumbnailBottomOverlayViewModel":{"badges":[{"thumbnailBadgeViewModel":{"text":"43:23"}}]}}]}}}}}},
          {"richItemRenderer":{"content":{"lockupViewModel":{"contentId":"lmnopqrstuv","contentImage":{"thumbnailViewModel":{
            "overlays":[{"thumbnailBottomOverlayViewModel":{"badges":[{"thumbnailBadgeViewModel":{"text":"1:02:05"}}]}}]}}}}}},
          {"richItemRenderer":{"content":{"lockupViewModel":{"contentId":"livelive123","contentImage":{"thumbnailViewModel":{
            "overlays":[{"thumbnailBottomOverlayViewModel":{"badges":[{"thumbnailBadgeViewModel":{"text":"LIVE"}}]}}]}}}}}},
          {"richItemRenderer":{"content":{"videoRenderer":{"videoId":"oldlayout01","lengthText":{"simpleText":"1:35"}}}}}
        ]}}}}]}}}
    """.trimIndent()

    private val ATOM = """
        <feed xmlns:yt="http://www.youtube.com/xml/schemas/2015">
          <title>From atom</title>
          <entry>
            <yt:videoId>abcdefghijk</yt:videoId>
            <title>Hello</title>
            <published>2026-10-01T00:00:00Z</published>
          </entry>
        </feed>
    """.trimIndent()
}
