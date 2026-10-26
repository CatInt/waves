package io.waves.download

import io.waves.model.EpisodeId

/**
 * Decides where [KtorDownloadManager.runDownload] runs. The default (null scheduler) runs it in the manager's own
 * coroutine scope; Android plugs in WorkManager so downloads survive backgrounding and process death.
 */
interface DownloadScheduler {
    /** Arrange for `runDownload(id)` to be called (now or when constraints allow). Must be idempotent. */
    fun schedule(id: EpisodeId)

    /** Drop pending work for [id]; running work is also cancelled directly by the manager. */
    fun unschedule(id: EpisodeId)
}
