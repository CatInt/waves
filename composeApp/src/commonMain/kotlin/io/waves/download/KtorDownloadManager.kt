package io.waves.download

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import app.cash.sqldelight.db.SqlDriver
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import io.waves.currentTimeMillis
import io.waves.data.db.WavesDatabase
import io.waves.model.Episode
import io.waves.model.EpisodeId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.io.Sink
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import io.waves.data.db.Download as DownloadRow

/**
 * Ktor + SQLDelight [DownloadManager]: streams each episode to `<file>.part`, then renames it atomically.
 * Interrupted transfers resume with an HTTP `Range` request (falling back to a full download when the server
 * ignores it). At most [maxConcurrent] transfers run at once; the rest wait as [DownloadStatus.Queued].
 *
 * @param driver shared app database driver (the one behind `SqlLibraryRepository`); the `download` table is
 *   created on first use if the database predates it.
 * @param directory absolute directory for downloaded files.
 * @param scope long-lived scope for in-process downloads and the restart re-queue.
 * @param scheduler where transfers run; null = launch them in [scope]. Either way the transfer is [runDownload].
 * @param resumeOnStart re-queue downloads left Queued/Downloading by a previous process.
 */
class KtorDownloadManager(
    driver: SqlDriver,
    private val client: HttpClient,
    private val directory: String,
    private val scope: CoroutineScope,
    private val scheduler: DownloadScheduler? = null,
    private val ioContext: CoroutineContext = Dispatchers.IO,
    private val clock: () -> Long = ::currentTimeMillis,
    private val maxConcurrent: Int = 2,
    private val progressIntervalMs: Long = 500,
    resumeOnStart: Boolean = true,
    /** Ordinary enclosures pass through. YouTube watch URLs become a direct audio stream. */
    private val locate: suspend (String) -> String = { it },
    /** Range size for resolved YouTube streams, which are throttled when requested open-ended. */
    private val rangeChunkBytes: Long = 8L * 1024 * 1024,
    /**
     * Copies an episode the player has wholly in its own streaming cache to the given path, returning its size, or
     * null when the player doesn't have it all. Lets a download take over that copy instead of fetching it again.
     */
    private val copyFromPlayerCache: suspend (Episode, String) -> Long? = { _, _ -> null },
    /**
     * Fills the player's own streaming cache with the rest of an ordinary enclosure, for [cache] to keep one copy
     * rather than fetch a second; false when that cache can't be used, and a file is cached as before. YouTube streams
     * are always cached as files: playing them from the player's cache needs the network to resolve them again.
     */
    private val fillPlayerCache: (suspend (Episode) -> Boolean)? = null,
) : DownloadManager {

    private val db = WavesDatabase(driver)
    private val queries by lazy {
        driver.execute(null, CREATE_DOWNLOAD_TABLE, 0)
        db.downloadQueries
    }
    private val fs = SystemFileSystem
    private val permits = Semaphore(maxConcurrent)
    private val activeLock = Mutex()
    private val active = mutableMapOf<EpisodeId, Job>()

    /** Completed downloads (id -> path) for the synchronous [localUri]; kept in step with this manager's writes. */
    private val doneCache by lazy {
        MutableStateFlow(
            queries.byStatus(listOf(DownloadStatus.Done.name)).executeAsList()
                .associate { EpisodeId(it.feedUrl, it.guid) to it.filePath },
        )
    }

    init {
        scope.launch(ioContext) {
            doneCache.value // warm up off the caller's thread
            if (resumeOnStart) resumeInterrupted()
        }
    }

    override val downloads: Flow<List<DownloadInfo>> =
        flow { emitAll(queries.all().asFlow().mapToList(ioContext)) }.flowOn(ioContext)
            .map { rows -> rows.map { it.toInfo() } }

    override fun state(episodeId: EpisodeId): Flow<DownloadInfo?> =
        flow { emitAll(queries.byId(episodeId.feedUrl, episodeId.guid).asFlow().mapToOneOrNull(ioContext)) }
            .flowOn(ioContext)
            .map { it?.toInfo() }

    override suspend fun enqueue(episode: Episode) {
        val id = episode.id
        val current = withContext(ioContext) { queries.byId(id.feedUrl, id.guid).executeAsOneOrNull() }
        val path = current?.filePath ?: "${directory.trimEnd('/')}/${downloadFileName(episode)}"
        // A fresh download takes over the playback cache's copy: complete files become the download outright,
        // partial ones are resumed from where caching stopped.
        val adopted = if (current.needsTransfer()) adoptCached(episode, path) ?: adoptPlayerCache(episode, path) else null
        val schedule = withContext(ioContext) {
            queries.transactionWithResult {
                val row = queries.byId(id.feedUrl, id.guid).executeAsOneOrNull()
                val status = row?.let { DownloadStatus.valueOf(it.status) }
                when {
                    row.needsTransfer() && adopted != null && fs.exists(Path(adopted.first)) -> {
                        val (file, size) = adopted
                        val now = clock()
                        queries.insert(
                            id.feedUrl, id.guid, episode.title, episode.audioUrl, DownloadStatus.Done.name,
                            size, size, file, null, now, now, now,
                        )
                        doneCache.update { it + (id to file) }
                        false
                    }
                    row.needsTransfer() -> {
                        val now = clock()
                        queries.insert(
                            id.feedUrl, id.guid, episode.title, episode.audioUrl, DownloadStatus.Queued.name,
                            0, null, row?.filePath ?: path, null, now, now, null,
                        )
                        true
                    }
                    status == DownloadStatus.Done -> false
                    status == DownloadStatus.Failed -> {
                        queries.setStatus(DownloadStatus.Queued.name, null, clock(), id.feedUrl, id.guid)
                        true
                    }
                    else -> true // Queued / Downloading: make sure it is scheduled (idempotent)
                }
            }
        }
        if (schedule) dispatch(id)
    }

    override suspend fun cancel(episodeId: EpisodeId) {
        val row = withContext(ioContext) { queries.byId(episodeId.feedUrl, episodeId.guid).executeAsOneOrNull() }
        if (row == null || row.status == DownloadStatus.Done.name) return
        remove(row)
    }

    override suspend fun delete(episodeId: EpisodeId) {
        val row = withContext(ioContext) { queries.byId(episodeId.feedUrl, episodeId.guid).executeAsOneOrNull() }
        if (row != null) remove(row)
        forgetCached(episodeId)
    }

    override fun localUri(episodeId: EpisodeId): String? {
        val path = doneCache.value[episodeId] ?: return null
        return if (fs.exists(Path(path))) fileUri(path) else null
    }

    override suspend fun deleteCompleted(olderThan: Duration): Int {
        val rows = withContext(ioContext) {
            queries.completedBefore(clock() - olderThan.inWholeMilliseconds).executeAsList()
        }
        rows.forEach { remove(it) }
        return rows.size
    }

    override suspend fun deleteDownloads(episodeIds: Collection<EpisodeId>): Int {
        var n = 0
        for (id in episodeIds.toSet()) {
            val row = withContext(ioContext) { queries.byId(id.feedUrl, id.guid).executeAsOneOrNull() } ?: continue
            remove(row)
            n++
        }
        return n
    }

    /** No download row yet, or a completed one whose file has gone missing. */
    private fun DownloadRow?.needsTransfer(): Boolean =
        this == null || (status == DownloadStatus.Done.name && !fs.exists(Path(filePath)))

    // --- Playback cache: `<directory>/.cache/<key>.<ext>` plus a `<key>.accessed` stamp (epoch ms of the last play
    // or of caching), without database rows, so cached episodes never show up as downloads. The extension is the
    // one of the stream actually fetched (a YouTube episode may resolve to M4A or another audio format).

    private val cacheDirectory = "${directory.trimEnd('/')}/$CACHE_DIR"
    private val cacheIndexPath = Path("${directory.trimEnd('/')}/.cache-index")
    private val cacheIndex = LinkedHashMap<String, EpisodeId>()
    private val cachedIdsState = MutableStateFlow<Set<EpisodeId>>(emptySet())
    override val cachedEpisodeIds: Flow<Set<EpisodeId>> = cachedIdsState.asStateFlow()
    private val caching = mutableMapOf<EpisodeId, Job>() // guarded by activeLock
    private val cacheIndexLock = Mutex()

    init { loadCacheIndex() }

    private fun cacheKey(episode: Episode) = downloadFileName(episode).substringBefore('.')

    private fun stampPath(key: String) = "$cacheDirectory/$key$ACCESS_SUFFIX"

    /** Cache files of [key]: complete or partial audio and the stamp. */
    private fun cacheFiles(key: String): List<Path> {
        val dir = Path(cacheDirectory)
        if (!fs.exists(dir)) return emptyList()
        return fs.list(dir).filter { it.name.substringBefore('.') == key }
    }

    private fun Path.isAudio() = !name.endsWith(PART_SUFFIX) && !name.endsWith(ACCESS_SUFFIX)

    /** The complete cached file of [episode], if any. */
    private fun cachedFile(episode: Episode): Path? = cacheFiles(cacheKey(episode)).firstOrNull { it.isAudio() }

    override suspend fun cache(episode: Episode) {
        if (episode.audioUrl.startsWith("file:")) return
        val id = episode.id
        if (localUri(id) != null) return
        val skip = withContext(ioContext) {
            queries.byId(id.feedUrl, id.guid).executeAsOneOrNull() != null || cachedFile(episode) != null
        }
        if (skip) return
        activeLock.withLock {
            if (id in caching) return
            caching[id] = scope.launch(ioContext, start = CoroutineStart.LAZY) {
                val key = cacheKey(episode)
                var path: String? = null
                var resolved = false
                try {
                    val source = locate(episode.audioUrl)
                    resolved = source != episode.audioUrl
                    if (!resolved && fillPlayerCache != null && permits.withPermit { fillPlayerCache.invoke(episode) }) {
                        return@launch
                    }
                    val ext = explicitAudioExtension(source)?.takeIf { resolved } ?: audioExtension(episode.audioUrl)
                    path = "$cacheDirectory/$key.$ext"
                    stamp(key)
                    // A resolved stream URL expires and changes between attempts; its bytes can't be resumed.
                    permits.withPermit { transfer(source, path, discardPartial = resolved) { _, _ -> } }
                    stamp(key)
                    rememberCached(key, episode)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    // A plain enclosure keeps its partial file for the next cache() or a manual download to resume.
                    if (resolved) path?.let { fs.delete(Path(it + PART_SUFFIX), mustExist = false) }
                } finally {
                    withContext(NonCancellable) { activeLock.withLock { caching.remove(id) } }
                }
            }.also { it.start() }
        }
    }

    override suspend fun evictCache(notAccessedFor: Duration): Int {
        val busy = activeLock.withLock { caching.isNotEmpty() }
        return withContext(ioContext) {
            cacheIndexLock.withLock {
                val dir = Path(cacheDirectory)
                if (!fs.exists(dir)) return@withLock 0
                val cutoff = clock() - notAccessedFor.inWholeMilliseconds
                var evicted = 0
                val forgotten = HashSet<EpisodeId>()
                for ((key, files) in fs.list(dir).groupBy { it.name.substringBefore('.') }) {
                    if (busy && files.any { it.name.endsWith(PART_SUFFIX) }) continue // may be the transfer in progress
                    if (files.any { !it.name.endsWith(ACCESS_SUFFIX) } && lastAccess(Path(stampPath(key))) >= cutoff) continue
                    val hadAudio = files.any { it.isAudio() }
                    files.forEach { fs.delete(it, mustExist = false) }
                    if (hadAudio) {
                        evicted++
                        cacheIndex.remove(key)?.let { forgotten += it }
                    }
                }
                if (forgotten.isNotEmpty()) {
                    writeCacheIndex()
                    cachedIdsState.update { it - forgotten }
                }
                evicted
            }
        }
    }

    override fun playableUri(episode: Episode): String? {
        localUri(episode.id)?.let { return it }
        val file = cachedFile(episode) ?: return null
        scope.launch(ioContext) { stamp(cacheKey(episode)) }
        return fileUri(file.toString())
    }

    /**
     * Moves the cached copy of [episode] next to the download path [dest], stopping caching in progress first.
     * Returns the (path, size) of a complete file, which keeps its own extension; null when there was none. A
     * partial plain enclosure becomes `dest.part` so the download resumes it.
     */
    private suspend fun adoptCached(episode: Episode, dest: String): Pair<String, Long>? {
        activeLock.withLock { caching[episode.id] }?.cancelAndJoin()
        return withContext(ioContext) {
            val files = cacheFiles(cacheKey(episode))
            val adopted = runCatching {
                Path(dest).parent?.let { fs.createDirectories(it) }
                val complete = files.firstOrNull { it.isAudio() }
                val partial = files.firstOrNull { it.name.endsWith(PART_SUFFIX) }
                when {
                    complete != null -> {
                        val target = dest.substringBeforeLast('.') + "." + complete.name.substringAfter('.')
                        fs.delete(Path(dest + PART_SUFFIX), mustExist = false)
                        fs.atomicMove(complete, Path(target))
                        fs.metadataOrNull(Path(target))?.size?.let { target to it }
                    }
                    partial != null && Path(dest + PART_SUFFIX).name.substringAfter('.') == partial.name.substringAfter('.') &&
                        !fs.exists(Path(dest + PART_SUFFIX)) -> {
                        fs.atomicMove(partial, Path(dest + PART_SUFFIX))
                        null
                    }
                    else -> null
                }
            }.getOrNull().also {
                cacheFiles(cacheKey(episode)).forEach { file -> fs.delete(file, mustExist = false) }
            }
            forgetKey(cacheKey(episode))
            adopted
        }
    }

    /** The player's streamed copy of [episode], copied to [dest] when it has the whole file. */
    private suspend fun adoptPlayerCache(episode: Episode, dest: String): Pair<String, Long>? = withContext(ioContext) {
        try {
            Path(dest).parent?.let { fs.createDirectories(it) }
            copyFromPlayerCache(episode, dest)?.let { dest to it }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null // fetch it after all
        }
    }

    /** Restores [cachedEpisodeIds] from the index, dropping entries whose audio file is gone. */
    private fun loadCacheIndex() {
        val text = runCatching { fs.source(cacheIndexPath).buffered().use { it.readString() } }.getOrNull() ?: return
        val ids = HashSet<EpisodeId>()
        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            val parts = line.split('\u001f')
            if (parts.size != 3) continue
            val id = EpisodeId(parts[1], parts[2])
            if (cacheFiles(parts[0]).none { it.isAudio() }) continue
            cacheIndex[parts[0]] = id
            ids += id
        }
        cachedIdsState.value = ids
        if (cacheIndex.size != text.lineSequence().count { it.isNotBlank() }) writeCacheIndex()
    }

    private fun writeCacheIndex() {
        fs.sink(cacheIndexPath).buffered().use { sink ->
            for ((key, id) in cacheIndex) sink.writeString("$key\u001f${id.feedUrl}\u001f${id.guid}\n")
        }
    }

    private suspend fun rememberCached(key: String, episode: Episode) = cacheIndexLock.withLock {
        cacheIndex[key] = episode.id
        writeCacheIndex()
        cachedIdsState.update { it + episode.id }
    }

    /** Drops a complete cache file and its index row. Used by manual delete. */
    private suspend fun forgetCached(episodeId: EpisodeId) = withContext(ioContext) {
        cacheIndexLock.withLock {
            val key = fnv1a64(episodeId.toString())
            cacheFiles(key).forEach { fs.delete(it, mustExist = false) }
            val id = cacheIndex.remove(key) ?: return@withLock
            writeCacheIndex()
            cachedIdsState.update { it - id }
        }
    }

    private suspend fun forgetKey(key: String) = cacheIndexLock.withLock {
        val id = cacheIndex.remove(key) ?: return@withLock
        writeCacheIndex()
        cachedIdsState.update { it - id }
    }

    private fun stamp(key: String) {
        fs.createDirectories(Path(cacheDirectory))
        fs.sink(Path(stampPath(key))).buffered().use { it.writeString(clock().toString()) }
    }

    private fun lastAccess(stamp: Path): Long =
        runCatching { fs.source(stamp).buffered().use { it.readString().trim().toLong() } }.getOrDefault(0L)

    /** Re-queues downloads a previous process left Queued/Downloading. Called on construction when `resumeOnStart`. */
    suspend fun resumeInterrupted() {
        val running = activeLock.withLock { active.keys.toSet() }
        val ids = withContext(ioContext) {
            queries.transactionWithResult {
                queries.byStatus(listOf(DownloadStatus.Queued.name, DownloadStatus.Downloading.name)).executeAsList()
                    .map { row ->
                        val id = EpisodeId(row.feedUrl, row.guid)
                        if (row.status == DownloadStatus.Downloading.name && id !in running) {
                            queries.setStatus(DownloadStatus.Queued.name, null, clock(), row.feedUrl, row.guid)
                        }
                        id
                    }
            }
        }
        ids.forEach { dispatch(it) }
    }

    /**
     * Performs the transfer of [id] (waiting for one of the [maxConcurrent] slots) and returns its final status,
     * or null when there was nothing to do: unknown id, already running elsewhere, or cancelled via [cancel]/[delete].
     * Schedulers call this; it is safe to call repeatedly.
     */
    suspend fun runDownload(id: EpisodeId): DownloadStatus? = try {
        withContext(ioContext) {
            val job = coroutineContext.job
            val claimed = activeLock.withLock { if (id in active) false else true.also { active[id] = job } }
            if (!claimed) return@withContext null
            try {
                permits.withPermit { transferIfPending(id) }
            } catch (e: CancellationException) {
                // Interrupted (shutdown, WorkManager stop): leave it Queued so it resumes. cancel()/delete()
                // remove the row first, making this a no-op.
                queries.setStatusIf(id, from = DownloadStatus.Downloading, to = DownloadStatus.Queued)
                throw e
            } finally {
                withContext(NonCancellable) { activeLock.withLock { if (active[id] === job) active.remove(id) } }
            }
        }
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive() // the caller itself was cancelled: propagate
        null // only the transfer was cancelled (cancel()/delete())
    }

    private fun dispatch(id: EpisodeId) {
        if (scheduler != null) scheduler.schedule(id) else scope.launch { runDownload(id) }
    }

    private suspend fun remove(row: DownloadRow) {
        val id = EpisodeId(row.feedUrl, row.guid)
        withContext(ioContext) { queries.remove(row.feedUrl, row.guid) }
        scheduler?.unschedule(id)
        activeLock.withLock { active[id] }?.cancelAndJoin()
        doneCache.update { it - id }
        withContext(ioContext) {
            fs.delete(Path(row.filePath + PART_SUFFIX), mustExist = false)
            fs.delete(Path(row.filePath), mustExist = false)
        }
    }

    private suspend fun transferIfPending(id: EpisodeId): DownloadStatus? {
        val row = queries.byId(id.feedUrl, id.guid).executeAsOneOrNull() ?: return null
        if (row.status == DownloadStatus.Done.name) return DownloadStatus.Done
        queries.setStatus(DownloadStatus.Downloading.name, null, clock(), id.feedUrl, id.guid)
        return try {
            val source = locate(row.audioUrl)
            val path = retarget(row, source)
            val size = transfer(source, path, discardPartial = source != row.audioUrl) { downloaded, total ->
                queries.setProgress(downloaded, total, clock(), id.feedUrl, id.guid)
            }
            queries.markDone(size, clock(), id.feedUrl, id.guid)
            doneCache.update { it + (id to path) }
            DownloadStatus.Done
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val message = e.message?.takeIf { it.isNotBlank() } ?: (e::class.simpleName ?: "Download failed")
            queries.setStatus(DownloadStatus.Failed.name, message, clock(), id.feedUrl, id.guid)
            DownloadStatus.Failed
        }
    }

    /**
     * The row's file path, switched to the extension [source] actually has when it differs (players pick demuxers
     * by extension, and a resolved stream may be MP3 where the episode URL suggested M4A).
     */
    private fun retarget(row: DownloadRow, source: String): String {
        val ext = explicitAudioExtension(source) ?: return row.filePath
        val current = row.filePath.substringAfterLast('/').substringAfterLast('.', "")
        if (current.equals(ext, ignoreCase = true)) return row.filePath
        val path = row.filePath.substringBeforeLast('.') + "." + ext
        fs.delete(Path(row.filePath + PART_SUFFIX), mustExist = false)
        queries.setFilePath(path, row.feedUrl, row.guid)
        return path
    }

    /**
     * Downloads [source] to `<path>.part` (resuming when possible), renames it into place; returns the file size.
     * [onProgress] gets (bytes so far, total or null) at most every [progressIntervalMs].
     */
    private suspend fun transfer(
        source: String,
        path: String,
        discardPartial: Boolean,
        onProgress: (Long, Long?) -> Unit,
    ): Long {
        val dest = Path(path)
        val part = Path(path + PART_SUFFIX)
        dest.parent?.let { fs.createDirectories(it) }
        // A resolved YouTube stream URL expires and changes between attempts; don't resume bytes from the last one.
        if (discardPartial) {
            fs.delete(part, mustExist = false)
            return transferInChunks(source, dest, part, onProgress)
        }
        repeat(MAX_ATTEMPTS) {
            val existing = fs.metadataOrNull(part)?.size?.takeIf { it > 0 } ?: 0L
            val restart = client.prepareGet(source) {
                // Byte offsets must refer to the stored representation (Android's HttpURLConnection would gzip).
                header(HttpHeaders.AcceptEncoding, "identity")
                if (existing > 0) header(HttpHeaders.Range, "bytes=$existing-")
            }.execute { response -> receive(response, part, existing, onProgress) }
            if (!restart) {
                fs.delete(dest, mustExist = false)
                fs.atomicMove(part, dest)
                return fs.metadataOrNull(dest)?.size ?: 0L
            }
        }
        throw DownloadException("Server rejected the resume request")
    }

    /** Writes the body to [part]; returns true when the partial file was discarded and the request must restart. */
    private suspend fun receive(
        response: HttpResponse,
        part: Path,
        existing: Long,
        onProgress: (Long, Long?) -> Unit,
    ): Boolean {
        val status = response.status
        val length = response.contentLength()
        val append: Boolean
        val total: Long?
        when {
            existing > 0 && status == HttpStatusCode.PartialContent -> {
                val range = parseContentRange(response.headers[HttpHeaders.ContentRange])
                if (range?.start != null && range.start != existing) {
                    fs.delete(part, mustExist = false)
                    return true
                }
                append = true
                total = range?.total ?: length?.let { existing + it }
            }
            existing > 0 && status == HttpStatusCode.RequestedRangeNotSatisfiable -> {
                // The part may already hold the whole file; otherwise start over.
                if (parseContentRange(response.headers[HttpHeaders.ContentRange])?.total == existing) return false
                fs.delete(part, mustExist = false)
                return true
            }
            status.isSuccess() -> { // 200 (Range ignored or not sent): whole body from byte 0
                append = false
                total = length
            }
            else -> throw DownloadException("HTTP ${status.value} ${status.description}".trim())
        }

        val start = if (append) existing else 0L
        onProgress(start, total)
        val progress = Progress(total, onProgress)
        val downloaded = fs.sink(part, append).buffered().use { sink -> start + copyBody(response, sink, start, progress) }
        onProgress(downloaded, total)
        if (total != null && downloaded != total) {
            throw DownloadException("Connection closed after $downloaded of $total bytes")
        }
        return false
    }

    /**
     * Downloads [source] in bounded ranges of [rangeChunkBytes] into [part], then renames it to [dest]. YouTube
     * throttles open-ended requests for a stream to a crawl but serves bounded ranges at full speed.
     */
    private suspend fun transferInChunks(source: String, dest: Path, part: Path, onProgress: (Long, Long?) -> Unit): Long {
        var total: Long? = null
        var downloaded = 0L
        fs.sink(part).buffered().use { sink ->
            while (total == null || downloaded < total!!) {
                val from = downloaded
                client.prepareGet(source) {
                    header(HttpHeaders.AcceptEncoding, "identity")
                    header(HttpHeaders.Range, "bytes=$from-${from + rangeChunkBytes - 1}")
                }.execute { response ->
                    val expected: Long? = when (response.status) {
                        HttpStatusCode.PartialContent -> {
                            val range = parseContentRange(response.headers[HttpHeaders.ContentRange])
                            if (range?.start != from) throw DownloadException("The server sent the wrong part of the file")
                            total = range.total ?: throw DownloadException("The server didn't say how large the file is")
                            response.contentLength()
                        }
                        HttpStatusCode.OK -> { // Range ignored: the whole file in one body
                            if (from != 0L) throw DownloadException("The server ignored the range request")
                            response.contentLength().also { total = it }
                        }
                        else -> throw DownloadException("HTTP ${response.status.value} ${response.status.description}".trim())
                    }
                    val n = copyBody(response, sink, from, Progress(total, onProgress))
                    if (expected != null && n != expected) {
                        throw DownloadException("Connection closed after ${from + n} of $total bytes")
                    }
                    downloaded = from + n
                    if (response.status == HttpStatusCode.OK) total = downloaded
                    if (n == 0L && downloaded < (total ?: 0L)) throw DownloadException("The server sent an empty part")
                }
            }
        }
        onProgress(downloaded, total)
        fs.delete(dest, mustExist = false)
        fs.atomicMove(part, dest)
        return fs.metadataOrNull(dest)?.size ?: 0L
    }

    /** Throttles [onProgress] to one call per [progressIntervalMs]. */
    private inner class Progress(val total: Long?, val onProgress: (Long, Long?) -> Unit) {
        private var lastReport = clock()

        fun report(downloaded: Long, sink: Sink) {
            val now = clock()
            if (now - lastReport < progressIntervalMs) return
            sink.flush()
            onProgress(downloaded, total)
            lastReport = now
        }
    }

    /** Appends [response]'s body to [sink]; [offset] is the file position it starts at. Returns the bytes written. */
    private suspend fun copyBody(response: HttpResponse, sink: Sink, offset: Long, progress: Progress): Long {
        val channel = response.bodyAsChannel()
        val buffer = ByteArray(BUFFER_SIZE)
        var written = 0L
        while (true) {
            val n = channel.readAvailable(buffer, 0, buffer.size)
            if (n < 0) break
            if (n == 0) {
                if (channel.isClosedForRead) break else continue
            }
            sink.write(buffer, 0, n)
            written += n
            progress.report(offset + written, sink)
        }
        return written
    }

    private fun io.waves.data.db.DownloadQueries.setStatusIf(id: EpisodeId, from: DownloadStatus, to: DownloadStatus) {
        transaction {
            if (byId(id.feedUrl, id.guid).executeAsOneOrNull()?.status == from.name) {
                setStatus(to.name, null, clock(), id.feedUrl, id.guid)
            }
        }
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
        const val MAX_ATTEMPTS = 2
        const val CACHE_DIR = ".cache"
        const val ACCESS_SUFFIX = ".accessed"
    }
}

internal fun DownloadRow.toInfo() = DownloadInfo(
    episodeId = EpisodeId(feedUrl, guid),
    status = DownloadStatus.valueOf(status),
    bytesDownloaded = bytesDownloaded,
    totalBytes = totalBytes,
    filePath = filePath,
    error = error,
    episodeTitle = title,
    audioUrl = audioUrl,
    createdAtEpochMs = createdAt,
    completedAtEpochMs = completedAt,
)
