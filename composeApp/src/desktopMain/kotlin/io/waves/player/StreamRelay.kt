package io.waves.player

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Plays streamed episodes through 127.0.0.1, from a local file the relay downloads in bounded chunks, answering the
 * player's range requests from it and waiting for bytes that haven't arrived yet.
 *
 * - A resolved YouTube stream ([url]): JavaFX Media picks its demuxer from the URL's extension or a Content-Type it
 *   knows, and a stream URL (`/videoplayback?…`, sent as `audio/mp4`) has neither. YouTube also throttles open-ended
 *   requests to a crawl but serves bounded ranges fast. Its audio is a fragmented MP4, which JavaFX reads end to end
 *   (one request per fragment) before it can start, so the whole stream is fetched.
 * - An ordinary enclosure ([relayOrNull]): once it plays, only up to [READ_AHEAD_BYTES] past where it plays is
 *   fetched and served ([reportPlayback]). JavaFX reads a file as fast as it's served, far ahead of what it plays, so
 *   pacing it by where it reads alone would still fetch the whole episode.
 *
 * Chunks come from where the player reads ([nextFillGap]): after a seek the next chunk starts there, and a chunk the
 * player has left is cut short.
 *
 * Caching an episode while it plays ([completeTo]) lets its stream fetch the rest, ahead of the player and then the
 * gaps behind, and keeps the whole file: the episode is fetched once.
 */
internal object StreamRelay {
    private const val MAX_STREAMS = 4
    private const val CHUNK = 4L * 1024 * 1024
    /** How far ahead of the player an ordinary enclosure is fetched. */
    const val READ_AHEAD_BYTES = 8L * 1024 * 1024
    private val contentTypes = mapOf("m4a" to "audio/x-m4a", "mp4" to "audio/x-m4a", "mp3" to "audio/mpeg")

    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    /** Token → stream, oldest first; evicted streams stop downloading and delete their file. */
    private val streams = LinkedHashMap<String, Stream>()

