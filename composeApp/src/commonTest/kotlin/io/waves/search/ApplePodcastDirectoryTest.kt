package io.waves.search

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApplePodcastDirectoryTest {
    private val requests = mutableListOf<HttpRequestData>()

    private fun directory(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): ApplePodcastDirectory {
        val engine = MockEngine { req -> requests += req; handler(req) }
        return ApplePodcastDirectory(HttpClient(engine))
    }

    private fun MockRequestHandleScope.js(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "text/javascript; charset=utf-8"))

    @Test
    fun searchParsesItunesResponseServedAsJavascript() = runTest {
        val dir = directory { js(AppleFixtures.search) }
        val results = dir.search("morning brief", limit = 2)

        assertEquals(2, results.size)
        val brief = results[0]
        assertEquals("Morning Brief", brief.title)
        assertEquals("Example News", brief.author)
        assertEquals("https://feeds.example.com/morning-brief.xml", brief.feedUrl)
        assertEquals(1000000001L, brief.appleId)
        assertTrue(brief.artworkUrl!!.endsWith("600x600bb.jpg"), brief.artworkUrl)

        val url = requests.single().url
        assertEquals("itunes.apple.com", url.host)
        assertEquals("/search", url.encodedPath)
        assertEquals("podcast", url.parameters["media"])
        assertEquals("podcast", url.parameters["entity"])
        assertEquals("morning brief", url.parameters["term"])
        assertEquals("2", url.parameters["limit"])
    }

    @Test
    fun blankSearchMakesNoRequest() = runTest {
        val dir = directory { error("unexpected request") }
        assertEquals(emptyList(), dir.search("   "))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun searchWithPastedAppleLinkDoesLookup() = runTest {
        val dir = directory { js(AppleFixtures.lookupTop3) }
        val results = dir.search("https://podcasts.apple.com/us/podcast/morning-brief/id1000000001")
        assertEquals("/lookup", requests.single().url.encodedPath)
        assertEquals("1000000001", requests.single().url.parameters["id"])
        assertEquals("Morning Brief", results.first().title)
    }

    @Test
    fun resultsWithoutFeedUrlAreDropped() = runTest {
        val body = """{"resultCount":2,"results":[
            {"kind":"podcast","collectionId":1,"collectionName":"No feed","artistName":"x"},
            {"kind":"podcast","collectionId":2,"collectionName":"Has feed","feedUrl":"https://example.com/rss","artworkUrl100":"https://a/100.jpg"}]}"""
        val dir = directory { js(body) }
        val results = dir.search("x")
        assertEquals(listOf("Has feed"), results.map { it.title })
        assertEquals("https://a/100.jpg", results[0].artworkUrl) // falls back when artworkUrl600 is missing
    }

    @Test
    fun lookupReturnsPodcastOrNull() = runTest {
        val found = directory { js(AppleFixtures.lookupTop3) }.lookup(1000000001)
        assertEquals("https://feeds.example.com/morning-brief.xml", found?.feedUrl)
        assertEquals("podcast", requests.last().url.parameters["entity"])

        assertNull(directory { js(AppleFixtures.lookupEmpty) }.lookup(1))
    }

    @Test
    fun topPodcastsResolvesFeedUrlsWithOneBatchedLookupInChartOrder() = runTest {
        val dir = directory { req ->
            when (req.url.host) {
                "rss.marketingtools.apple.com" -> respond(AppleFixtures.chartTop3, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                else -> js(AppleFixtures.lookupTop3)
            }
        }
        val top = dir.topPodcasts("us", 3)

        assertEquals(listOf("Morning Brief", "Laugh Track", "Case Files"), top.map { it.title })
        assertEquals("https://feeds.example.com/laugh-track.xml", top[1].feedUrl)
        assertEquals(2, requests.size)
        assertEquals("/api/v2/us/podcasts/top/3/podcasts.json", requests[0].url.encodedPath)
        assertEquals("1000000001,1000000003,1000000004", requests[1].url.parameters["id"])
    }

    @Test
    fun chartsRetryOnServerError() = runTest {
        var calls = 0
        val dir = directory { req ->
            if (req.url.host == "rss.marketingtools.apple.com" && calls++ == 0) {
                respond("<html>504 Gateway Time-out</html>", HttpStatusCode.GatewayTimeout)
            } else if (req.url.host == "rss.marketingtools.apple.com") {
                respond(AppleFixtures.chartTop3)
            } else {
                js(AppleFixtures.lookupTop3)
            }
        }
        assertEquals(3, dir.topPodcasts().size)
    }

    @Test
    fun clientErrorsSurfaceAsDirectoryException() = runTest {
        val dir = directory { js("nope", HttpStatusCode.Forbidden) }
        assertFailsWith<PodcastDirectoryException> { dir.search("x") }
        assertEquals(1, requests.size) // 4xx is not retried
    }

    @Test
    fun garbageBodySurfacesAsDirectoryException() = runTest {
        val dir = directory { js("<html>oops</html>") }
        assertFailsWith<PodcastDirectoryException> { dir.search("x") }
    }

    @Test
    fun parsesApplePodcastsUrls() {
        assertEquals(1000000001L, parseApplePodcastsUrl("https://podcasts.apple.com/us/podcast/some-name/id1000000001"))
        assertEquals(1000000001L, parseApplePodcastsUrl("https://podcasts.apple.com/us/podcast/morning-brief/id1000000001?i=1000731234567"))
        assertEquals(1000000001L, parseApplePodcastsUrl("  podcasts.apple.com/gb/podcast/id1000000001  "))
        assertEquals(1000000003L, parseApplePodcastsUrl("https://itunes.apple.com/us/podcast/laugh-track/id1000000003?mt=2"))
        assertEquals(42L, parseApplePodcastsUrl("https://itunes.apple.com/lookup?id=42"))
        assertNull(parseApplePodcastsUrl("https://example.com/podcast/id1000000001"))
        assertNull(parseApplePodcastsUrl("https://podcasts.apple.com/us/browse"))
        assertNull(parseApplePodcastsUrl("morning brief"))
    }
}
