package io.waves.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import io.waves.model.Episode
import io.waves.model.EpisodeId
import io.waves.offline.PlaybackCache
import io.waves.offline.PlaybackCacheEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-wide handle so the playback service (which owns the [SimpleCache]) and the UI (which owns the
 * catalog) can drop the same bytes. Automatic eviction is intentionally not done here: [io.waves.offline.LocalMediaCleaner]
 * decides what to drop, and it does nothing while offline mode is on.
 */
object PlaybackCacheBridge {
    @Volatile var catalog: PlaybackCache? = null

    private val cacheRef = AtomicReference<Cache?>(null)
    private val upstreamRef = AtomicReference<DataSource.Factory?>(null)
    private val pendingKeys = ConcurrentLinkedQueue<String>()

    fun attach(cache: Cache, upstream: DataSource.Factory? = null) {
        cacheRef.set(cache)
        upstreamRef.set(upstream)
        while (true) {
            val key = pendingKeys.poll() ?: break
            runCatching { cache.removeResource(key) }
        }
    }

    fun detach(cache: Cache) {
        if (cacheRef.compareAndSet(cache, null)) upstreamRef.set(null)
    }

    internal val claims = CacheWriteClaims()

    private class LocalCopy(val episodeId: EpisodeId, val path: String)

    /** Cache key (episode id) → complete local copy, as reported by the download manager. */
    private val localCopies = ConcurrentHashMap<String, LocalCopy>()

    /** Key → path of a local copy found to match the stream; remembered because dropping the stream loses the length. */
    private val verified = ConcurrentHashMap<String, String>()

    private val dropped = ConcurrentHashMap.newKeySet<String>()

    /** Cache key → the byte the player reads next (ahead of what it plays, by its buffer), for [fill] to follow. */
    private val playheads = ConcurrentHashMap<String, Long>()

    internal fun notePlayhead(key: String, position: Long) {
        playheads[key] = position
    }
    private val dropScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun onLocalCopy(episodeId: EpisodeId, path: String) {
        val key = episodeId.toString()
        localCopies[key] = LocalCopy(episodeId, path)
        // A new copy (a download again after one was deleted, say): what the player streams since is dropped again.
        dropped.remove("$key\u0000$path")
    }

    internal fun localCopyPath(key: String): String? = localCopies[key]?.path

    /** Whether a local copy can stand in for a stream: yes, no, or not known yet. */
    internal sealed interface CopyCheck {
        class Usable(val file: File) : CopyCheck
        /** Gone, or not the same file as the stream (another format of the same video). */
        data object Unusable : CopyCheck
        /** The stream's length isn't known yet (or the cache isn't open): ask again later. */
        data object Unknown : CopyCheck
    }

    /**
     * [path] as a file to read [key]'s stream from instead of the network: it must exist and be exactly as long as
     * the stream the player cached under [key] (so it is the same file, not another format of the same video).
     */
    @OptIn(UnstableApi::class)
    internal fun checkLocalCopy(key: String, path: String): CopyCheck {
        val file = File(path)
        if (!file.isFile) {
            localCopies.computeIfPresent(key) { _, copy -> copy.takeIf { it.path != path } }
            verified.remove(key, path)
            dropped.remove("$key\u0000$path")
            return CopyCheck.Unusable
        }
        if (verified[key] == path) return CopyCheck.Usable(file)
        val cache = cacheRef.get() ?: return CopyCheck.Unknown
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        if (length <= 0) return CopyCheck.Unknown
        if (file.length() != length) return CopyCheck.Unusable
        verified[key] = path
        return CopyCheck.Usable(file)
    }

    /**
     * Deletes what the player streamed of [key] (bytes and catalog row) now that it plays the local copy at [path]:
     * kept, it would take the space twice. Skipped while a player still writes it; [io.waves.offline.LocalMediaCleaner]
     * drops it once that episode is no longer playing.
     */
    internal fun dropStreamedCopy(key: String, path: String) {
        val copy = localCopies[key]?.takeIf { it.path == path } ?: return
        if (claims.hasWriter(key) || !dropped.add("$key\u0000$path")) return
        dropScope.launch {
            runCatching {
                val catalog = catalog
                if (catalog != null) catalog.remove(copy.episodeId) else removeResource(copy.episodeId, null)
            }
        }
    }