    private val server: HttpServer by lazy {
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { exchange -> handle(exchange) }
            executor = Executors.newCachedThreadPool { r -> Thread(r, "waves-stream-relay").apply { isDaemon = true } }
            start()
        }
    }

    /**
     * A loopback URL that plays the resolved YouTube stream [remote] as a `.[extension]` file, fetched whole. [episode]
     * names it for [completeTo] (the episode's own address).
     */
    fun url(remote: String, extension: String = "m4a", episode: String? = null): String =
        add(Stream(remote, extension, readAhead = null, episode = episode)).first

    /**
     * A loopback URL that plays the ordinary enclosure [remote] (already past its redirects) as a `.[extension]`
     * file, fetched only a little ahead of the player; null when it can't be relayed (the server doesn't serve byte
     * ranges or say how big the file is, or doesn't answer within [timeoutMs]), and the player streams it directly.
     */
    fun relayOrNull(remote: String, extension: String, episode: String? = null, timeoutMs: Long = 15_000): String? {
        if (extension !in contentTypes) return null
        val (url, stream) = add(Stream(remote, extension, readAhead = READ_AHEAD_BYTES, episode = episode))
        if (stream.awaitTotal(timeoutMs) > 0 && stream.servesRanges) return url
        synchronized(streams) { streams.values.remove(stream) }
        stream.close()
        return null
    }

    /**
     * Fetches all of [episode]'s relayed stream (its own address, as given to [url] or [relayOrNull]) and copies it to
     * [dest], returning its size: ahead of the player first, then the gaps behind, while it goes on playing. Null when
     * the episode isn't being relayed, or its stream fails or closes first; the caller fetches it another way then.
     */
    suspend fun completeTo(episode: String, dest: File): Long? {
        val stream = synchronized(streams) { streams.values.lastOrNull { it.episode == episode } } ?: return null
        System.err.println("WavesRelay: caching $episode: fetching the rest")
        stream.fetchAll()
        while (!stream.isComplete()) {
            if (stream.isOver()) return null.also { System.err.println("WavesRelay: caching $episode: stream ended first") }
            kotlinx.coroutines.delay(250)
        }
        return withContext(Dispatchers.IO) { stream.copyTo(dest) }
            .also { System.err.println("WavesRelay: caching $episode: kept ${it ?: "nothing"} bytes as $dest") }
    }

    /**
     * Where the player is in a relayed [url]: [positionMs] of [durationMs]. An ordinary enclosure is then served and
     * fetched only up to [READ_AHEAD_BYTES] past that point (by its share of the file's size).
     */
    fun reportPlayback(url: String, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0 || !url.startsWith("http://127.0.0.1:")) return
        val token = url.substringAfterLast('/').substringBefore('.')
        val stream = synchronized(streams) { streams[token] } ?: return
        stream.reportPlayback(positionMs.toDouble() / durationMs)
    }

    private fun add(stream: Stream): Pair<String, Stream> {
        val token = UUID.randomUUID().toString()
        synchronized(streams) {
            streams[token] = stream
            while (streams.size > MAX_STREAMS) {
                val eldest = streams.keys.first()
                streams.remove(eldest)?.close()
            }
        }
        return "http://127.0.0.1:${server.address.port}/$token.${stream.extension}" to stream
    }

    private fun handle(exchange: HttpExchange) = exchange.use {
        val name = exchange.requestURI.path.trim('/')
        val stream = synchronized(streams) { streams[name.substringBefore('.')] }
        val total = stream?.awaitTotal() ?: -1
        if (stream == null || total < 0) {
            exchange.sendResponseHeaders(if (stream == null) 404 else 502, -1)
            return@use
        }
        val range = exchange.requestHeaders.getFirst("Range")?.let { parseRange(it, total) }
        val (start, end) = range ?: (0L to total - 1)
        if (start >= total || end < start) {
            exchange.responseHeaders["Content-Range"] = "bytes */$total"
            exchange.sendResponseHeaders(416, -1)
            return@use
        }
        val headers = exchange.responseHeaders
        headers["Content-Type"] = contentTypes[stream.extension] ?: "application/octet-stream"
        headers["Accept-Ranges"] = "bytes"
        if (range != null) headers["Content-Range"] = "bytes $start-$end/$total"
        val status = if (range != null) 206 else 200
        val length = end - start + 1
        if (exchange.requestMethod.equals("HEAD", ignoreCase = true)) {
            headers["Content-Length"] = length.toString()
            exchange.sendResponseHeaders(status, -1)
            return@use
        }
        try {
            exchange.sendResponseHeaders(status, length)
            stream.copyTo(exchange.responseBody, start, end)
        } catch (_: IOException) {
            // The player dropped the connection: a seek, a stop, or it read what it needed.
        }
    }

    /** `bytes=a-b` / `bytes=a-` / `bytes=-n` → inclusive (start, end) within [total]. */
    internal fun parseRange(header: String, total: Long): Pair<Long, Long>? {
        val spec = header.trim().removePrefix("bytes=").substringBefore(',').trim()
        val from = spec.substringBefore('-').trim()
        val to = spec.substringAfter('-', "").trim()
        return when {
            from.isEmpty() -> to.toLongOrNull()?.let { n -> (total - n).coerceAtLeast(0) to total - 1 }
            else -> from.toLongOrNull()?.let { a -> a to (to.toLongOrNull()?.coerceAtMost(total - 1) ?: (total - 1)) }
        }
    }

    /** Byte ranges held, as sorted, non-touching [start, end) pairs. Not thread-safe. */
    internal class Spans {
        private val starts = ArrayList<Long>()
        private val ends = ArrayList<Long>()

        fun add(start: Long, end: Long) {
            if (end <= start) return
            var s = start
            var e = end
            var i = 0
            while (i < starts.size && ends[i] < s) i++
            while (i < starts.size && starts[i] <= e) {
                s = minOf(s, starts[i])
                e = maxOf(e, ends[i])
                starts.removeAt(i)
                ends.removeAt(i)
            }
            starts.add(i, s)
            ends.add(i, e)
        }

        /** Where the run of held bytes from [position] ends; [position] itself when it isn't held. */
        fun endOf(position: Long): Long {
            for (i in starts.indices) if (position >= starts[i] && position < ends[i]) return ends[i]
            return position
        }

        /** Like `Cache.getCachedLength`: held bytes from [position] (positive), or missing ones up to the next (negative). */
        fun cachedLength(position: Long, length: Long): Long {
            val limit = if (Long.MAX_VALUE - position < length) Long.MAX_VALUE else position + length
            val end = endOf(position)
            if (end > position) return minOf(end, limit) - position
            val next = starts.firstOrNull { it > position } ?: Long.MAX_VALUE
            return -(minOf(next, limit) - position)
        }

        override fun toString() = starts.indices.joinToString { "${starts[it]}-${ends[it]}" }
    }

    /**
     * One relayed file. A fetch thread downloads it a chunk at a time from where the player reads ([playhead]),
     * whole ([readAhead] null) or only that far ahead of the player; readers wait for the bytes they need.
     */
    private class Stream(val remote: String, val extension: String, readAhead: Long?, val episode: String? = null) {
        /** How far past where it plays the player may read and the relay fetch; null to fetch it all. Under [lock]. */
        private var readAhead: Long? = readAhead
        private val file: File = File.createTempFile("waves-stream", ".$extension").apply { deleteOnExit() }
        private val lock = Object()
        private val held = Spans()
        private var total = -1L
        private var failed = false
        /** A fragmented MP4's header needs patching before the player may read it; for other files it's ready. */
        private var headReady = extension != "m4a" && extension != "mp4"
        /** Where the player reads (or last asked to read). */
        private var playhead = 0L
        /** A position a reader waits at, or -1. */
        private var wanted = -1L
        /** Byte the player plays at (estimated from its time), -1 until it reports one: no pacing before then. */
        private var playing = -1L
        @Volatile var servesRanges = true
            private set
        @Volatile private var closed = false
        @Volatile private var done = false

        init {
            Thread(::fill, "waves-stream-fill").apply { isDaemon = true }.start()
        }

        fun close() {
            closed = true
            synchronized(lock) { lock.notifyAll() }
            if (done) file.delete()
        }

        /** From now on fetches the whole file: ahead of the player, then the gaps behind. */
        fun fetchAll() = synchronized(lock) {
            readAhead = null
            lock.notifyAll()
        }

        fun isComplete(): Boolean = synchronized(lock) { total > 0 && held.endOf(0) >= total }

        /** Failed, or closed (evicted): it won't complete. */
        fun isOver(): Boolean = synchronized(lock) { failed || closed }

        /** Copies the whole file (once [isComplete]) to [dest], beside it first, returning its size; null if it fails. */
        fun copyTo(dest: File): Long? {
            val size = synchronized(lock) { total.takeIf { isCompleteLocked() } } ?: return null
            val temp = File(dest.path + ".copying")
            return try {
                dest.parentFile?.mkdirs()
                file.copyTo(temp, overwrite = true)
                if (temp.length() != size || !temp.renameTo(dest)) null else size
            } catch (_: Exception) {
                null
            } finally {
                temp.delete()
            }
        }

        private fun isCompleteLocked() = total > 0 && held.endOf(0) >= total

        fun reportPlayback(fraction: Double) = synchronized(lock) {
            if (total <= 0) return@synchronized
            val at = (fraction.coerceIn(0.0, 1.0) * total).toLong()
            if (at != playing) {
                playing = at
                lock.notifyAll()
            }
        }

        /** Whether a read at [pos] is far behind where a paced stream plays. Under [lock]. */
        private fun leftBehind(pos: Long): Boolean {
            val readAhead = readAhead ?: return false
            return playing >= 0 && pos < playing - readAhead
        }

        /** How far the player may read, and the relay fetch: unlimited unless it reads ahead and plays. Under [lock]. */
        private fun limit(): Long = readAhead?.takeIf { playing >= 0 }?.let { playing + it } ?: Long.MAX_VALUE

        fun awaitTotal(timeoutMs: Long = Long.MAX_VALUE): Long = synchronized(lock) {
            val deadline = if (timeoutMs == Long.MAX_VALUE) Long.MAX_VALUE else System.currentTimeMillis() + timeoutMs
            while (total < 0 && !failed && !closed) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                lock.wait(minOf(left, 1_000))
            }
            total
        }

        /** Writes bytes [start]..[end] to [out] as they arrive. */
        fun copyTo(out: java.io.OutputStream, start: Long, end: Long) {
            val buffer = ByteArray(64 * 1024)
            RandomAccessFile(file, "r").use { raf ->
                var pos = start
                while (pos <= end) {
                    val available = synchronized(lock) {
                        if (playhead != pos) {
                            playhead = pos
                            lock.notifyAll()
                        }
                        // Held back at the pace limit too: woken as the player plays on.
                        // A read left far behind where it plays (an old connection after a jump ahead) ends, rather
                        // than pull in everything it passes.
                        if (leftBehind(pos)) throw IOException("Left behind where it plays")
                        while ((!(headReady && held.endOf(pos) > pos) || pos >= limit()) && !failed && !closed) {
                            if (leftBehind(pos)) {
                                if (wanted == pos) wanted = -1
                                throw IOException("Left behind where it plays")
                            }
                            wanted = pos
                            lock.wait()
                        }
                        if (wanted == pos) wanted = -1
                        if (leftBehind(pos)) throw IOException("Left behind where it plays")
                        if (headReady) minOf(held.endOf(pos), limit()) else pos
                    }
                    if (available <= pos) throw IOException("Stream ended early")
                    raf.seek(pos)
                    val n = raf.read(buffer, 0, minOf(buffer.size.toLong(), available - pos, end - pos + 1).toInt())
                    if (n <= 0) throw IOException("Stream ended early")
                    out.write(buffer, 0, n)
                    pos += n
                }
            }
        }

        /** The next chunk to fetch (start, length); null once there's nothing left to fetch, or it's closed. */
        private fun next(): Pair<Long, Long>? = synchronized(lock) {
            while (!closed) {
                // A fragmented MP4's header first: nothing plays until it's patched.
                if (!headReady) return@synchronized held.endOf(0).let { it to CHUNK }
                if (total < 0) return@synchronized playhead to CHUNK
                val readAhead = readAhead
                // From where a reader waits for bytes; else, paced, from where it plays, not where some old reader
                // last was: nothing behind it is fetched for its own sake.
                val from = when {
                    wanted >= 0 && !leftBehind(wanted) -> wanted
                    readAhead != null && playing >= 0 -> playing
                    else -> playhead
                }
                val gap = nextFillGap(held::cachedLength, from, total) ?: return@synchronized null
                val ahead = gap.position >= from
                val window = if (playing >= 0) limit() else from + (readAhead ?: 0)
                if (readAhead == null || (ahead && gap.position < window)) {
                    return@synchronized gap.position to minOf(gap.length, CHUNK)
                }
                // Far enough ahead of the player (or only gaps behind it): wait for it to move on.
                lock.wait()
            }
            null
        }

        private fun fill() {
            try {
                RandomAccessFile(file, "rw").use { raf ->
                    var failures = 0
                    while (!closed) {
                        val (start, length) = next() ?: break
                        System.err.println("WavesRelay: chunk at $start (player at $playhead, size $total)")
                        try {
                            fetch(raf, start, length)
                            failures = 0
                        } catch (e: IOException) {
                            if (++failures >= 3) throw e
                            Thread.sleep(500L * failures)
                        }
                    }
                }
            } catch (_: Exception) {
                synchronized(lock) { failed = true }
            } finally {
                done = true
                synchronized(lock) { lock.notifyAll() }
                if (closed) file.delete()
            }
        }

        /** Fetches [length] bytes from [start] into [raf], stopping early when a reader waits for bytes elsewhere. */
        private fun fetch(raf: RandomAccessFile, start: Long, length: Long) {
            val request = HttpRequest.newBuilder(URI(remote))
                .header("Range", "bytes=$start-${start + length - 1}")
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            val code = response.statusCode()
            if (code != 200 && code != 206) {
                response.body().close()
                throw IOException("HTTP $code")
            }
            // Ranges ignored: the whole file from its start comes back.
            val from = if (code == 200) 0L else start
            if (code == 200) servesRanges = false
            if (total < 0) {
                val size = if (code == 206) {
                    response.headers().firstValue("Content-Range").orElse("").substringAfterLast('/').toLongOrNull()
                } else {
                    response.headers().firstValueAsLong("Content-Length").orElse(-1).takeIf { it >= 0 }
                }
                synchronized(lock) {
                    total = size ?: throw IOException("Unknown stream length")
                    lock.notifyAll()
                }
            }
            val until = if (code == 200) total else start + length
            response.body().use { input ->
                val buffer = ByteArray(64 * 1024)
                var pos = from
                while (!closed && pos < until) {
                    val n = input.read(buffer, 0, minOf(buffer.size.toLong(), until - pos).toInt())
                    if (n < 0) break
                    raf.seek(pos)
                    raf.write(buffer, 0, n)
                    val end = pos + n
                    val redirect = synchronized(lock) {
                        held.add(pos, end)
                        if (!headReady) headReady = patchHeader(raf)
                        lock.notifyAll()
                        // The player went elsewhere and waits there: leave this chunk for the bytes it needs.
                        code == 206 && wanted >= 0 && !leftBehind(wanted) &&
                            (wanted < start || wanted >= start + length) && held.endOf(wanted) == wanted
                    }
                    pos = end
                    if (redirect) break
                }
            }
        }

        /** Patches a fragmented MP4's header once all of it is held; true when readers may proceed. Under [lock]. */
        private fun patchHeader(raf: RandomAccessFile): Boolean {
            val scan = minOf(total.takeIf { it > 0 } ?: Long.MAX_VALUE, Mp4Header.SCAN_BYTES.toLong())
            val have = held.endOf(0)
            val head = ByteArray(minOf(have, scan).toInt())
            raf.seek(0)
            raf.readFully(head)
            if (!Mp4Header.clearFragmentedDurations(head) && have < scan) return false
            raf.seek(0)
            raf.write(head)
            return true
        }
    }
}
