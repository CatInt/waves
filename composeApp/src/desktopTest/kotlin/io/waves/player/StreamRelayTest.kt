package io.waves.player

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Collections
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StreamRelayTest {
    private var payload = Random(7).nextBytes(250_000)
    private val upstreamRanges: MutableList<String?> = Collections.synchronizedList(mutableListOf())

    /** Stands in for googlevideo: honors bounded ranges, labels the body `audio/mp4`. */
    private val upstream = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/videoplayback") { ex ->
            ex.use {
                val range = ex.requestHeaders.getFirst("Range").also { upstreamRanges += it }
                ex.responseHeaders["Content-Type"] = "audio/mp4"
                if (range == null) {
                    ex.sendResponseHeaders(200, payload.size.toLong())
                    ex.responseBody.write(payload)
                } else {
                    val (start, end) = StreamRelay.parseRange(range, payload.size.toLong())!!
                    ex.responseHeaders["Content-Range"] = "bytes $start-$end/${payload.size}"
                    ex.sendResponseHeaders(206, end - start + 1)
                    ex.responseBody.write(payload, start.toInt(), (end - start + 1).toInt())
                }
            }
        }
        start()
    }

    private val client = HttpClient.newHttpClient()

    @AfterTest fun tearDown() = upstream.stop(0)

    private fun relayed() = StreamRelay.url("http://127.0.0.1:${upstream.address.port}/videoplayback?itag=140&id=x")

    private fun get(url: String, range: String? = null, method: String = "GET"): HttpResponse<ByteArray> =
        client.send(
            HttpRequest.newBuilder(URI(url)).method(method, HttpRequest.BodyPublishers.noBody())
                .apply { range?.let { header("Range", it) } }.build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )

    @Test fun fragmentedMp4IsServedWithoutItsMoovDurations() {
        val header = Mp4HeaderTest.mp4(fragmented = true)
        payload = header + Random(8).nextBytes(100_000)
        val body = get(relayed()).body()
        assertEquals(payload.size, body.size)
        assertEquals(0, Mp4HeaderTest.mvhdDuration(body))
        assertEquals(0, Mp4HeaderTest.mdhdDuration(body))
        assertContentEquals(payload.copyOfRange(header.size, payload.size), body.copyOfRange(header.size, body.size))
    }

    @Test fun servesTheStreamAsAnM4aFileWithRanges() {
        val url = relayed()
        assertTrue(url.startsWith("http://127.0.0.1:") && url.endsWith(".m4a"), url)

        val whole = get(url)
        assertEquals(200, whole.statusCode())
        assertEquals("audio/x-m4a", whole.headers().firstValue("Content-Type").get())
        assertContentEquals(payload, whole.body())

        val part = get(url, "bytes=1000-1999")
        assertEquals(206, part.statusCode())
        assertEquals("bytes 1000-1999/${payload.size}", part.headers().firstValue("Content-Range").get())
        assertContentEquals(payload.copyOfRange(1000, 2000), part.body())

        val tail = get(url, "bytes=200000-")
        assertContentEquals(payload.copyOfRange(200_000, payload.size), tail.body())

        val head = get(url, method = "HEAD")
        assertEquals(payload.size.toString(), head.headers().firstValue("Content-Length").get())

        assertEquals(416, get(url, "bytes=${payload.size}-").statusCode())
        // Fetched once, in bounded ranges, however many requests the player makes.
        assertTrue(upstreamRanges.all { it != null && !it.endsWith("-") }, upstreamRanges.toString())
    }

    @Test fun unknownStreamIs404() {
        val url = relayed()
        val unknown = url.substringBeforeLast('/') + "/nope.m4a"
        assertEquals(404, get(unknown).statusCode())
    }

    @Test fun parsesRanges() {
        assertEquals(0L to 99L, StreamRelay.parseRange("bytes=0-99", 1000))
        assertEquals(500L to 999L, StreamRelay.parseRange("bytes=500-", 1000))
        assertEquals(900L to 999L, StreamRelay.parseRange("bytes=-100", 1000))
        assertEquals(10L to 999L, StreamRelay.parseRange("bytes=10-5000", 1000))
    }
}
