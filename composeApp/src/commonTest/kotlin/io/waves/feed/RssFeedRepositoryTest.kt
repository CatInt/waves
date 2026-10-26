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
