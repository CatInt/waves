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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ordinary enclosures through the relay: fetched from where the player reads, only a little ahead, and once. */
class StreamRelayPlayheadTest {
    private val mb = 1024 * 1024
    private val payload = Random(11).nextBytes(24 * mb)
    /** Every (start, end inclusive) range the server was asked for. */
    private val asked: MutableList<Pair<Long, Long>> = Collections.synchronizedList(mutableListOf())
    private var honorRanges = true

    private val upstream = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/ep.mp3") { ex ->
            ex.use {
                val range = ex.requestHeaders.getFirst("Range")?.takeIf { honorRanges }
                ex.responseHeaders["Content-Type"] = "audio/mpeg"
                try {
                    if (range == null) {
                        asked += 0L to payload.size - 1L
                        ex.sendResponseHeaders(200, payload.size.toLong())
                        ex.responseBody.write(payload)
                    } else {
                        val (start, end) = StreamRelay.parseRange(range, payload.size.toLong())!!
                        asked += start to end
                        ex.responseHeaders["Content-Range"] = "bytes $start-$end/${payload.size}"
                        ex.sendResponseHeaders(206, end - start + 1)
                        ex.responseBody.write(payload, start.toInt(), (end - start + 1).toInt())
                    }
                } catch (_: java.io.IOException) {
                    // The relay cut a chunk short.
                }
            }
        }
        start()
    }

    private val remote = "http://127.0.0.1:${upstream.address.port}/ep.mp3"
    private val client = HttpClient.newHttpClient()

    @AfterTest fun tearDown() = upstream.stop(0)

    private fun get(url: String, range: String): ByteArray =
        client.send(HttpRequest.newBuilder(URI(url)).header("Range", range).build(), HttpResponse.BodyHandlers.ofByteArray()).body()

    private fun settle() = Thread.sleep(500)

    private fun fetchedBytes(): Long = synchronized(asked) { asked.sumOf { it.second - it.first + 1 } }

    @Test fun onlyALittleAheadOfThePlayerIsFetched() {
        val url = assertNotNull(StreamRelay.relayOrNull(remote, "mp3"))
        assertContentEquals(payload.copyOfRange(0, mb), get(url, "bytes=0-${mb - 1}"))
        settle()
        // The read-ahead (and the chunk it ends in), not the whole 24 MB.
        assertTrue(fetchedBytes() <= StreamRelay.READ_AHEAD_BYTES + 4L * mb + mb, "fetched ${fetchedBytes()}")
        assertTrue(fetchedBytes() < payload.size)
    }

    @Test fun aSeekIsFetchedFromWhereThePlayerWent() {
        val url = assertNotNull(StreamRelay.relayOrNull(remote, "mp3"))
        val at = 18 * mb
        assertContentEquals(payload.copyOfRange(at, at + mb), get(url, "bytes=$at-${at + mb - 1}"))
        assertTrue(asked.any { it.first == at.toLong() }, asked.toString())
        // Nothing between the start and the seek was fetched for it.
        assertTrue(asked.none { it.first in (5L * mb) until at.toLong() }, asked.toString())
    }

    @Test fun readThroughEveryByteIsFetchedOnce() {
        val url = assertNotNull(StreamRelay.relayOrNull(remote, "mp3"))
        assertContentEquals(payload, get(url, "bytes=0-"))
        settle()
        assertEquals(payload.size.toLong(), fetchedBytes(), asked.toString())
    }

    @Test fun oncePlayingTheRelayServesAndFetchesOnlyALittlePastWhereItPlays() {
        val url = assertNotNull(StreamRelay.relayOrNull(remote, "mp3"))
        StreamRelay.reportPlayback(url, positionMs = 0, durationMs = 1_000_000)
        val body = client.send(
            HttpRequest.newBuilder(URI(url)).header("Range", "bytes=0-").build(),
            HttpResponse.BodyHandlers.ofInputStream(),
        ).body()
        val received = java.util.concurrent.atomic.AtomicLong()
        val reader = Thread {
            val buffer = ByteArray(64 * 1024)
            runCatching { while (true) { val n = body.read(buffer); if (n < 0) break; received.addAndGet(n.toLong()) } }
        }.apply { isDaemon = true; start() }
        settle()
        // A player reading as fast as it can gets only the read-ahead past where it plays, and no more is fetched.
        assertEquals(StreamRelay.READ_AHEAD_BYTES, received.get())
        assertTrue(fetchedBytes() <= StreamRelay.READ_AHEAD_BYTES + 4L * mb, "fetched ${fetchedBytes()}")
        // Playing on, it gets more.
        StreamRelay.reportPlayback(url, positionMs = 500_000, durationMs = 1_000_000)
        settle()
        assertEquals(payload.size / 2 + StreamRelay.READ_AHEAD_BYTES, received.get())
        body.close()
        reader.join(2_000)
    }

    @Test fun cachingWhilePlayingCompletesTheRelayedFileAndKeepsIt() = kotlinx.coroutines.runBlocking {
        val url = assertNotNull(StreamRelay.relayOrNull(remote, "mp3", episode = "https://feed.example/ep1.mp3"))
        StreamRelay.reportPlayback(url, positionMs = 0, durationMs = 1_000_000)
        assertContentEquals(payload.copyOfRange(0, mb), get(url, "bytes=0-${mb - 1}"))
        settle()
        assertTrue(fetchedBytes() < payload.size, "paced before caching")

        val dest = java.io.File.createTempFile("relay-cache", ".mp3").apply { delete(); deleteOnExit() }
        assertEquals(payload.size.toLong(), StreamRelay.completeTo("https://feed.example/ep1.mp3", dest))
        assertContentEquals(payload, dest.readBytes())
        // One download: what the player streamed and the rest, every byte once.
        assertEquals(payload.size.toLong(), fetchedBytes(), asked.toString())
        // And it still plays from the relay, now from what's held.
        assertContentEquals(payload.copyOfRange(20 * mb, 21 * mb), get(url, "bytes=${20 * mb}-${21 * mb - 1}"))
    }

    @Test fun cachingAnEpisodeThatIsntRelayedLeavesItToTheCaller() = kotlinx.coroutines.runBlocking {
        val dest = java.io.File.createTempFile("relay-cache", ".mp3").apply { delete(); deleteOnExit() }
        assertNull(StreamRelay.completeTo("https://feed.example/not-playing.mp3", dest))
        assertTrue(!dest.exists())
    }

    @Test fun aReadLeftBehindByAJumpAheadEndsAndFetchesNothingBehind() {
        val url = assertNotNull(StreamRelay.relayOrNull(remote, "mp3"))
        StreamRelay.reportPlayback(url, positionMs = 0, durationMs = 1_000_000)
        // A connection reading as fast as it can, held at the cap...
        val body = client.send(
            HttpRequest.newBuilder(URI(url)).header("Range", "bytes=0-").build(),
            HttpResponse.BodyHandlers.ofInputStream(),
        ).body()
        val reader = Thread { runCatching { body.readAllBytes() } }.apply { isDaemon = true; start() }
        settle()
        // ...then a jump to 90%: a new connection there, the old one left behind.
        val at = (payload.size * 0.9).toLong()
        StreamRelay.reportPlayback(url, positionMs = 900_000, durationMs = 1_000_000)
        assertContentEquals(payload.copyOfRange(at.toInt(), at.toInt() + mb), get(url, "bytes=$at-${at + mb - 1}"))
        settle()
        reader.join(5_000)
        assertTrue(!reader.isAlive, "the old read ended")
        // Nothing between the cap and the jump was fetched.
        assertTrue(asked.none { it.first in (12L * mb) until at - 2L * mb }, asked.toString())
    }

    @Test fun aServerWithoutRangesIsPlayedDirectly() {
        honorRanges = false
        assertNull(StreamRelay.relayOrNull(remote, "mp3"))
    }

    @Test fun anUnplayableExtensionIsPlayedDirectly() {
        assertNull(StreamRelay.relayOrNull(remote, "ogg"))
        assertTrue(asked.isEmpty())
    }

    @Test fun spansMergeAndAnswerLikeTheCache() {
        val spans = StreamRelay.Spans()
        spans.add(0, 100)
        spans.add(200, 300)
        spans.add(100, 150)
        assertEquals("0-150, 200-300", spans.toString())
        assertEquals(150, spans.endOf(10))
        assertEquals(160, spans.endOf(160))
        assertEquals(40, spans.cachedLength(110, 1_000))
        assertEquals(-50, spans.cachedLength(150, 1_000))
        assertEquals(-(Long.MAX_VALUE - 300), spans.cachedLength(300, Long.MAX_VALUE))
        spans.add(140, 210)
        assertEquals("0-300", spans.toString())
    }
}
