package io.waves.download

import io.waves.data.LibraryRepository
import io.waves.model.Episode
import io.waves.model.EpisodeId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlin.time.Duration

/** Offline episode downloads. Platform factories: `createDownloadManager(...)` in androidMain / desktopMain. */
interface DownloadManager {
    /** Every known download (any status), newest first. */
    val downloads: Flow<List<DownloadInfo>>

    /** One episode's download, or null when it was never enqueued (or was cancelled/deleted). */
    fun state(episodeId: EpisodeId): Flow<DownloadInfo?>

    /**
     * Queues [episode] for download. No-op when it is already queued, downloading or done (with the file present).
     * A failed download is retried, resuming from the partial file when the server supports HTTP Range.
     */
    suspend fun enqueue(episode: Episode)

    /** Stops a queued/running/failed download and discards its partial data. Completed downloads are kept (see [delete]). */
    suspend fun cancel(episodeId: EpisodeId)

    /** Removes the download in any state, including the downloaded file. */
    suspend fun delete(episodeId: EpisodeId)

    /** `file://` URI of the completed download when its file exists, else null (stream [Episode.audioUrl]). Cheap, non-blocking. */
    fun localUri(episodeId: EpisodeId): String?

    /** Deletes completed downloads that finished more than [olderThan] ago. Returns how many were deleted. */
    suspend fun deleteCompleted(olderThan: Duration): Int

    /** Deletes the downloads of [episodeIds] (typically episodes marked played). Returns how many were deleted. */
    suspend fun deleteDownloads(episodeIds: Collection<EpisodeId>): Int

    /**
     * Caches [episode]'s audio in the background so later plays don't stream it. A cached episode is not a
     * download: it isn't listed in [downloads] or [state], and [evictCache] removes it once it goes unplayed.
     * [enqueue] takes the cached (or partially cached) file over instead of fetching it again. No-op when the
     * episode is downloaded, queued for download, already cached or being cached.
     */
    suspend fun cache(episode: Episode) {}

    /** Deletes cached episodes not played (nor cached) within [notAccessedFor]. Returns how many were deleted. */
    suspend fun evictCache(notAccessedFor: Duration): Int = 0

    /**
     * Episodes with a complete playback-cache file (not listed in [downloads]). Empty when the platform keeps no
     * such cache. Emissions are the current set, so offline Home can list them.
     */
    val cachedEpisodeIds: Flow<Set<EpisodeId>> get() = flowOf(emptySet())

    /**
     * `file://` URI to play [episode] from: the download, else the cached copy (which counts as an access for
     * [evictCache]), else null to stream it. Cheap, non-blocking.
     */
    fun playableUri(episode: Episode): String? = localUri(episode.id)
}

enum class DownloadStatus { Queued, Downloading, Done, Failed }

data class DownloadInfo(
    val episodeId: EpisodeId,
    val status: DownloadStatus,
    val bytesDownloaded: Long,
    /** Size of the whole file, null while unknown (no Content-Length). */
    val totalBytes: Long?,
    /** Absolute path of the downloaded file; the file only exists once [status] is [DownloadStatus.Done]. */
    val filePath: String,
    /** Human-readable reason when [status] is [DownloadStatus.Failed]. */
    val error: String?,
    val episodeTitle: String = "",
    val audioUrl: String = "",
    val createdAtEpochMs: Long = 0,
    val completedAtEpochMs: Long? = null,
) {
    /** 0..1 when the total size is known, else null (show an indeterminate indicator). */
    val progress: Float?
        get() = when {
            status == DownloadStatus.Done -> 1f
            totalBytes == null || totalBytes <= 0 -> null
            else -> (bytesDownloaded.toFloat() / totalBytes).coerceIn(0f, 1f)
        }
}

/** Deletes the downloads of every episode marked completed in [library]'s playback progress. */
suspend fun DownloadManager.deleteDownloadsOfCompletedEpisodes(library: LibraryRepository): Int =
    deleteDownloads(library.allProgress.first().filter { it.completed }.map { it.episodeId })
