package io.waves.ui.fake

import io.waves.currentTimeMillis
import io.waves.download.DownloadInfo
import io.waves.download.DownloadManager
import io.waves.download.DownloadStatus
import io.waves.model.Episode
import io.waves.model.EpisodeId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration

/**
 * In-memory [DownloadManager] that simulates transfers: an enqueued episode is Queued for one step, then
 * Downloading for [steps] steps of [stepMs] each, then Done. Nothing touches the network or the disk.
 *
 * @param sizeOf total size reported for an episode; null simulates a missing Content-Length (indeterminate progress).
 * @param failOnce episodes for which the first attempt fails halfway (a retry then succeeds).
 */
class InMemoryDownloadManager(
    private val scope: CoroutineScope,
    private val stepMs: Long = 300,
    private val steps: Int = 10,
    private val sizeOf: (Episode) -> Long? = { e -> (e.durationSeconds ?: 1800L) * 16_000 },
    private val failOnce: (Episode) -> Boolean = { false },
    private val clock: () -> Long = ::currentTimeMillis,
) : DownloadManager {
    private val items = MutableStateFlow<Map<EpisodeId, DownloadInfo>>(emptyMap())
    private val jobs = mutableMapOf<EpisodeId, Job>()
    private val attempts = mutableMapOf<EpisodeId, Int>()

    override val downloads: Flow<List<DownloadInfo>> =
        items.map { m -> m.values.sortedByDescending { it.createdAtEpochMs } }

    override fun state(episodeId: EpisodeId): Flow<DownloadInfo?> = items.map { it[episodeId] }.distinctUntilChanged()

    override suspend fun enqueue(episode: Episode) {
        val id = episode.id
        val existing = items.value[id]
        if (existing != null && existing.status != DownloadStatus.Failed) return
        val total = sizeOf(episode)
        val info = DownloadInfo(
            episodeId = id,
            status = DownloadStatus.Queued,
            bytesDownloaded = existing?.bytesDownloaded ?: 0,
            totalBytes = total,
            filePath = "/fake/episodes/${id.guid}.mp3",
            error = null,
            episodeTitle = episode.title,
            audioUrl = episode.audioUrl,
            createdAtEpochMs = existing?.createdAtEpochMs ?: clock(),
        )
        put(info)
        val attempt = (attempts[id] ?: 0) + 1
        attempts[id] = attempt
        jobs[id]?.cancel()
        jobs[id] = scope.launch { run(id, total ?: (steps * 100_000L), reportTotal = total != null, fail = attempt == 1 && failOnce(episode)) }
    }

    private suspend fun run(id: EpisodeId, total: Long, reportTotal: Boolean, fail: Boolean) {
        delay(stepMs)
        val chunk = (total / steps.coerceAtLeast(1)).coerceAtLeast(1)
        for (i in 1..steps) {
            update(id) { it.copy(status = DownloadStatus.Downloading) }
            delay(stepMs)
            if (fail && i > steps / 2) {
                update(id) { it.copy(status = DownloadStatus.Failed, error = "Simulated network error") }
                return
            }
            update(id) { it.copy(bytesDownloaded = (it.bytesDownloaded + chunk).coerceAtMost(total)) }
        }
        update(id) {
            it.copy(
                status = DownloadStatus.Done, bytesDownloaded = total,
                totalBytes = if (reportTotal) it.totalBytes else total, completedAtEpochMs = clock(),
            )
        }
    }

    override suspend fun cancel(episodeId: EpisodeId) {
        if (items.value[episodeId]?.status == DownloadStatus.Done) return
        remove(episodeId)
    }

    override suspend fun delete(episodeId: EpisodeId) = remove(episodeId)

    override fun localUri(episodeId: EpisodeId): String? =
        items.value[episodeId]?.takeIf { it.status == DownloadStatus.Done }?.let { "file://${it.filePath}" }

    override suspend fun deleteCompleted(olderThan: Duration): Int {
        val cutoff = clock() - olderThan.inWholeMilliseconds
        return deleteDownloads(
            items.value.values.filter { it.status == DownloadStatus.Done && (it.completedAtEpochMs ?: 0) < cutoff }
                .map { it.episodeId }
        )
    }

    override suspend fun deleteDownloads(episodeIds: Collection<EpisodeId>): Int {
        var n = 0
        for (id in episodeIds) if (items.value.containsKey(id)) { remove(id); n++ }
        return n
    }

    private fun remove(id: EpisodeId) {
        jobs.remove(id)?.cancel()
        items.update { it - id }
    }

    private fun put(info: DownloadInfo) = items.update { it + (info.episodeId to info) }

    private fun update(id: EpisodeId, f: (DownloadInfo) -> DownloadInfo) =
        items.update { m -> m[id]?.let { m + (id to f(it)) } ?: m }
}
