package io.waves.feed

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RssFeedRepositoryTest {

    @Test
    fun fetchesAndParses() = runTest {
        var userAgent: String? = null
        val client = HttpClient(MockEngine { req ->
            userAgent = req.headers[HttpHeaders.UserAgent]
            assertEquals(FeedFixtures.FEED_URL, req.url.toString())
            respond(FeedFixtures.rss, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/rss+xml; charset=utf-8"))
        })
        val feed = RssFeedRepository(client).fetch(FeedFixtures.FEED_URL)
        assertEquals("Cat & Mouse's “Show”", feed.podcast.title)
        assertEquals(4, feed.episodes.size)
        assertNotNull(userAgent)
    }

    @Test
    fun latin1FeedIsDecodedPerXmlDeclaration() = runTest {
        val xml = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><rss><channel><title>Café</title></channel></rss>"
        val bytes = ByteArray(xml.length) { xml[it].code.toByte() }
        val client = HttpClient(MockEngine { respond(bytes, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/xml")) })
        assertEquals("Café", RssFeedRepository(client).fetch("u").podcast.title)
    }

    @Test
    fun latestReadsOnlyTheStartAndKeepsItsWholeItems() = runTest {
        val items = (1..400).joinToString("") { n ->
            "<item><title>Episode $n</title><guid>g$n</guid><enclosure url=\"https://a/$n.mp3\" type=\"audio/mpeg\"/>" +
                "<itunes:duration>01:00:00</itunes:duration><description>${"words ".repeat(40)}</description></item>"
        }
        val xml = "<?xml version=\"1.0\"?><rss xmlns:itunes=\"http://www.itunes.com/dtds/podcast-1.0.dtd\"><channel>" +
            "<title>Long</title>$items</channel></rss>"
        var range: String? = null
        // A server that ignores the range and sends everything.
        val client = HttpClient(MockEngine { req ->
            range = req.headers[HttpHeaders.Range]
            respond(xml, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/rss+xml"))
        })
        val feed = RssFeedRepository(client).fetchLatest("https://x/feed", maxBytes = 8 * 1024)
        assertEquals("bytes=0-8191", range)
        assertEquals("Long", feed.podcast.title)
        assertEquals("Episode 1", feed.episodes.first().title)
        assertEquals(3600, feed.episodes.first().durationSeconds)
        assertTrue(feed.episodes.size in 2 until 400, "only the items read in full: ${feed.episodes.size}")
    }

    @Test
    fun aWholeFeedReadAsItsStartComesBackWhole() {
        val xml = "<rss><channel><item><title>A</title></item></channel></rss>"
        assertEquals(xml, RssFeedRepository.closeAfterLastItem(xml))
        assertEquals(
            "<rss><channel><item><title>A</title></item></channel></rss>",
            RssFeedRepository.closeAfterLastItem("<rss><channel><item><title>A</title></item><item><tit"),
        )
    }

    @Test
    fun httpErrorThrowsFetchException() = runTest {
        val client = HttpClient(MockEngine { respond("gone", HttpStatusCode.NotFound) })
        assertFailsWith<FeedFetchException> { RssFeedRepository(client).fetch("https://x/feed") }
    }

    @Test
    fun htmlPageThrowsParseException() = runTest {
        val client = HttpClient(MockEngine { respond("<!doctype html><html><body>hi</body></html>") })
        assertFailsWith<FeedParseException> { RssFeedRepository(client).fetch("https://x/feed") }
    }
}
