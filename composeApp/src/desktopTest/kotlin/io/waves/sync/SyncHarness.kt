package io.waves.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.waves.data.SqlLibraryRepository
import io.waves.data.createDesktopDriver
import io.waves.model.EpisodeId
import io.waves.model.HistoryEntry
import io.waves.model.PlaybackProgress
import io.waves.model.Podcast
import io.waves.wavesDefaults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import java.io.File
import java.nio.file.Files

/** Shared fake wall clock; devices may add a skew. */
class TestClock(var now: Long = 1_000_000L) {
    fun advance(ms: Long = 1_000) { now += ms }
}

/**
 * One simulated install: its own SQLite file and device id, the production HttpClient setup (Java engine,
 * [wavesDefaults]) and a [DriveCloudStore] + [DefaultSyncService] pointed at the shared [FakeDriveServer].
 */
class Device(
    val label: String,
    server: FakeDriveServer,
    private val clock: TestClock,
    private val skewMs: Long = 0,
) : AutoCloseable {
    val dir: File = Files.createTempDirectory("waves-device-$label").toFile()
    val driver = createDesktopDriver(File(dir, "waves.db"))
    val repo = SqlLibraryRepository(driver, Dispatchers.IO) { now() }
    val auth = FakeAuth().apply { tokens = ArrayDeque((1..5).map { "$label-$it" }) }
    val http = HttpClient(Java) { wavesDefaults() }
    val store = DriveCloudStore(http, auth, server.baseUrl, retryDelaysMs = listOf(10, 20))
    val sync = DefaultSyncService(repo, store, clock = ::now)

    fun now() = clock.now + skewMs

    suspend fun deviceId() = repo.deviceId()

    /** Runs one sync and returns its outcome. */
    suspend fun syncNow(): SyncStatus {
        sync.syncNow()
        return sync.status.value
    }

    suspend fun subscriptions(): List<String> = repo.subscriptions.first().map { it.feedUrl }
    suspend fun progress(id: EpisodeId): PlaybackProgress? = repo.progress(id).first()
    suspend fun history(): List<HistoryEntry> = repo.history.first()
    suspend fun state(): SyncState = SyncMerge.normalize(repo.exportSyncState())

    suspend fun play(id: EpisodeId, positionMs: Long, durationMs: Long = 3_600_000, completed: Boolean = false) =
        repo.saveProgress(PlaybackProgress(id, positionMs, durationMs, completed, now()))

    suspend fun listened(id: EpisodeId, title: String = id.guid) =
        repo.addHistory(HistoryEntry(id, title, "Pod", now(), deviceId()))

    override fun close() {
        http.close()
        driver.close()
        dir.deleteRecursively()
    }
}

fun podcast(n: Int) = Podcast("https://feeds.example.com/$n.xml", "Podcast $n", author = "Author $n", artworkUrl = "https://img.example.com/$n.jpg")
fun episode(feed: Int, guid: String) = EpisodeId(podcast(feed).feedUrl, guid)

const val MIN = 60_000L
