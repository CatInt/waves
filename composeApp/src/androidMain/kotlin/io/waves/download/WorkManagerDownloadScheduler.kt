package io.waves.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.waves.model.EpisodeId

/** One unique [DownloadWorker] per episode, requiring a connected network. */
internal class WorkManagerDownloadScheduler(context: Context) : DownloadScheduler {
    private val workManager = WorkManager.getInstance(context)

    override fun schedule(id: EpisodeId) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY_FEED_URL to id.feedUrl, DownloadWorker.KEY_GUID to id.guid))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(DownloadWorker.TAG)
            .build()
        // KEEP: re-scheduling a pending/running download is a no-op.
        workManager.enqueueUniqueWork(workName(id), ExistingWorkPolicy.KEEP, request)
    }

    override fun unschedule(id: EpisodeId) {
        workManager.cancelUniqueWork(workName(id))
    }

    private fun workName(id: EpisodeId) = "download:$id"
}
