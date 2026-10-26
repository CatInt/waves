package io.waves.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import io.waves.R
import io.waves.model.EpisodeId
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/**
 * Runs one episode's transfer ([KtorDownloadManager.runDownload]) as a long-running foreground worker with a
 * progress notification. If the system stops it (network lost, quota), the download is left Queued and
 * WorkManager runs it again later, resuming via HTTP Range.
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val feedUrl = inputData.getString(KEY_FEED_URL) ?: return Result.failure()
        val guid = inputData.getString(KEY_GUID) ?: return Result.failure()
        val id = EpisodeId(feedUrl, guid)
        // Restarted by WorkManager before the app created the manager: wait a bit, then retry later.
        val manager = DownloadManagerHolder.await(30.seconds) ?: return Result.retry()

        val notificationId = NOTIFICATION_ID_BASE + (id.toString().hashCode() and 0xffff)
        tryForeground(foregroundInfo(notificationId, null))
        coroutineScope {
            val progress = launch {
                manager.state(id).filterNotNull().conflate().collect { info ->
                    if (info.status == DownloadStatus.Downloading || info.status == DownloadStatus.Queued) {
                        tryForeground(foregroundInfo(notificationId, info))
                    }
                    delay(1_000)
                }
            }
            try {
                manager.runDownload(id)
            } finally {
                progress.cancel()
            }
        }
        // Failures are recorded as DownloadStatus.Failed; the user retries via enqueue().
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo(NOTIFICATION_ID_BASE, null)

    private suspend fun tryForeground(info: ForegroundInfo) {
        try {
            setForeground(info)
        } catch (e: IllegalStateException) {
            // Android 12+ refuses to start foreground services from the background; keep working without one.
        }
    }

    private fun foregroundInfo(notificationId: Int, info: DownloadInfo?): ForegroundInfo {
        val context = applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW),
                )
            }
        }
        val progress = info?.progress
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_waves)
            .setContentTitle(info?.episodeTitle?.takeIf { it.isNotBlank() } ?: "Downloading episode")
            .setContentText(if (info?.status == DownloadStatus.Queued) "Waiting…" else progress?.let { "${(it * 100).toInt()}%" })
            .setProgress(100, ((progress ?: 0f) * 100).toInt(), progress == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    companion object {
        const val KEY_FEED_URL = "feedUrl"
        const val KEY_GUID = "guid"
        const val TAG = "waves-download"
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID_BASE = 0x4e000000
    }
}
