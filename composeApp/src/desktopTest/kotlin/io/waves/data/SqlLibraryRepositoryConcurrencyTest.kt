package io.waves.data

import io.waves.model.EpisodeId
import io.waves.model.PlaybackProgress
import io.waves.sync.SyncState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The desktop driver opens one SQLite connection per thread for file databases, so read-then-write
 * transactions running concurrently on Dispatchers.IO can deadlock-upgrade and fail with SQLITE_BUSY.
 */
class SqlLibraryRepositoryConcurrencyTest {
    @Test
    fun concurrentProgressWritesAndImportsDoNotFailWithBusy() = runBlocking {
        val dir = Files.createTempDirectory("waves-db").toFile()
        val repo = SqlLibraryRepository(createDesktopDriver(dir.resolve("waves.db")), Dispatchers.IO)
        val ids = (0 until 8).map { EpisodeId("https://a/feed", "g$it") }
        val jobs = (0 until 8).map { worker ->
            async(Dispatchers.IO) {
                repeat(150) { n ->
                    val p = PlaybackProgress(ids[worker], positionMs = n.toLong(), durationMs = 1000, updatedAtEpochMs = n.toLong())
                    if (worker % 2 == 0) repo.saveProgress(p) else repo.importSyncState(SyncState(progress = listOf(p)))
                }
            }
        }
        jobs.awaitAll()
        assertEquals(8, repo.exportSyncState().progress.size)
        dir.deleteRecursively()
        Unit
    }
}