    /**
     * Fetches whatever of [episode] the cache doesn't hold yet into it, under the key the player uses, so the cache
     * ends up with the whole file and nothing is fetched twice; then reports it to [catalog]. False when the cache
     * isn't open. Stops when the calling coroutine is cancelled.
     *
     * It goes a chunk ([FILL_CHUNK_BYTES]) at a time, each time from where the player reads ([notePlayhead]): what it
     * needs next comes first, and after a seek the next chunk starts at the new spot. Once everything ahead is
     * cached it fills the gaps behind. A chunk reserves only its own bytes in the cache, and the player waits for
     * those rather than fetching them a second time.
     *
     * A player streaming the episode hands its write over first (see [CacheWriteClaims]) and reads what this writes.
     * If it doesn't within [HANDOFF_TIMEOUT_MS] (paused with a full buffer, so it may never read again), this gives up
     * and returns false, so the caller caches the episode its own way.
     */
    @OptIn(UnstableApi::class)
    suspend fun fill(episode: Episode): Boolean {
        val cache = cacheRef.get() ?: return false
        val upstream = upstreamRef.get() ?: return false
        val key = episode.id.toString()
        claims.beginFill(key)
        android.util.Log.d(FILL_LOG_TAG, "$key: fill asked (player at ${playheads[key]})")
        try {
            if (!claims.awaitNoWriters(key, HANDOFF_TIMEOUT_MS)) {
                android.util.Log.d(FILL_LOG_TAG, "$key: player didn't hand over; cached as a file instead")
                return false
            }
            // A seek hands over as it closes the player's old read, a moment before its new one says where it reads
            // now: wait for that, so the first chunk goes where the player went rather than where it was.
            delay(HANDOFF_SETTLE_MS)
            withContext(Dispatchers.IO) { fillChunks(cache, upstream, Uri.parse(episode.audioUrl), key) }
            android.util.Log.d(FILL_LOG_TAG, "$key: fill done")
        } catch (e: Exception) {
            android.util.Log.d(FILL_LOG_TAG, "$key: fill stopped: $e")
            throw e
        } finally {
            claims.endFill(key)
        }
        val length = cache.getContentMetadata(key).get(ContentMetadata.KEY_CONTENT_LENGTH, -1L).takeIf { it > 0L }
        val bytes = cache.getCachedSpans(key).sumOf { it.length }
        catalog?.upsert(PlaybackCacheEntry(episode.id, episode.audioUrl, bytes, length, System.currentTimeMillis()))
        return true
    }

    @OptIn(UnstableApi::class)
    private suspend fun fillChunks(cache: Cache, upstream: DataSource.Factory, uri: Uri, key: String) {
        val buffer = ByteArray(64 * 1024)
        var idle = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
            val gap = nextFillGap({ position, bytes -> cache.getCachedLength(key, position, bytes) }, playheads[key] ?: 0L, length) ?: return
            val chunk = if (gap.length == FillGap.UNKNOWN) C.LENGTH_UNSET.toLong() else minOf(gap.length, FILL_CHUNK_BYTES)
            android.util.Log.d(FILL_LOG_TAG, "$key: chunk at ${gap.position} (player at ${playheads[key]}, size $length)")
            val source = CacheDataSource(
                cache,
                upstream.createDataSource(),
                FileDataSource(),
                // Small fragments, so a player waiting on these bytes gets them soon.
                CacheDataSink(cache, FILL_FRAGMENT_BYTES),
                CacheDataSource.FLAG_BLOCK_ON_CACHE,
                null,
            )
            var read = 0L
            try {
                // Open-ended only while the size is unknown: that request tells the cache the size.
                source.open(DataSpec.Builder().setUri(uri).setKey(key).setPosition(gap.position).setLength(chunk).build())
                while (read < FILL_CHUNK_BYTES) {
                    currentCoroutineContext().ensureActive()
                    val n = source.read(buffer, 0, minOf(buffer.size.toLong(), FILL_CHUNK_BYTES - read).toInt())
                    if (n == C.RESULT_END_OF_INPUT) break
                    read += n
                }
            } finally {
                source.close()
            }
            // Nothing came (at the end of a file of unknown size, the cache learns the size from that): look again,
            // but don't spin on a gap that never fills.
            if (read > 0) idle = 0 else if (++idle >= 3) throw java.io.IOException("Nothing to fetch at ${gap.position} for $key")
        }
    }

    /**
     * Copies [episode]'s audio to [dest] when the cache holds the whole file, returning its size; null when it doesn't
     * (or the cache isn't open). Writes beside [dest] first, so a copy cut short leaves nothing behind.
     */
    @OptIn(UnstableApi::class)
    fun copyFullyCached(episode: Episode, dest: File): Long? {
        val cache = cacheRef.get() ?: return null
        val (key, length) = listOf(episode.id.toString(), episode.audioUrl).firstNotNullOfOrNull { key ->
            val length = cache.getContentMetadata(key).get(ContentMetadata.KEY_CONTENT_LENGTH, -1L)
            if (length > 0L && cache.isCached(key, 0, length)) key to length else null
        } ?: return null
        val temp = File(dest.path + ".copying")
        // No upstream: it reads only what's cached, and fails rather than fetch anything.
        val source = CacheDataSource(cache, null)
        try {
            source.open(DataSpec.Builder().setUri(Uri.parse(episode.audioUrl)).setKey(key).build())
            temp.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = source.read(buffer, 0, buffer.size)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                }
            }
        } catch (e: Exception) {
            temp.delete()
            throw e
        } finally {
            source.close()
        }
        if (temp.length() != length || !temp.renameTo(dest)) {
            temp.delete()
            return null
        }
        return length
    }

    /** Deletes cached bytes for [episodeId] and, when known, for [audioUrl] (the key ExoPlayer uses if no custom key was set). */
    fun removeResource(episodeId: EpisodeId, audioUrl: String?) {
        val cache = cacheRef.get()
        val keys = listOfNotNull(episodeId.toString(), audioUrl?.takeIf { it.isNotBlank() })
        if (cache == null) {
            pendingKeys.addAll(keys)
            return
        }
        for (key in keys) runCatching { cache.removeResource(key) }
    }

    private const val HANDOFF_TIMEOUT_MS = 30_000L
    private const val HANDOFF_SETTLE_MS = 100L
    /** `adb logcat -s WavesFill`: each chunk a fill fetches, and where the player was. */
    private const val FILL_LOG_TAG = "WavesFill"
    /** How much a fill fetches before looking again at where the player is. */
    private const val FILL_CHUNK_BYTES = 4L * 1024 * 1024
    private const val FILL_FRAGMENT_BYTES = 1L * 1024 * 1024
}

