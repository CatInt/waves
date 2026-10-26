package io.waves.sync

import io.waves.model.HistoryEntry
import io.waves.model.PlaybackProgress
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.measureTimedValue

/** A heavy library (500 subscriptions, 20k progress rows, 2000 history entries) synced between two devices. */
class SyncScaleTest {
    private val server = FakeDriveServer()
    private val clock = TestClock()
    private val a = Device("A", server, clock)
    private val b = Device("B", server, clock)

    @AfterTest fun tearDown() { a.close(); b.close(); server.close() }

    private fun bigState(deviceId: String): SyncState {
        val t = clock.now
        return SyncState(
            subscriptions = (0 until 500).map { SubscriptionRecord(podcast(it), subscribed = it % 10 != 0, updatedAtEpochMs = t - it) },
            progress = (0 until 20_000).map {
                PlaybackProgress(episode(it % 500, "guid-$it-0123456789abcdef"), it * 1_000L, 3_600_000, it % 3 == 0, t - it)
            },
            history = (0 until 2_000).map {
                HistoryEntry(episode(it % 500, "guid-$it-0123456789abcdef"), "Episode title number $it", "Podcast ${it % 500}", t - it * 60_000L, deviceId)
            },
        )
    }

    private fun gzipSize(bytes: ByteArray): Int =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.size()

    @Test fun largeLibrarySyncsInReasonableTime() = runBlocking<Unit> {
        val (_, seed) = measureTimedValue { a.repo.importSyncState(bigState(a.deviceId())) }
        val (first, tFirst) = measureTimedValue { a.syncNow() }
        assertIs<SyncStatus.Done>(first, "$first")
        val stored = server.file(DefaultSyncService.DEFAULT_FILE_NAME)!!
        val storedBytes = stored.content.size
        val plainJson = SyncFormat.encode(a.state()).encodeToByteArray()

        val (second, tDownload) = measureTimedValue { b.syncNow() }
        assertIs<SyncStatus.Done>(second, "$second")
        val (noop, tNoop) = measureTimedValue { a.syncNow() }
        assertIs<SyncStatus.Done>(noop, "$noop")

        clock.advance(); b.play(episode(1, "new"), 60_000)
        val (third, tSmallEdit) = measureTimedValue { b.syncNow() }
        assertIs<SyncStatus.Done>(third, "$third")
        val (fourth, tPull) = measureTimedValue { a.syncNow() }
        assertIs<SyncStatus.Done>(fourth, "$fourth")

        assertEquals(a.state(), b.state())
        assertEquals(450, b.subscriptions().size)
        assertEquals(20_001, b.state().progress.size)
        assertEquals(2_000, b.history().size)
        assertEquals(0, server.oversizedUploads, "an upload exceeded Drive's 5 MB simple/multipart limit")

        val report = """
            |sync scale: 500 subscriptions, 20000 progress, 2000 history
            |  seed local DB (import)          ${seed.inWholeMilliseconds} ms
            |  A first sync (create)           ${tFirst.inWholeMilliseconds} ms
            |  B first sync (download+import)  ${tDownload.inWholeMilliseconds} ms
            |  A no-op sync                    ${tNoop.inWholeMilliseconds} ms
            |  B one edit (upload)             ${tSmallEdit.inWholeMilliseconds} ms
            |  A pull one edit (import)        ${tPull.inWholeMilliseconds} ms
            |  JSON size                       ${plainJson.size} bytes
            |  JSON gzip -6 size               ${gzipSize(plainJson)} bytes
            |  stored file size                $storedBytes bytes (${stored.mimeType})
            """.trimMargin()
        println(report)
        java.io.File("build/reports/sync-scale.txt").apply { parentFile.mkdirs() }.writeText(report + "\n")
        for (t in listOf(tFirst, tDownload, tNoop, tSmallEdit, tPull)) assertTrue(t.inWholeSeconds < 30, report)
    }
}
