package io.waves.search

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.waves.model.Episode
import io.waves.model.Podcast
import kotlinx.datetime.Instant
import io.waves.net.ResponseCache
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
        val body = getText(
            "$ITUNES/search", SearchTtl,
            listOfNotNull(
                "media" to "podcast", "entity" to "podcast", "term" to trimmed, "limit" to limit.coerceIn(1, 200),
                cc.takeIf { it.isNotEmpty() }?.let { "country" to it },
            ),
        )
        return decode<ItunesResponse>(body).results.mapNotNull { it.toPodcast() }
    }

    override suspend fun lookup(appleId: Long): Podcast? = lookupMany(listOf(appleId)).firstOrNull()

    override suspend fun topPodcasts(country: String, limit: Int): List<Podcast> {
        val cc = country.trim().lowercase().ifEmpty { "us" }
        val n = limit.coerceIn(1, 100)
        val body = getText("$CHARTS/$cc/podcasts/top/$n/podcasts.json", ChartTtl)
        val ids = decode<ChartResponse>(body).feed.results.mapNotNull { it.id.toLongOrNull() }
        if (ids.isEmpty()) return emptyList()
        val byId = lookupMany(ids, country = cc).associateBy { it.appleId }
        return ids.mapNotNull { byId[it] } // preserve chart order
    }

    /**
     * The top shows by genre: the genres of the chart's top [limit], in the order their first show comes, each with the
     * top [PER_GENRE] of its own chart (iTunes' older chart feed, which has genres the newer one lacks), ranked. A genre
     * whose chart doesn't load keeps just its shows from the top [limit], unranked.
     */
    override suspend fun topShows(country: String, limit: Int): List<ChartShow> = coroutineScope {
        val cc = country.trim().lowercase().ifEmpty { "us" }
        val n = limit.coerceIn(1, 100)
        val entries = decode<ChartResponse>(getText("$CHARTS/$cc/podcasts/top/$n/podcasts.json", ChartTtl)).feed.results
        val genres = entries.mapNotNull { e ->
            val g = e.genres.firstOrNull() ?: return@mapNotNull null
            val id = g.genreId?.toIntOrNull() ?: return@mapNotNull null
            id to (g.name ?: return@mapNotNull null)
        }.distinctBy { it.first }
        val charts = genres.map { (id, name) ->
            async {
                val ranked = genreChart(cc, id).take(PER_GENRE)
                val fallback = entries.filter { it.genres.firstOrNull()?.genreId?.toIntOrNull() == id }.mapNotNull { it.id.toLongOrNull() }
                Triple(name, ranked.ifEmpty { fallback }, ranked.isNotEmpty())
            }
        }.awaitAll()
        val byId = lookupMany(charts.flatMap { it.second }.distinct(), country = cc).associateBy { it.appleId }
        charts.flatMap { (name, ids, ranked) ->
            ids.mapIndexedNotNull { i, id -> byId[id]?.let { ChartShow(it, name, if (ranked) i + 1 else null) } }
        }
    }

    /** Show ids on [genreId]'s chart in order; none when it can't be had. */
    private suspend fun genreChart(country: String, genreId: Int): List<Long> = try {
        val body = getText("$ITUNES/$country/rss/toppodcasts/limit=200/genre=$genreId/json", ChartTtl)
        withContext(Dispatchers.Default) { genreChartIds(body) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyList()
    }

    override suspend fun topEpisodes(country: String, limit: Int): List<ChartEpisode> {
        val cc = country.trim().lowercase().ifEmpty { "us" }
        val n = limit.coerceIn(1, 100)
        val body = getText("$CHARTS/$cc/podcasts/top/$n/podcast-episodes.json", ChartTtl)
        return decode<EpisodeChartResponse>(body).feed.results.mapNotNull { it.toChartEpisode() }
    }

    /**
     * Apple lists a show's recent episodes, guid and audio address included, when it's looked up with them; the chart's
     * is found among them by its id. The guid is the feed's own, so it's the same episode once the feed is fetched.
     */
    override suspend fun playable(episode: ChartEpisode, country: String): Pair<Podcast, Episode>? {
        if (episode.appleId == 0L) return null
        val cc = country.trim().lowercase().ifEmpty { "us" }
        val body = getText(
            "$ITUNES/lookup", EpisodesTtl,
            listOf("id" to episode.showAppleId, "entity" to "podcastEpisode", "limit" to 100, "country" to cc),
        )
        val results = decode<ItunesResponse>(body).results
        val show = results.firstNotNullOfOrNull { it.toPodcast() } ?: return null
        val found = results.firstOrNull { it.kind == "podcast-episode" && it.trackId == episode.appleId } ?: return null
        val audio = found.episodeUrl?.takeIf { it.isNotBlank() } ?: return null
        return show to Episode(
            guid = found.episodeGuid?.takeIf { it.isNotBlank() } ?: audio,
            podcastFeedUrl = show.feedUrl,
            title = found.trackName ?: episode.title,
            audioUrl = audio,
            publishedAtEpochMs = found.releaseDate?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() },
            durationSeconds = found.trackTimeMillis?.div(1000),
            description = found.description,
            artworkUrl = found.artworkUrl600 ?: episode.artworkUrl,
        )
    }

    /** Batched lookup (`?id=1,2,3`); results lacking a feed URL are dropped. */
    private suspend fun lookupMany(ids: List<Long>, country: String? = null): List<Podcast> =
        ids.distinct().chunked(LOOKUP_BATCH).flatMap { chunk ->
            val body = getText(
                "$ITUNES/lookup", LookupTtl,
                listOfNotNull("id" to chunk.joinToString(","), "entity" to "podcast", country?.let { "country" to it }),
            )
            decode<ItunesResponse>(body).results.mapNotNull { it.toPodcast() }
        }

    /** [url] with [params], kept for [ttl] in the [ResponseCache] (and answered from it when Apple can't be reached). */
    private suspend fun getText(url: String, ttl: Duration, params: List<Pair<String, Any>> = emptyList()): String =
        ResponseCache.text("apple " + url + params.joinToString("&", prefix = "?") { (k, v) -> "$k=$v" }, ttl) {
            fetchText(url) { params.forEach { (k, v) -> parameter(k, v) } }
        }

    private suspend fun fetchText(url: String, block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {}): String {
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
        val episodeUrl: String? = null,
        val episodeGuid: String? = null,
        val trackTimeMillis: Long? = null,
        val releaseDate: String? = null,
        val description: String? = null,
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
    internal data class ChartEntry(val id: String, val name: String? = null, val genres: List<ChartGenre> = emptyList())

    @Serializable
    internal data class ChartGenre(val genreId: String? = null, val name: String? = null)

    @Serializable
    internal data class EpisodeChartResponse(val feed: EpisodeChartFeed)

    @Serializable
    internal data class EpisodeChartFeed(val results: List<EpisodeChartEntry> = emptyList())

    @Serializable
    internal data class EpisodeChartEntry(
        val id: String? = null,
        val name: String? = null,
        val artistName: String? = null,
        val artworkUrl100: String? = null,
        val url: String? = null,
    ) {
        /** Its show is the `id…` in the episode's podcasts.apple.com link; the 100 px artwork has larger siblings. */
        fun toChartEpisode(): ChartEpisode? {
            val show = url?.let { showIdInUrl.find(it) }?.groupValues?.get(1)?.toLongOrNull() ?: return null
            return ChartEpisode(
                title = name ?: return null,
                showTitle = artistName.orEmpty(),
                artworkUrl = artworkUrl100?.replace("100x100bb", "600x600bb"),
                showAppleId = show,
                appleId = id?.toLongOrNull() ?: 0,
            )
        }
    }

    internal companion object {
        /** Charts move daily; searches change rarely; a show's details (its feed above all) hardly ever. */
        val ChartTtl = 3.hours
        val SearchTtl = 6.hours
        val LookupTtl = 7.days
        /** A show's recent episodes: new ones come out daily. */
        val EpisodesTtl = 1.hours

        const val ITUNES = "https://itunes.apple.com"
        const val CHARTS = "https://rss.marketingtools.apple.com/api/v2"
        const val LOOKUP_BATCH = 100
        /** Shows per genre: enough to fill three rows of covers on a wide window. */
        const val PER_GENRE = 24
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

private val showIdInUrl = Regex("""/id(\d+)""")

/**
 * Show ids in an iTunes chart feed, in chart order: `feed.entry[].id.attributes["im:id"]`. The feed gives a lone
 * entry as an object rather than a list of one.
 */
internal fun genreChartIds(body: String): List<Long> {
    val feed = runCatching { Json.parseToJsonElement(body).jsonObject["feed"]?.jsonObject }.getOrNull() ?: return emptyList()
    val entries = when (val e = feed["entry"]) {
        is JsonArray -> e.toList()
        is JsonObject -> listOf(e)
        else -> emptyList()
    }
    return entries.mapNotNull { entry ->
        entry.jsonObject["id"]?.jsonObject?.get("attributes")?.jsonObject?.get("im:id")?.jsonPrimitive?.content?.toLongOrNull()
    }
}
