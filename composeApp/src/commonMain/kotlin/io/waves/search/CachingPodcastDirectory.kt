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
 * - Lookups are kept for [lookupTtlMs], and every show seen in a chart or search seeds them, so opening a show from
 *   Discover needs no lookup.
 */
class CachingPodcastDirectory(
    private val delegate: PodcastDirectory,
    private val store: DirectoryCacheStore? = null,
    private val now: () -> Long = ::currentTimeMillis,
    private val chartTtlMs: Long = 6 * HOUR,
    private val searchTtlMs: Long = HOUR,
    private val lookupTtlMs: Long = 24 * HOUR,
    private val searchEntries: Int = 64,
) : PodcastDirectory {

    private class Entry<T>(val value: T, val at: Long)

    @Serializable
    private data class StoredChart(val at: Long, val podcasts: List<Podcast>)

    private val mutex = Mutex()
    private val charts = mutableMapOf<String, Entry<List<Podcast>>>()
    private val searches = LinkedHashMap<String, Entry<List<Podcast>>>()
    private val lookups = mutableMapOf<Long, Entry<Podcast>>()

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
        }
        return results
    }

    override suspend fun topPodcasts(country: String, limit: Int): List<Podcast> {
        val key = "chart-${country.trim().lowercase().ifEmpty { "us" }}-$limit"
        val cached = mutex.withLock { charts[key] ?: readStored(key)?.also { charts[key] = it } }
        if (cached != null && fresh(cached, chartTtlMs)) return cached.value
        val top = try {
            delegate.topPodcasts(country, limit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return cached?.value ?: throw e
        }
        val entry = Entry(top, now())
        mutex.withLock {
            charts[key] = entry
            remember(top)
        }
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
        val podcast = delegate.lookup(appleId) ?: return null
        mutex.withLock { remember(listOf(podcast)) }
        return podcast
    }

    private fun fresh(entry: Entry<*>, ttlMs: Long): Boolean = now() - entry.at in 0 until ttlMs

    /** Call with [mutex] held. */
    private fun remember(podcasts: List<Podcast>, at: Long = now()) {
        for (p in podcasts) p.appleId?.let { lookups[it] = Entry(p, at) }
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