/**
 * ExoPlayer disk cache for streamed episode audio. Downloaded `file://` URIs bypass it.
 * Progress is written to [PlaybackCacheBridge.catalog] so offline Home can list fully cached episodes.
 */
@OptIn(UnstableApi::class)
class PlaybackCacheController(
    context: Context,
    httpFactory: HttpDataSource.Factory,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reports = Channel<Episode>(Channel.CONFLATED)
    private val cache: SimpleCache = SimpleCache(
        File(appContext.cacheDir, "playback-cache").apply { mkdirs() },
        NoOpCacheEvictor(),
        StandaloneDatabaseProvider(appContext),
    )

    val mediaSourceFactory: MediaSource.Factory

    init {
        val upstream = DefaultDataSource.Factory(appContext, RangedHttpDataSource.Factory(httpFactory))
        PlaybackCacheBridge.attach(cache, upstream)
        val caching = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        // No sink: reads cached bytes and streams the rest, switching back to the cache as a fill catches up. Bytes a
        // fill is fetching right now (its chunk, at the playhead) it waits for, instead of fetching them a second time.
        val readOnly = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstream)
            .setCacheWriteDataSinkFactory(null)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR or CacheDataSource.FLAG_BLOCK_ON_CACHE)
        val sources = RemoteCachingDataSource.Factory(caching, readOnly, upstream, PlaybackCacheBridge.claims)
        mediaSourceFactory = HlsTolerantMediaSourceFactory(DefaultMediaSourceFactory(sources), sources)
        scope.launch {
            for (episode in reports) {
                try {
                    val entry = snapshot(episode) ?: continue
                    PlaybackCacheBridge.catalog?.upsert(entry)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The cache may be closing; the next playback reports again.
                }
            }
        }
    }

    fun attachTo(player: Player) {
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                val episode = player.currentMediaItem?.let { AndroidAudioPlayer.episodeOf(it) } ?: return
                reports.trySend(episode)
            }
        })
    }

    fun release() {
        reports.close()
        scope.cancel()
        PlaybackCacheBridge.detach(cache)
        cache.release()
    }

    private fun snapshot(episode: Episode): PlaybackCacheEntry? {
        if (!episode.audioUrl.startsWith("http", ignoreCase = true)) return null
        val keys = listOf(episode.id.toString(), episode.audioUrl)
        var bestKey: String? = null
        var bestBytes = 0L
        for (key in keys) {
            val bytes = cache.getCachedSpans(key).sumOf { it.length }
            if (bytes > bestBytes) {
                bestBytes = bytes
                bestKey = key
            }
        }
        val key = bestKey ?: return null
        if (bestBytes <= 0L) return null
        val length = cache.getContentMetadata(key).get(ContentMetadata.KEY_CONTENT_LENGTH, -1L).takeIf { it > 0L }
        val bytes = if (length != null && cache.isCached(key, 0, length)) length else bestBytes
        return PlaybackCacheEntry(
            episodeId = episode.id,
            audioUrl = episode.audioUrl,
            bytesCached = bytes,
            totalBytes = length,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
    }
}

