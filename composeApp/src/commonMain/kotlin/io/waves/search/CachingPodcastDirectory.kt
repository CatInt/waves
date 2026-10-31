package io.waves.search

import io.waves.currentTimeMillis
import io.waves.model.Podcast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Small key-value text store that survives restarts, for [CachingPodcastDirectory]'s charts. */
interface DirectoryCacheStore {
    suspend fun read(key: String): String?
    suspend fun write(key: String, value: String)
}

/**
 * [PodcastDirectory] that answers repeat Discover requests from memory, and top charts from [store] across
 * restarts, so opening the app or switching tabs doesn't hit Apple again.
 *
 * - Top charts are kept for [chartTtlMs] (Apple's chart feed is marked uncacheable, so the HTTP cache can't help).
 *   When a refresh fails, the last chart is served instead, however old.
 * - Searches are kept for [searchTtlMs], the most recent [searchEntries] terms per store.
 * - Shows are kept by their Apple id, in memory for [lookupTtlMs] and in [store] for [showTtlMs]: every show seen
 *   in a chart, a search or a lookup goes in, so a later need for it (opening it, a top episode's cover) asks Apple
 *   nothing, across restarts too. A lookup that fails falls back on the one kept, however old.
 */
class CachingPodcastDirectory(
    private val delegate: PodcastDirectory,
    private val store: DirectoryCacheStore? = null,
    private val now: () -> Long = ::currentTimeMillis,
    private val chartTtlMs: Long = 6 * HOUR,
    private val searchTtlMs: Long = HOUR,
    private val lookupTtlMs: Long = 24 * HOUR,
    private val showTtlMs: Long = 30 * 24 * HOUR,
    private val searchEntries: Int = 64,
) : PodcastDirectory {

    private class Entry<T>(val value: T, val at: Long)

    @Serializable
    private data class StoredChart(val at: Long, val podcasts: List<Podcast>)

    @Serializable
    private data class StoredShows(val at: Long, val shows: List<ChartShow>)

    @Serializable
    private data class StoredShow(val at: Long, val podcast: Podcast)

    private val mutex = Mutex()
    private val charts = mutableMapOf<String, Entry<List<Podcast>>>()
    private val searches = LinkedHashMap<String, Entry<List<Podcast>>>()
    private val lookups = mutableMapOf<Long, Entry<Podcast>>()
    private val episodeCharts = mutableMapOf<String, Entry<List<ChartEpisode>>>()
    private val showCharts = mutableMapOf<String, Entry<List<ChartShow>>>()

    override suspend fun search(term: String, limit: Int): List<Podcast> = search(term, country = "", limit = limit)

    override suspend fun search(term: String, country: String, limit: Int): List<Podcast> {
        val key = "${country.trim().lowercase()}|$limit|${term.trim().lowercase()}"
        mutex.withLock {
            searches.remove(key)?.let { hit ->
                if (fresh(hit, searchTtlMs)) {
                    searches[key] = hit // most recently used last
                    return hit.value
                }
            }
        }
        val results = delegate.search(term, country, limit)
        mutex.withLock {
            searches[key] = Entry(results, now())
            while (searches.size > searchEntries) searches.remove(searches.keys.first())
            remember(results)
        }.also { keep(it) }
        return results
    }

    override suspend fun topPodcasts(country: String, limit: Int): List<Podcast> =
        chart("chart-${country.trim().lowercase().ifEmpty { "us" }}-$limit") { delegate.topPodcasts(country, limit) }

    /** Like the show charts: from memory or [store] while fresh, else fetched, falling back to the last one on failure. */
    override suspend fun topShows(country: String, limit: Int): List<ChartShow> {
        val key = "genres-${country.trim().lowercase().ifEmpty { "us" }}-$limit"
        val cached = mutex.withLock { showCharts[key] ?: readStoredShows(key)?.also { showCharts[key] = it } }
        if (cached != null && fresh(cached, chartTtlMs)) return cached.value
        val top = try {
            delegate.topShows(country, limit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return cached?.value ?: throw e
        }
        val entry = Entry(top, now())
        mutex.withLock {
            showCharts[key] = entry
            remember(top.map { it.podcast })
        }.also { keep(it) }
        store?.let { s ->
            try {
                s.write(key, withContext(Dispatchers.Default) { json.encodeToString(StoredShows(entry.at, top)) })
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        return top
    }

    private suspend fun readStoredShows(key: String): Entry<List<ChartShow>>? {
        val text = try {
            store?.read(key)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return null
        return runCatching { withContext(Dispatchers.Default) { json.decodeFromString<StoredShows>(text) } }.getOrNull()
            ?.let { Entry(it.shows, it.at) }
    }

    override suspend fun playable(episode: ChartEpisode, country: String): Pair<Podcast, io.waves.model.Episode>? =
        delegate.playable(episode, country)

    /** Kept in memory for [chartTtlMs], like the show charts; a failed refresh serves the last one. */
    override suspend fun topEpisodes(country: String, limit: Int): List<ChartEpisode> {
        val key = "${country.trim().lowercase().ifEmpty { "us" }}-$limit"
        val cached = mutex.withLock { episodeCharts[key] }
        if (cached != null && fresh(cached, chartTtlMs)) return cached.value
        val top = try {
            delegate.topEpisodes(country, limit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return cached?.value ?: throw e
        }
        mutex.withLock { episodeCharts[key] = Entry(top, now()) }
        return top
    }

    /** A show chart: from memory or [store] while fresh, else [fetch]ed (falling back to the last one on failure). */
    private suspend fun chart(key: String, fetch: suspend () -> List<Podcast>): List<Podcast> {
        val cached = mutex.withLock { charts[key] ?: readStored(key)?.also { charts[key] = it } }
        if (cached != null && fresh(cached, chartTtlMs)) return cached.value
        val top = try {
            fetch()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return cached?.value ?: throw e
        }
        val entry = Entry(top, now())
        mutex.withLock {
            charts[key] = entry
            remember(top)
        }.also { keep(it) }
        store?.let { s ->
            try {
                s.write(key, withContext(Dispatchers.Default) { json.encodeToString(StoredChart(entry.at, top)) })
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        return top
    }

    override suspend fun lookup(appleId: Long): Podcast? {
        mutex.withLock { lookups[appleId]?.takeIf { fresh(it, lookupTtlMs) }?.let { return it.value } }
        val kept = readStoredShow(appleId)
        if (kept != null && fresh(kept, showTtlMs)) {
            mutex.withLock { lookups[appleId] = kept }
            return kept.value
        }
        val podcast = try {
            delegate.lookup(appleId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return kept?.value ?: throw e
        } ?: return kept?.value
        keep(mutex.withLock { remember(listOf(podcast)) })
        return podcast
    }

    /** Writes [podcasts] to [store] by Apple id (the ones [remember] found new or changed). */
    private suspend fun keep(podcasts: List<Podcast>) {
        val s = store ?: return
        val at = now()
        for (p in podcasts) {
            val id = p.appleId ?: continue
            try {
                s.write(showKey(id), json.encodeToString(StoredShow(at, p)))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun readStoredShow(appleId: Long): Entry<Podcast>? {
        val text = try {
            store?.read(showKey(appleId))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return null
        return runCatching { json.decodeFromString<StoredShow>(text) }.getOrNull()?.let { Entry(it.podcast, it.at) }
    }

    private fun showKey(appleId: Long) = "show-$appleId"

    private fun fresh(entry: Entry<*>, ttlMs: Long): Boolean = now() - entry.at in 0 until ttlMs

    /** Call with [mutex] held. */
    /** Call with [mutex] held. Returns the ones it hadn't got, or had otherwise, for [keep] to store. */
    private fun remember(podcasts: List<Podcast>, at: Long = now()): List<Podcast> = podcasts.filter { p ->
        val id = p.appleId ?: return@filter false
        val changed = lookups[id]?.value != p
        lookups[id] = Entry(p, at)
        changed
    }

    /** Call with [mutex] held. */
    private suspend fun readStored(key: String): Entry<List<Podcast>>? {
        val text = try {
            store?.read(key)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return null
        // Off the main thread, like the encoding: a stored chart runs to tens of KB.
        val stored = withContext(Dispatchers.Default) { runCatching { json.decodeFromString<StoredChart>(text) }.getOrNull() }
            ?: return null
        remember(stored.podcasts, stored.at)
        return Entry(stored.podcasts, stored.at)
    }

    private companion object {
        const val HOUR = 60 * 60 * 1000L
        val json = Json { ignoreUnknownKeys = true }
    }
}
