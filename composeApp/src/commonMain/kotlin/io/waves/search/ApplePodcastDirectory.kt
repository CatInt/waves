package io.waves.search

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.waves.model.Podcast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Thrown when the Apple Podcasts directory cannot be reached or returns an error. */
class PodcastDirectoryException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * [PodcastDirectory] backed by the public iTunes Search / Lookup APIs and Apple's marketing-tools charts feed.
 * No API key needed. iTunes serves JSON as `text/javascript`, so bodies are decoded manually.
 */
class ApplePodcastDirectory(private val client: HttpClient) : PodcastDirectory {

    override suspend fun search(term: String, limit: Int): List<Podcast> = search(term, country = "", limit = limit)

    /** A blank [country] uses iTunes' default store (US). */
    override suspend fun search(term: String, country: String, limit: Int): List<Podcast> {
        val cc = country.trim().lowercase()
        val trimmed = term.trim()
        if (trimmed.isEmpty()) return emptyList()
        // A pasted podcasts.apple.com link resolves directly to that show.
        parseApplePodcastsUrl(trimmed)?.let { id -> return listOfNotNull(lookup(id)) }
        val body = getText("$ITUNES/search") {
            parameter("media", "podcast")
            parameter("entity", "podcast")
            parameter("term", trimmed)
            parameter("limit", limit.coerceIn(1, 200))
            if (cc.isNotEmpty()) parameter("country", cc)
        }
        return decode<ItunesResponse>(body).results.mapNotNull { it.toPodcast() }
    }

    override suspend fun lookup(appleId: Long): Podcast? = lookupMany(listOf(appleId)).firstOrNull()

    override suspend fun topPodcasts(country: String, limit: Int): List<Podcast> {
        val cc = country.trim().lowercase().ifEmpty { "us" }
        val n = limit.coerceIn(1, 100)
        val body = getText("$CHARTS/$cc/podcasts/top/$n/podcasts.json")
        val ids = decode<ChartResponse>(body).feed.results.mapNotNull { it.id.toLongOrNull() }
        if (ids.isEmpty()) return emptyList()
        val byId = lookupMany(ids, country = cc).associateBy { it.appleId }
        return ids.mapNotNull { byId[it] } // preserve chart order
    }

    /** Batched lookup (`?id=1,2,3`); results lacking a feed URL are dropped. */
    private suspend fun lookupMany(ids: List<Long>, country: String? = null): List<Podcast> =
        ids.distinct().chunked(LOOKUP_BATCH).flatMap { chunk ->
            val body = getText("$ITUNES/lookup") {
                parameter("id", chunk.joinToString(","))
                parameter("entity", "podcast")
                if (country != null) parameter("country", country)
            }
            decode<ItunesResponse>(body).results.mapNotNull { it.toPodcast() }
        }

    private suspend fun getText(url: String, block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {}): String {
        var lastError: Exception? = null
        repeat(ATTEMPTS) { attempt ->
            if (attempt > 0) delay(RETRY_DELAY_MS * attempt)
            try {
                val response = client.get(url, block)
                if (response.status.isSuccess()) return response.bodyAsText()
                lastError = PodcastDirectoryException("HTTP ${response.status.value} from $url")
                // Apple's charts endpoint intermittently returns 5xx; other statuses are not worth retrying.
                if (response.status.value < 500) throw lastError!!
            } catch (e: CancellationException) {
                throw e
            } catch (e: PodcastDirectoryException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        val e = lastError
        throw e as? PodcastDirectoryException ?: PodcastDirectoryException("Request to $url failed: ${e?.let { it.message ?: it::class.simpleName }}", e)
    }

    // Off the main thread: a chart's lookups run to hundreds of KB, and decoding them there stalls the UI.
    private suspend inline fun <reified T> decode(body: String): T = withContext(Dispatchers.Default) {
        try {
            json.decodeFromString<T>(body)
        } catch (e: Exception) {
            throw PodcastDirectoryException("Unexpected response from Apple: ${e.message}", e)
        }
    }

    @Serializable
    internal data class ItunesResponse(val resultCount: Int = 0, val results: List<ItunesResult> = emptyList())

    @Serializable
    internal data class ItunesResult(
        val kind: String? = null,
        val collectionId: Long? = null,
        val trackId: Long? = null,
        val collectionName: String? = null,
        val trackName: String? = null,
        val artistName: String? = null,
        val feedUrl: String? = null,
        val artworkUrl600: String? = null,
        val artworkUrl100: String? = null,
        val artworkUrl60: String? = null,
        val artworkUrl30: String? = null,
    ) {
        fun toPodcast(): Podcast? {
            if (kind != null && kind != "podcast") return null
            val feed = feedUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return Podcast(
                feedUrl = feed,
                title = collectionName ?: trackName ?: feed,
                author = artistName,
                description = null, // iTunes search results carry no show description; the RSS feed has it.
                artworkUrl = artworkUrl600 ?: artworkUrl100 ?: artworkUrl60 ?: artworkUrl30,
                appleId = collectionId ?: trackId,
            )
        }
    }

    @Serializable
    internal data class ChartResponse(val feed: ChartFeed)

    @Serializable
    internal data class ChartFeed(val results: List<ChartEntry> = emptyList())

    @Serializable
    internal data class ChartEntry(val id: String, val name: String? = null)

    internal companion object {
        const val ITUNES = "https://itunes.apple.com"
        const val CHARTS = "https://rss.marketingtools.apple.com/api/v2"
        const val LOOKUP_BATCH = 100
        const val ATTEMPTS = 3
        const val RETRY_DELAY_MS = 500L
        val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    }
}

private val appleIdInPath = Regex("""/id(\d+)""")
private val appleHost = Regex("""^(?:https?://)?(?:[a-z0-9-]+\.)*(?:podcasts|itunes)\.apple\.com(?:[/?#:]|$)""", RegexOption.IGNORE_CASE)

/**
 * Extracts the Apple Podcasts collection id from a link such as
 * `https://podcasts.apple.com/us/podcast/the-daily/id1200361736?i=1000...` (episode `?i=` ids are ignored).
 * Also accepts `itunes.apple.com` links and `?id=` query parameters. Returns null for anything else.
 */
fun parseApplePodcastsUrl(url: String): Long? {
    val s = url.trim()
    if (!appleHost.containsMatchIn(s)) return null
    val path = s.substringBefore('?').substringBefore('#')
    appleIdInPath.findAll(path).lastOrNull()?.let { return it.groupValues[1].toLongOrNull() }
    val query = s.substringAfter('?', "").substringBefore('#')
    return query.split('&').firstOrNull { it.startsWith("id=") }?.removePrefix("id=")?.toLongOrNull()
}
