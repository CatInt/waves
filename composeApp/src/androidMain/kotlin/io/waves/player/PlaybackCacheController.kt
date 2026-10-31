package io.waves.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File
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

    /**
     * Fetches whatever of [episode] the cache doesn't hold yet into it, under the key the player uses, so the cache
     * ends up with the whole file and nothing is fetched twice; then reports it to [catalog]. False when the cache
     * isn't open. Stops when the calling coroutine is cancelled.
     */
    @OptIn(UnstableApi::class)
    suspend fun fill(episode: Episode): Boolean {
        val cache = cacheRef.get() ?: return false
        val upstream = upstreamRef.get() ?: return false
        val key = episode.id.toString()
        val writer = CacheWriter(
            CacheDataSource(cache, upstream.createDataSource()),
            DataSpec.Builder().setUri(Uri.parse(episode.audioUrl)).setKey(key).build(),
            null, null,
        )
        withContext(Dispatchers.IO) {
            val stop = coroutineContext.job.invokeOnCompletion { writer.cancel() }
            try {
                writer.cache()
            } catch (e: java.io.InterruptedIOException) {
                ensureActive()
                throw e
            } finally {
                stop.dispose()
            }
        }
        val length = cache.getContentMetadata(key).get(ContentMetadata.KEY_CONTENT_LENGTH, -1L).takeIf { it > 0L }
        val bytes = cache.getCachedSpans(key).sumOf { it.length }
        catalog?.upsert(PlaybackCacheEntry(episode.id, episode.audioUrl, bytes, length, System.currentTimeMillis()))
        return true
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
        val upstream = DefaultDataSource.Factory(appContext, httpFactory)
        PlaybackCacheBridge.attach(cache, upstream)
        val caching = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        mediaSourceFactory = DefaultMediaSourceFactory(RemoteCachingDataSource.Factory(caching, upstream))
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
 */
@OptIn(UnstableApi::class)
private class RemoteCachingDataSource(
    private val cached: DataSource,
    private val direct: DataSource,
) : DataSource {
    private var current: DataSource? = null

    class Factory(
        private val cached: DataSource.Factory,
        private val direct: DataSource.Factory,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = RemoteCachingDataSource(cached.createDataSource(), direct.createDataSource())
    }

    override fun addTransferListener(transferListener: TransferListener) {
        cached.addTransferListener(transferListener)
        direct.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val scheme = dataSpec.uri.scheme
        val chosen = if (scheme == "http" || scheme == "https") cached else direct
        current = chosen
        return chosen.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = current!!.read(buffer, offset, length)

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders.orEmpty()

    override fun close() {
        current?.close()
        current = null
    }
}
