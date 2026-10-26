package io.waves.download

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import io.waves.data.SqlLibraryRepository
import io.waves.data.db.WavesDatabase
import io.waves.model.Episode
import io.waves.model.EpisodeId
import io.waves.model.PlaybackProgress
import io.waves.player.AudioPlayer
import io.waves.player.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.util.Collections
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

class KtorDownloadManagerTest {
    private val driver: SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { WavesDatabase.Schema.create(it) }
    private val dir: File = Files.createTempDirectory("waves downloads").toFile() // space: exercises URI encoding
    private val scopes = mutableListOf<CoroutineScope>()
    private var now = 1_000L

    // An in-memory JdbcSqliteDriver shares one connection and one "current transaction" across threads, which
    // can drop change notifications under real concurrency. Serialize DB work on one lane (file-backed and
    // Android drivers keep transactions per thread, so production uses plain Dispatchers.IO).
    private val io = Dispatchers.IO.limitedParallelism(1)

    private val payload = Random(42).nextBytes(300_000)
    private val requests: MutableList<HttpRequestData> = Collections.synchronizedList(mutableListOf())
    private var handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { serve(it, payload) }

    private fun episode(guid: String = "g1", url: String = "https://cdn.example/ep-$guid.m4a?t=1") =
        Episode(guid = guid, podcastFeedUrl = "https://feed.example/rss", title = "Episode $guid", audioUrl = url)

    private fun newManager(
        driver: SqlDriver = this.driver,
        resumeOnStart: Boolean = true,
        locate: suspend (String) -> String = { it },
        rangeChunkBytes: Long = 8L * 1024 * 1024,
    ): KtorDownloadManager {
        val scope = CoroutineScope(SupervisorJob() + io).also { scopes += it }
        val client = HttpClient(MockEngine { req -> requests += req; handler(req) }) { downloadDefaults() }
        return KtorDownloadManager(
            driver, client, dir.absolutePath, scope, ioContext = io, clock = { now }, progressIntervalMs = 0,
            resumeOnStart = resumeOnStart, locate = locate, rangeChunkBytes = rangeChunkBytes,
        )
    }

    @AfterTest fun tearDown() {
        runBlocking { scopes.forEach { it.coroutineContext.job.cancelAndJoin() } }
        driver.close()
        dir.deleteRecursively()
    }

    private suspend fun DownloadManager.await(id: EpisodeId, what: (DownloadInfo?) -> Boolean): DownloadInfo? =
        withTimeout(10_000) { state(id).first(what) }

    private fun files() = dir.listFiles().orEmpty().map { it.name }.sorted()

    @Test fun fullDownloadRenamesIntoPlaceAndExposesLocalUri() = runBlocking<Unit> {
        val m = newManager()
        val ep = episode()
        m.enqueue(ep)
        val done = m.await(ep.id) { it?.status == DownloadStatus.Done }!!
        val file = File(done.filePath)
        assertContentEquals(payload, file.readBytes())
        assertEquals(payload.size.toLong(), done.bytesDownloaded)
        assertEquals(payload.size.toLong(), done.totalBytes)
        assertEquals(1f, done.progress)
        assertEquals(now, done.completedAtEpochMs)
        assertTrue(file.name.endsWith(".m4a"))
        assertEquals(listOf(file.name), files()) // no .part left behind
        val uri = assertNotNull(m.localUri(ep.id))
        assertTrue(uri.startsWith("file:///") && "%20" in uri, uri)
        assertEquals(file.canonicalFile, File(URI(uri)).canonicalFile)
        assertEquals("identity", requests.single().headers[HttpHeaders.AcceptEncoding])
        assertNull(requests.single().headers[HttpHeaders.Range])

        m.enqueue(ep) // already done: no new request
        delay(100)
        assertEquals(1, requests.size)
        assertEquals(listOf(ep.id), m.downloads.first().map { it.episodeId })
    }

