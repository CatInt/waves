package io.waves.refresh

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.cache.HttpCache
import io.waves.feed.FeedRepository
import io.waves.feed.RssFeedRepository
import io.waves.wavesDefaults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoundedRssDownloadTest {
    @Test
    fun ignoresRangeWithoutDownloadingTheTailAndClosesTheConnection() = runBlocking {
        val maxBytes = FeedRepository.LATEST_BYTES
        val prefix = ("<rss><channel><title>T</title><item><guid>g1</guid><title>E1</title>" +
            "<enclosure url=\"https://a/1.mp3\"/></item><item><description>").padEnd(maxBytes, 'x').toByteArray()
        val disconnected = CountDownLatch(1)
        val tailBytes = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/feed") { exchange ->
            try {
                exchange.responseHeaders.add("Content-Type", "application/rss+xml")
                exchange.responseHeaders.add("Cache-Control", "public, max-age=3600")
                // Deliberately ignore Range and keep streaming a much larger body.
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { out ->
                    out.write(prefix)
                    out.flush()
                    val chunk = ByteArray(16 * 1024) { 'x'.code.toByte() }
                    repeat(2048) {
                        out.write(chunk)
                        out.flush()
                        tailBytes.addAndGet(chunk.size)
                        Thread.sleep(2)
                    }
                }
            } catch (_: IOException) {
                disconnected.countDown()
            } finally {
                exchange.close()
            }
        }
        server.start()
        val client = HttpClient(Android) {
            wavesDefaults()
            install(HttpCache)
        }
        try {
            val feed = RssFeedRepository(client).fetchLatest("http://127.0.0.1:${server.address.port}/feed")
            assertEquals(listOf("g1"), feed.episodes.map { it.guid })
            assertFalse(feed.isComplete)
            val closed = withContext(Dispatchers.IO) { disconnected.await(5, TimeUnit.SECONDS) }
            assertTrue(closed, "server must observe the abandoned response; tail bytes sent=${tailBytes.get()}")
            assertTrue(tailBytes.get() < 1024 * 1024, "must not drain the 32 MiB tail")
        } finally {
            client.close()
            server.stop(0)
        }
    }
}
