package io.waves.net

import io.waves.currentTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readLine
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * Keeps the bodies of successful requests on disk for as long as each caller says, so the same request isn't made
 * again while its answer is fresh, and a request that fails (offline, say) can be answered with the last good one.
 *
 * Off until [install]ed with a directory (the app does at start); tests never turn it on, so their responses can't
 * leak from one test into the next. Entries untouched for [KEEP] are dropped at install.
 *
 * Not for what must always be current or is private: sync, sign-in, and audio (whose downloaded files are their cache).
 */
object ResponseCache {
    private val KEEP = 30.days
    private const val MEMORY_ENTRIES = 64

    private class Entry(val at: Long, val body: String)

    @kotlin.concurrent.Volatile
    private var dir: Path? = null
    private val lock = Mutex()
    private val memory = LinkedHashMap<String, Entry>()
    private val fs = SystemFileSystem

    /** Turns the cache on, in [directory] (created if need be), and drops entries older than [KEEP]. */
    suspend fun install(directory: String, now: Long = currentTimeMillis()) {
        val path = Path(directory)
        withContext(Dispatchers.IO) {
            fs.createDirectories(path)
            for (file in fs.list(path)) {
                val at = runCatching { fs.source(file).buffered().use { it.readLine(); it.readLine()?.toLongOrNull() } }.getOrNull()
                if (at == null || now - at > KEEP.inWholeMilliseconds) fs.delete(file, mustExist = false)
            }
        }
        dir = path
    }

    /**
     * The body for [key]: the one kept while younger than [ttl], else [fetch]ed (and kept). When [fetch] fails, the
     * one kept is returned however old, if [staleOnError] (off for answers that stop working, like a stream address).
     */
    suspend fun text(key: String, ttl: Duration, staleOnError: Boolean = true, fetch: suspend () -> String): String {
        val directory = dir ?: return fetch()
        val now = currentTimeMillis()
        val kept = read(directory, key)
        if (kept != null && now - kept.at in 0 until ttl.inWholeMilliseconds) return kept.body
        val body = try {
            fetch()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (staleOnError && kept != null) return kept.body
            throw e
        }
        write(directory, key, Entry(now, body))
        return body
    }

    private suspend fun read(directory: Path, key: String): Entry? {
        lock.withLock { memory[key]?.let { return it } }
        val entry = withContext(Dispatchers.IO) {
            runCatching {
                val file = Path(directory, fileName(key))
                if (!fs.exists(file)) return@runCatching null
                fs.source(file).buffered().use { source ->
                    if (source.readLine() != key.replace('\n', ' ')) return@use null // another key with the same hash
                    val at = source.readLine()?.toLongOrNull() ?: return@use null
                    Entry(at, source.readString())
                }
            }.getOrNull()
        } ?: return null
        lock.withLock { remember(key, entry) }
        return entry
    }

    private suspend fun write(directory: Path, key: String, entry: Entry) {
        lock.withLock { remember(key, entry) }
        withContext(Dispatchers.IO) {
            runCatching {
                val file = Path(directory, fileName(key))
                val temp = Path(directory, fileName(key) + ".tmp")
                fs.sink(temp).buffered().use { sink ->
                    sink.writeString(key.replace('\n', ' '))
                    sink.writeString("\n${entry.at}\n")
                    sink.writeString(entry.body)
                }
                fs.atomicMove(temp, file)
            }
        }
    }

    /** Call with [lock] held: most recently used last, the oldest dropped past [MEMORY_ENTRIES]. */
    private fun remember(key: String, entry: Entry) {
        memory.remove(key)
        memory[key] = entry
        while (memory.size > MEMORY_ENTRIES) memory.remove(memory.keys.first())
    }

    /** 64-bit FNV-1a of [key] in hex: short, file-name safe, and checked against the key kept inside. */
    private fun fileName(key: String): String {
        var hash = -0x340d631b7bdddcdbL
        for (c in key) {
            hash = hash xor c.code.toLong()
            hash *= 0x100000001b3L
        }
        return hash.toULong().toString(16)
    }

    /** Turns the cache off and forgets what's in memory (tests). */
    internal fun uninstall() {
        dir = null
        memory.clear()
    }
}