    @Test fun locatedSourceDecidesTheFileExtension() = runBlocking<Unit> {
        val watch = "https://www.youtube.com/watch?v=firstVideo1"
        var source = "https://ts.example/audio/firstVideo1.mp3?key=k"
        val m = newManager(locate = { if (it == watch) source else it })
        val ep = episode(guid = "firstVideo1", url = watch)
        m.enqueue(ep)
        val done = m.await(ep.id) { it?.status == DownloadStatus.Done }!!
        assertTrue(done.filePath.endsWith(".mp3"), done.filePath)
        assertEquals(listOf(File(done.filePath).name), files())
        assertEquals(File(done.filePath).toURI().path, File(URI(m.localUri(ep.id)!!)).toURI().path)
        assertEquals("https://ts.example/audio/firstVideo1.mp3?key=k", requests.single().url.toString())

        // A direct stream has no extension in its path: keep the .m4a the watch URL implied.
        source = "https://rr.googlevideo.com/videoplayback?itag=140"
        val direct = episode(guid = "other", url = watch)
        m.enqueue(direct)
        assertTrue(m.await(direct.id) { it?.status == DownloadStatus.Done }!!.filePath.endsWith(".m4a"))
    }

    @Test fun resolvedStreamIsFetchedInBoundedRanges() = runBlocking<Unit> {
        // YouTube throttles open-ended requests for a stream but serves bounded ranges at full speed.
        val watch = "https://www.youtube.com/watch?v=firstVideo1"
        val m = newManager(
            locate = { if (it == watch) "https://rr.googlevideo.com/videoplayback?itag=140" else it },
            rangeChunkBytes = 100_000,
        )
        val ep = episode(guid = "firstVideo1", url = watch)
        m.enqueue(ep)
        val done = m.await(ep.id) { it?.status == DownloadStatus.Done }!!
        assertContentEquals(payload, File(done.filePath).readBytes())
        assertEquals(payload.size.toLong(), done.totalBytes)
        assertEquals(
            listOf("bytes=0-99999", "bytes=100000-199999", "bytes=200000-299999"),
            requests.map { it.headers[HttpHeaders.Range] },
        )
    }

