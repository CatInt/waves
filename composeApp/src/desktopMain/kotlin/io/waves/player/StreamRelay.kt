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

/**
 * Plays a resolved YouTube stream through 127.0.0.1, for two reasons:
 *
 * - JavaFX Media picks its demuxer from the URL's extension or a Content-Type it knows, and a stream URL
 *   (`/videoplayback?…`, sent as `audio/mp4`) has neither, so JavaFX rejects it as MEDIA_UNSUPPORTED.
 * - YouTube throttles open-ended requests to a crawl but serves bounded ranges fast. The audio is a fragmented
 *   MP4, which JavaFX reads end to end (one request per fragment) before it can start.
 *
 * So the relay downloads the stream in bounded chunks into a temp file and answers the player's range
 * requests from that file, waiting for bytes that haven't arrived yet.
 */
internal object StreamRelay {
    private const val MAX_STREAMS = 4
    private const val CHUNK = 8L * 1024 * 1024
    private val contentTypes = mapOf("m4a" to "audio/x-m4a", "mp4" to "audio/x-m4a", "mp3" to "audio/mpeg")

    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
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

    /** A loopback URL that plays [remote] as a `.[extension]` file. */
    fun url(remote: String, extension: String = "m4a"): String {
        val token = UUID.randomUUID().toString()
        synchronized(streams) {
            streams[token] = Stream(remote, extension)
            while (streams.size > MAX_STREAMS) {
                val eldest = streams.keys.first()
                streams.remove(eldest)?.close()
            }
        }
        return "http://127.0.0.1:${server.address.port}/$token.$extension"
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

    private class Stream(val remote: String, val extension: String) {
        private val file: File = File.createTempFile("waves-stream", ".$extension").apply { deleteOnExit() }
        private val lock = Object()
        private var total = -1L
        private var filled = 0L
        private var failed = false
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

        fun awaitTotal(): Long = synchronized(lock) {
            while (total < 0 && !failed && !closed) lock.wait()
            total
        }

        /** Writes bytes [start]..[end] to [out] as they arrive. */
        fun copyTo(out: java.io.OutputStream, start: Long, end: Long) {
            val buffer = ByteArray(64 * 1024)
            RandomAccessFile(file, "r").use { raf ->
                var pos = start
                while (pos <= end) {
                    val available = synchronized(lock) {
                        while (filled <= pos && !failed && !closed) lock.wait()
                        filled
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

        /** Patches the header once it's all in [raf] ([written] bytes); true when readers may proceed. */
        private fun patchHeader(raf: RandomAccessFile, written: Long, complete: Boolean): Boolean {
            val head = ByteArray(minOf(written, Mp4Header.SCAN_BYTES.toLong()).toInt())
            raf.seek(0)
            raf.readFully(head)
            if (!Mp4Header.clearFragmentedDurations(head) && !complete && written < Mp4Header.SCAN_BYTES) return false
            raf.seek(0)
            raf.write(head)
            return true
        }

        private fun fill() {
            try {
                RandomAccessFile(file, "rw").use { raf ->
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    var headerPatched = false
                    while (!closed && (total < 0 || written < total)) {
                        val request = HttpRequest.newBuilder(URI(remote))
                            .header("Range", "bytes=$written-${written + CHUNK - 1}")
                            .build()
                        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
                        val code = response.statusCode()
                        if (code != 200 && code != 206) {
                            response.body().close()
                            throw IOException("HTTP $code")
                        }
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
                        response.body().use { input ->
                            while (!closed) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                raf.seek(written)
                                raf.write(buffer, 0, n)
                                written += n
                                // Readers only see bytes once the MP4 header has been patched.
                                if (!headerPatched) headerPatched = patchHeader(raf, written, written >= total)
                                if (headerPatched) synchronized(lock) {
                                    filled = written
                                    lock.notifyAll()
                                }
                            }
                        }
                        if (code == 200) break // the whole body came at once
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
    }
}