/**
 * Uses the cache for http(s) and reads `file://` downloads directly, so a completed download is not copied
 * into the playback cache a second time.
 *
 * Streams write to the cache unless [PlaybackCacheBridge.fill] is filling the same key; then they only read it, and a
 * stream already writing reopens read-only where it is, freeing the range for the fill (see [CacheWriteClaims]).
 *
 * A stream whose episode gets a complete local copy meanwhile (a YouTube episode's cache file, a download) carries on
 * from that file at the same byte, so it stops using the network without a gap in playback.
 */
@OptIn(UnstableApi::class)
private class RemoteCachingDataSource(
    private val writing: DataSource,
    private val reading: DataSource,
    private val direct: DataSource,
    private val claims: CacheWriteClaims,
) : DataSource {
    private var current: DataSource? = null
    private var spec: DataSpec? = null
    /** Cache key of the stream being read from the network or cache; null once it reads a local file. */
    private var remoteKey: String? = null
    private var claimedKey: String? = null
    /** A local copy found unusable for this stream (gone or a different file), not checked again. */
    private var rejectedCopy: String? = null
    private var opened = C.LENGTH_UNSET.toLong()
    private var bytesRead = 0L

    class Factory(
        private val writing: DataSource.Factory,
        private val reading: DataSource.Factory,
        private val direct: DataSource.Factory,
        private val claims: CacheWriteClaims,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = RemoteCachingDataSource(
            writing.createDataSource(), reading.createDataSource(), direct.createDataSource(), claims,
        )
    }

    override fun addTransferListener(transferListener: TransferListener) {
        writing.addTransferListener(transferListener)
        reading.addTransferListener(transferListener)
        direct.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        bytesRead = 0
        rejectedCopy = null
        val scheme = dataSpec.uri.scheme
        if (scheme != "http" && scheme != "https") {
            current = direct
            opened = direct.open(dataSpec)
            return opened
        }
        val key = dataSpec.key ?: dataSpec.uri.toString()
        localCopy(key)?.let { file ->
            current = direct
            opened = direct.open(dataSpec.buildUpon().setUri(Uri.fromFile(file)).build())
            PlaybackCacheBridge.dropStreamedCopy(key, file.path)
            return opened
        }
        remoteKey = key
        PlaybackCacheBridge.notePlayhead(key, dataSpec.position)
        val chosen = if (claims.claimWrite(key)) {
            claimedKey = key
            writing
        } else {
            reading
        }
        current = chosen
        opened = chosen.open(dataSpec)
        return opened
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        remoteKey?.let { key ->
            val file = localCopy(key)
            if (file != null) {
                val more = reopen(direct, Uri.fromFile(file))
                PlaybackCacheBridge.dropStreamedCopy(key, file.path)
                if (!more) return C.RESULT_END_OF_INPUT
            } else if (claimedKey == key && claims.isFilling(key)) {
                if (!reopen(reading, null)) return C.RESULT_END_OF_INPUT
            }
        }
        val n = current!!.read(buffer, offset, length)
        if (n > 0) {
            bytesRead += n
            remoteKey?.let { PlaybackCacheBridge.notePlayhead(it, spec!!.position + bytesRead) }
        }
        return n
    }

    /** [key]'s complete local copy, when there is one and it holds the same bytes as the stream. */
    private fun localCopy(key: String): File? {
        val path = PlaybackCacheBridge.localCopyPath(key) ?: return null
        if (path == rejectedCopy) return null
        return when (val check = PlaybackCacheBridge.checkLocalCopy(key, path)) {
            is PlaybackCacheBridge.CopyCheck.Usable -> check.file
            PlaybackCacheBridge.CopyCheck.Unusable -> null.also { rejectedCopy = path }
            // The stream's length isn't in yet: look again on a later read.
            PlaybackCacheBridge.CopyCheck.Unknown -> null
        }
    }

    /**
     * Closes the current source (a writing one keeps what it wrote and drops its lock) and carries on with [next]
     * from the same byte, reading [uri] instead when given. False when nothing was left to read.
     */
    private fun reopen(next: DataSource, uri: Uri?): Boolean {
        val spec = spec!!
        closeCurrent()
        if (uri != null) remoteKey = null
        val remaining = if (opened == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else opened - bytesRead
        if (remaining == 0L) return false
        val builder = spec.buildUpon().setPosition(spec.position + bytesRead).setLength(remaining)
        if (uri != null) builder.setUri(uri)
        current = next
        next.open(builder.build())
        return true
    }

    private fun closeCurrent() {
        try {
            current?.close()
        } finally {
            current = null
            claimedKey?.let(claims::releaseWrite)
            claimedKey = null
        }
    }

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders.orEmpty()

    override fun close() {
        try {
            closeCurrent()
        } finally {
            spec = null
            remoteKey = null
        }
    }
}
