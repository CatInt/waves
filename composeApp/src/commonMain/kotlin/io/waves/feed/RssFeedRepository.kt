package io.waves.feed

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess

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
        val xml = decode(bytes, response.contentType()?.parameter("charset"))
        return FeedParser.parse(feedUrl, xml)
    }

    internal companion object {
        const val USER_AGENT = "Waves/0.1 (podcast app)"

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
