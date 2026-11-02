package io.waves.feed

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.utils.io.readAvailable
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.request.prepareGet
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext

/** Thrown when a feed cannot be downloaded. */
class FeedFetchException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** [FeedRepository] that downloads an RSS feed over HTTP and parses it with [FeedParser]. */
class RssFeedRepository(private val client: HttpClient) : FeedRepository {

    override suspend fun fetch(feedUrl: String): Feed {
        val response = try {
            client.get(feedUrl) {
                header(HttpHeaders.UserAgent, USER_AGENT)
                header(HttpHeaders.Accept, "application/rss+xml, application/xml;q=0.9, text/xml;q=0.9, */*;q=0.8")
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw FeedFetchException("Failed to download $feedUrl: ${e.message}", e)
        }
        if (!response.status.isSuccess()) {
            throw FeedFetchException("Failed to download $feedUrl: HTTP ${response.status.value}")
        }
        val bytes = response.bodyAsBytes()
        // Off the main thread: feeds run to megabytes, and Home refreshes every show at once.
        return withContext(Dispatchers.Default) {
            FeedParser.parse(feedUrl, decode(bytes, response.contentType()?.parameter("charset")))
        }
    }

    /**
     * Reads no more than [maxBytes] of the feed (asking for just that range, and stopping there if the server sends
     * it all), then parses the items read in full; the rest of the download is dropped unread.
     */
    override suspend fun fetchLatest(feedUrl: String, maxBytes: Int): Feed {
        require(maxBytes > 0) { "maxBytes must be positive" }
        val (bytes, charset) = try {
            client.prepareGet(feedUrl) {
                header(HttpHeaders.UserAgent, USER_AGENT)
                header(HttpHeaders.Accept, "application/rss+xml, application/xml;q=0.9, text/xml;q=0.9, */*;q=0.8")
                header(HttpHeaders.Range, "bytes=0-${maxBytes - 1}")
                // A range of compressed bytes wouldn't decompress on its own.
                header(HttpHeaders.AcceptEncoding, "identity")
                // HttpCache otherwise buffers the entire response, even for a streaming request.
                header(HttpHeaders.CacheControl, "no-cache, no-store")
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    throw FeedFetchException("Failed to download $feedUrl: HTTP ${response.status.value}")
                }
                val channel = response.bodyAsChannel()
                try {
                    val buffer = ByteArray(maxBytes)
                    var read = 0
                    while (read < maxBytes) {
                        val n = channel.readAvailable(buffer, read, maxBytes - read)
                        if (n < 0) break
                        read += n
                    }
                    buffer.copyOf(read) to response.contentType()?.parameter("charset")
                } finally {
                    channel.cancel(null)
                    response.coroutineContext.cancel()
                }
            }
        } catch (e: FeedFetchException) {
            throw e
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw FeedFetchException("Failed to download $feedUrl: ${e.message}", e)
        }
        return withContext(Dispatchers.Default) {
            val xml = decode(bytes, charset)
            val closed = closeAfterLastItem(xml)
            FeedParser.parse(feedUrl, closed).copy(isComplete = closed == xml)
        }
    }

    internal companion object {
        const val USER_AGENT = "Waves/0.1 (podcast app)"

        /**
         * The start of a feed, cut after its last complete item (or Atom entry) and closed, so it parses as a feed of
         * those items; a whole feed comes back as it is.
         */
        fun closeAfterLastItem(xml: String): String {
            val rss = xml.lastIndexOf("</item>")
            val atom = xml.lastIndexOf("</entry>")
            return when {
                xml.trimEnd().endsWith("</rss>") || xml.trimEnd().endsWith("</feed>") -> xml
                rss >= 0 && rss > atom -> xml.substring(0, rss + "</item>".length) + "</channel></rss>"
                atom >= 0 -> xml.substring(0, atom + "</entry>".length) + "</feed>"
                // Not even one item read: just the channel's opening, which says nothing of its episodes.
                else -> throw FeedFetchException("No complete item in the first ${xml.length} characters")
            }
        }

        /**
         * Decodes feed bytes. The XML declaration's encoding wins over the HTTP charset (servers often lie);
         * only UTF-8 and Latin-1/Windows-1252 are distinguished, which covers practically all podcast feeds.
         */
        fun decode(bytes: ByteArray, httpCharset: String?): String {
            if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
                return bytes.decodeToString(3, bytes.size)
            }
            val head = buildString { for (i in 0 until minOf(bytes.size, 200)) append((bytes[i].toInt() and 0xFF).toChar()) }
            val declared = Regex("""<\?xml[^>]*encoding\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(head)?.groupValues?.get(1)
            val charset = (declared ?: httpCharset ?: "utf-8").trim().lowercase()
            return if (charset in latin1Names) {
                CharArray(bytes.size) { (bytes[it].toInt() and 0xFF).toChar() }.concatToString()
            } else {
                bytes.decodeToString()
            }
        }

        private val latin1Names = setOf("iso-8859-1", "iso8859-1", "latin1", "latin-1", "windows-1252", "cp1252", "us-ascii")
    }
}