    @Test fun followsRedirectsIncludingHttpsToHttp() = runBlocking<Unit> {
        handler = { req ->
            if (req.url.host == "track.example") {
                respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "http://cdn.example/real.mp3"))
            } else serve(req, payload)
        }
        val m = newManager()
        val ep = episode(url = "https://track.example/redirect/cdn.example/real.mp3")
        m.enqueue(ep)
        val done = m.await(ep.id) { it?.status == DownloadStatus.Done }!!
        assertContentEquals(payload, File(done.filePath).readBytes())
        assertEquals(listOf("track.example", "cdn.example"), requests.map { it.url.host })
    }

    @Test fun interruptedDownloadResumesWithRange() = runBlocking<Unit> {
        // First response promises the whole file but the connection drops after 1000 bytes.
        handler = {
            respond(
                ByteReadChannel(payload.copyOf(1000)), HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentLength, payload.size.toString()),
            )
        }
        val m = newManager()
        val ep = episode()
        m.enqueue(ep)
        val failed = m.await(ep.id) { it?.status == DownloadStatus.Failed }!!
        assertEquals(1000, failed.bytesDownloaded)
        assertTrue(failed.error!!.contains("1000"), failed.error)
        assertNull(m.localUri(ep.id))
        assertEquals(1000L, File(failed.filePath + PART_SUFFIX).length())

        handler = { serve(it, payload) }
        m.enqueue(ep) // retry resumes
        val done = m.await(ep.id) { it?.status == DownloadStatus.Done }!!
        assertEquals("bytes=1000-", requests.last().headers[HttpHeaders.Range])
        assertContentEquals(payload, File(done.filePath).readBytes())
        assertNull(done.error)
    }

    @Test fun serverIgnoringRangeRestartsFromZero() = runBlocking<Unit> {
        val m = newManager()
        val ep = episode()
        val target = File(dir, downloadFileName(ep))
        File(target.path + PART_SUFFIX).writeBytes(ByteArray(5000) { 7 }) // stale junk
        handler = { serve(it, payload, honorRange = false) }
        m.enqueue(ep)
        m.await(ep.id) { it?.status == DownloadStatus.Done }
        assertEquals("bytes=5000-", requests.single().headers[HttpHeaders.Range])
        assertContentEquals(payload, target.readBytes())
    }

    @Test fun completePartFileIsFinalizedOn416() = runBlocking<Unit> {
        val m = newManager()
        val ep = episode()
        val target = File(dir, downloadFileName(ep))
        File(target.path + PART_SUFFIX).writeBytes(payload)
        m.enqueue(ep)
        m.await(ep.id) { it?.status == DownloadStatus.Done }
        assertContentEquals(payload, target.readBytes())
    }

    @Test fun httpErrorMarksFailed() = runBlocking<Unit> {
        handler = { respond("nope", HttpStatusCode.NotFound) }
        val m = newManager()
        val ep = episode()
        m.enqueue(ep)
        val failed = m.await(ep.id) { it?.status == DownloadStatus.Failed }!!
        assertTrue(failed.error!!.contains("404"), failed.error)
        assertNull(m.localUri(ep.id))
        assertFalse(File(failed.filePath).exists())
    }

    @Test fun cancelStopsTransferAndDiscardsPartialData() = runBlocking<Unit> {
        val body = ByteChannel(autoFlush = true)
        handler = {
            body.writeFully(payload, 0, 1000)
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, payload.size.toString()))
        }
        val m = newManager()
        val ep = episode()
        m.enqueue(ep)
        m.await(ep.id) { it?.status == DownloadStatus.Downloading && it.bytesDownloaded >= 1000 }
        m.cancel(ep.id)
        assertNull(m.state(ep.id).first())
        assertEquals(emptyList(), files())
        body.close()
    }

    @Test fun cancelDoesNotRemoveCompletedDownloadButDeleteDoes() = runBlocking<Unit> {
        val m = newManager()
        val ep = episode()
        m.enqueue(ep)
        val done = m.await(ep.id) { it?.status == DownloadStatus.Done }!!
        m.cancel(ep.id)
        assertEquals(DownloadStatus.Done, m.state(ep.id).first()?.status)

        m.delete(ep.id)
        assertNull(m.state(ep.id).first())
        assertFalse(File(done.filePath).exists())
        assertNull(m.localUri(ep.id))
        assertEquals(emptyList(), files())
    }

    @Test fun atMostTwoConcurrentTransfers() = runBlocking<Unit> {
        val bodies = Collections.synchronizedList(mutableListOf<ByteChannel>())
        handler = {
            val c = ByteChannel(autoFlush = true).also { bodies += it }
            respond(c, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, payload.size.toString()))
        }
        val m = newManager()
        val eps = (1..3).map { episode("g$it") }
        eps.forEach { m.enqueue(it) }
        withTimeout(10_000) { m.downloads.first { list -> list.count { it.status == DownloadStatus.Downloading } == 2 } }
        delay(200)
        val statuses = m.downloads.first().map { it.status }.sorted()
        assertEquals(listOf(DownloadStatus.Queued, DownloadStatus.Downloading, DownloadStatus.Downloading), statuses)
        assertEquals(2, requests.size)

        handler = { serve(it, payload) }
        bodies.toList().forEach { it.writeFully(payload); it.close() }
        withTimeout(10_000) { m.downloads.first { list -> list.size == 3 && list.all { it.status == DownloadStatus.Done } } }
    }

    @Test fun restartRequeuesAndResumesInterruptedDownload() = runBlocking<Unit> {
        handler = {
            val c = ByteChannel(autoFlush = true)
            c.writeFully(payload, 0, 2000)
            respond(c, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, payload.size.toString()))
        }
        val first = newManager()
        val ep = episode()
        first.enqueue(ep)
        first.await(ep.id) { it?.status == DownloadStatus.Downloading && it.bytesDownloaded >= 2000 }
        scopes.single().coroutineContext.job.cancelAndJoin() // "process death"

        handler = { serve(it, payload) }
        val second = newManager()
        val done = second.await(ep.id) { it?.status == DownloadStatus.Done }!!
        assertEquals("bytes=2000-", requests.last().headers[HttpHeaders.Range])
        assertContentEquals(payload, File(done.filePath).readBytes())
    }

    @Test fun restartRequeuesRowsLeftDownloading() = runBlocking<Unit> {
        val ep = episode()
        WavesDatabase(driver).downloadQueries.insert(
            ep.podcastFeedUrl, ep.guid, ep.title, ep.audioUrl, DownloadStatus.Downloading.name, 0, null,
            File(dir, "x.m4a").absolutePath, null, now, now, null,
        )
        val m = newManager()
        val done = m.await(ep.id) { it?.status == DownloadStatus.Done }!!
        assertContentEquals(payload, File(done.filePath).readBytes())

        // Without resumeOnStart nothing is requested.
        WavesDatabase(driver).downloadQueries.insert(
            ep.podcastFeedUrl, "g2", "t", ep.audioUrl, DownloadStatus.Queued.name, 0, null,
            File(dir, "y.m4a").absolutePath, null, now, now, null,
        )
        newManager(resumeOnStart = false)
        delay(200)
        assertEquals(1, requests.size)
    }

    @Test fun autoDeleteHelpers() = runBlocking<Unit> {
        val m = newManager()
        val old = episode("old")
        m.enqueue(old)
        m.await(old.id) { it?.status == DownloadStatus.Done }
        now += 3.days.inWholeMilliseconds
        val played = episode("played")
        val kept = episode("kept")
        m.enqueue(played)
        m.enqueue(kept)
        m.await(played.id) { it?.status == DownloadStatus.Done }
        m.await(kept.id) { it?.status == DownloadStatus.Done }

        assertEquals(1, m.deleteCompleted(olderThan = 2.days))
        assertNull(m.state(old.id).first())

        val library = SqlLibraryRepository(driver, io) { now }
        library.saveProgress(PlaybackProgress(played.id, 10, 10, completed = true, updatedAtEpochMs = now))
        library.saveProgress(PlaybackProgress(kept.id, 1, 10, completed = false, updatedAtEpochMs = now))
        assertEquals(1, m.deleteDownloadsOfCompletedEpisodes(library))
        assertEquals(listOf(kept.id), m.downloads.first().map { it.episodeId })
        assertEquals(1, files().size)
    }

    @Test fun createsTableInDatabaseThatPredatesIt() = runBlocking<Unit> {
        val legacy = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY) // no schema at all
        try {
            val m = newManager(legacy)
            val ep = episode()
            m.enqueue(ep)
            m.await(ep.id) { it?.status == DownloadStatus.Done }
        } finally {
            scopes.forEach { it.coroutineContext.job.cancelAndJoin() }
            legacy.close()
        }
    }

    @Test fun downloadAwarePlayerPrefersLocalFile() = runBlocking<Unit> {
        val m = newManager()
        val fake = FakePlayer()
        val player = DownloadAwareAudioPlayer(fake, m)
        val ep = episode()
        player.play(ep, 0)
        assertEquals(ep.audioUrl, fake.played.last().audioUrl)
        m.enqueue(ep)
        m.await(ep.id) { it?.status == DownloadStatus.Done }
        player.play(ep, 5, "Podcast")
        assertEquals(m.localUri(ep.id), fake.played.last().audioUrl)
        assertEquals(ep.id, fake.played.last().id)
    }

    private fun cached(ep: Episode, ext: String? = null) =
        File(File(dir, ".cache"), downloadFileName(ep).let { if (ext == null) it else it.substringBefore('.') + ".$ext" })

    private fun stamp(ep: Episode) = File(File(dir, ".cache"), downloadFileName(ep).substringBefore('.') + ".accessed")

    private fun cacheFiles() = File(dir, ".cache").listFiles().orEmpty().map { it.name }.sorted()

    private suspend fun eventually(check: () -> Boolean) = withTimeout(10_000) { while (!check()) delay(10) }

    @Test fun cachedEpisodeIsPlayableButNotADownload() = runBlocking<Unit> {
        val m = newManager()
        val ep = episode()
        m.cache(ep)
        eventually { cached(ep).exists() }
        assertContentEquals(payload, cached(ep).readBytes())
        assertEquals(emptyList(), m.downloads.first())
        assertNull(m.state(ep.id).first())
        assertNull(m.localUri(ep.id))
        assertEquals(cached(ep).canonicalFile, File(URI(assertNotNull(m.playableUri(ep)))).canonicalFile)
        assertEquals(setOf(ep.id), m.cachedEpisodeIds.first())

        m.cache(ep) // already cached: no new request
        delay(100)
        assertEquals(1, requests.size)
    }

    @Test fun cacheIsEvictedWhenNotPlayedFor3Days() = runBlocking<Unit> {
        val m = newManager()
        val ep = episode()
        m.cache(ep)
        eventually { cached(ep).exists() && stamp(ep).readText() == now.toString() }

        now += 2.days.inWholeMilliseconds
        m.playableUri(ep) // a play counts as an access
        eventually { stamp(ep).readText() == now.toString() }
        now += 2.days.inWholeMilliseconds
        assertEquals(0, m.evictCache(3.days))
        assertTrue(cached(ep).exists())

        now += 2.days.inWholeMilliseconds
        assertEquals(1, m.evictCache(3.days))
        assertEquals(emptyList(), File(dir, ".cache").listFiles().orEmpty().map { it.name })
        assertEquals(emptySet(), m.cachedEpisodeIds.first())
        assertNull(m.playableUri(ep))
    }

    @Test fun manualDownloadTakesOverCompleteCacheWithoutRefetching() = runBlocking<Unit> {
        val m = newManager()
        val ep = episode()
        m.cache(ep)
        eventually { cached(ep).exists() }
        m.enqueue(ep)
        val done = m.await(ep.id) { it?.status == DownloadStatus.Done }!!
        assertEquals(1, requests.size)
        assertContentEquals(payload, File(done.filePath).readBytes())
        assertEquals(payload.size.toLong(), done.totalBytes)
        assertFalse(cached(ep).exists())
        assertNotNull(m.localUri(ep.id))
    }

    @Test fun manualDownloadResumesPartialCache() = runBlocking<Unit> {
        val body = ByteChannel(autoFlush = true)
        handler = {
            body.writeFully(payload, 0, 1000)
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, payload.size.toString()))
        }
        val m = newManager()
        val ep = episode()
        m.cache(ep)
        eventually { File(cached(ep).path + PART_SUFFIX).let { it.exists() && it.length() >= 1000 } }
        handler = { serve(it, payload) }
        m.enqueue(ep)
        val done = m.await(ep.id) { it?.status == DownloadStatus.Done }!!
        assertEquals("bytes=1000-", requests.last().headers[HttpHeaders.Range])
        assertContentEquals(payload, File(done.filePath).readBytes())
        assertFalse(File(cached(ep).path + PART_SUFFIX).exists())
        body.close()
    }

    @Test fun youtubeEpisodeIsCachedAsTheResolvedStreamAndAdoptedByDownload() = runBlocking<Unit> {
        val watch = "https://www.youtube.com/watch?v=firstVideo1"
        val source = "https://ts.example/audio/firstVideo1.mp3?key=k"
        val m = newManager(locate = { if (it == watch) source else it })
        val ep = episode(guid = "firstVideo1", url = watch)
        m.cache(ep)
        eventually { cached(ep, "mp3").exists() }
        assertContentEquals(payload, cached(ep, "mp3").readBytes())
        assertEquals(source, requests.single().url.toString())
        assertEquals(emptyList(), m.downloads.first())
        assertEquals(cached(ep, "mp3").canonicalFile, File(URI(assertNotNull(m.playableUri(ep)))).canonicalFile)

        m.enqueue(ep)
        val done = m.await(ep.id) { it?.status == DownloadStatus.Done }!!
        assertEquals(1, requests.size)
        assertTrue(done.filePath.endsWith(".mp3"), done.filePath)
        assertContentEquals(payload, File(done.filePath).readBytes())
        assertEquals(emptyList(), cacheFiles())
    }

    @Test fun youtubeCachingFailureDiscardsThePartialStream() = runBlocking<Unit> {
        val watch = "https://www.youtube.com/watch?v=firstVideo1"
        val m = newManager(locate = { if (it == watch) "https://rr.googlevideo.com/videoplayback?itag=140" else it })
        val ep = episode(guid = "firstVideo1", url = watch)
        handler = {
            respond(
                ByteReadChannel(payload.copyOf(1000)), HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentLength, payload.size.toString()),
            )
        }
        m.cache(ep)
        eventually { requests.isNotEmpty() && cacheFiles().none { it.endsWith(".part") } }
        delay(100)
        assertTrue(cacheFiles().none { it.endsWith(".part") }, cacheFiles().toString())

        handler = { serve(it, payload) }
        m.cache(ep) // starts over: an expired stream's bytes can't be resumed
        eventually { cached(ep, "m4a").exists() }
        assertTrue(requests.last().headers[HttpHeaders.Range]!!.startsWith("bytes=0-"))
        assertContentEquals(payload, cached(ep, "m4a").readBytes())
    }

    @Test fun helpers() {
        assertEquals(ContentRange(100, 1000), parseContentRange("bytes 100-199/1000"))
        assertEquals(ContentRange(null, 1000), parseContentRange("bytes */1000"))
        assertEquals(ContentRange(5, null), parseContentRange("bytes 5-9/*"))
        assertNull(parseContentRange(null))
        assertEquals("mp3", audioExtension("https://x/feed?id=1"))
        assertEquals("m4a", audioExtension("https://x/a.b/ep.M4A?x=y.mp3"))
        assertEquals("m4a", audioExtension("https://www.youtube.com/watch?v=firstVideo1"))
        assertEquals("file:///a%20b/%C3%A9.mp3", fileUri("/a b/é.mp3"))
        assertEquals(16, fnv1a64("x").length)
    }

    private class FakePlayer : AudioPlayer {
        val played = mutableListOf<Episode>()
        override val state: StateFlow<PlaybackState> = MutableStateFlow(PlaybackState())
        override fun play(episode: Episode, startPositionMs: Long) { played += episode }
        override fun pause() {}
        override fun resume() {}
        override fun seekTo(positionMs: Long) {}
        override fun release() {}
    }
}

/** Minimal Range-capable file server. */
private fun MockRequestHandleScope.serve(req: HttpRequestData, payload: ByteArray, honorRange: Boolean = true): HttpResponseData {
    val range = req.headers[HttpHeaders.Range]
    if (range != null && honorRange) {
        val start = range.removePrefix("bytes=").substringBefore('-').toInt()
        if (start >= payload.size) {
            return respond(ByteArray(0), HttpStatusCode.RequestedRangeNotSatisfiable, headersOf(HttpHeaders.ContentRange, "bytes */${payload.size}"))
        }
        val end = range.substringAfter('-').toLongOrNull()?.coerceAtMost(payload.size - 1L)?.toInt() ?: (payload.size - 1)
        val body = payload.copyOfRange(start, end + 1)
        return respond(
            body, HttpStatusCode.PartialContent,
            headersOf(
                HttpHeaders.ContentRange to listOf("bytes $start-$end/${payload.size}"),
                HttpHeaders.ContentLength to listOf(body.size.toString()),
            ),
        )
    }
    return respond(payload, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, payload.size.toString()))
}
