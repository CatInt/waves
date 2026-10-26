package io.waves.refresh

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.plugins.cache.storage.FileStorage
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.waves.feed.RssFeedRepository
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** The factories' HttpCache setup makes RssFeedRepository revalidate with ETag and accept 304s. */
class FeedHttpCacheTest {
    private val rss = """<?xml version="1.0"?><rss version="2.0"><channel><title>T</title>
        <item><guid>g1</guid><title>E1</title><enclosure url="https://a/1.mp3" type="audio/mpeg"/></item>
        </channel></rss>"""

    @Test
    fun secondFetchIsConditionalAndServedFrom304() = runBlocking {
        val seen = mutableListOf<String?>()
        val engine = MockEngine { request ->
            val inm = request.headers[HttpHeaders.IfNoneMatch]
            seen += inm
            if (inm == "\"v1\"") respond("", HttpStatusCode.NotModified, headersOf(HttpHeaders.ETag, "\"v1\""))
            else respond(rss, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType to listOf("application/rss+xml"), HttpHeaders.ETag to listOf("\"v1\"")))
        }
        val dir = Files.createTempDirectory("waves-http-cache").toFile()
        val client = HttpClient(engine) { install(HttpCache) { publicStorage(FileStorage(dir)) } }
        val repo = RssFeedRepository(client)

        val first = repo.fetch("https://example.com/feed.xml")
        val second = repo.fetch("https://example.com/feed.xml")

        assertEquals(listOf(null, "\"v1\""), seen)
        assertEquals(first.episodes.map { it.guid }, second.episodes.map { it.guid })
        assertEquals(listOf("g1"), second.episodes.map { it.guid })
        dir.deleteRecursively()
        Unit
    }
}
