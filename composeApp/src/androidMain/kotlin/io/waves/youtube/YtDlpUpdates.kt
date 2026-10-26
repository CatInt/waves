package io.waves.youtube

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.yausername.youtubedl_android.YoutubeDL
import io.waves.currentTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.hours
import kotlin.time.toJavaDuration

/**
 * Refreshes the bundled yt-dlp from the stable channel at most once a day, only while online.
 * Failures are logged and otherwise ignored: playback keeps the binary it already has.
 */
internal object YtDlpUpdates {
    private const val PREFS = "waves_ytdlp"
    private const val KEY_ATTEMPT = "last_attempt_ms"
    private const val PERIODIC = "waves-ytdlp-update"
    private const val ONCE = "waves-ytdlp-update-now"
    private val dayMs = 24.hours.inWholeMilliseconds

    @Volatile private var periodicQueued = false

    /** Enqueue the daily job, and a one-off now when the last attempt is older than a day. Does not run yt-dlp. */
    fun poke(context: Context) {
        val app = context.applicationContext
        if (!periodicQueued) {
            synchronized(this) {
                if (!periodicQueued) {
                    val daily = PeriodicWorkRequestBuilder<YtDlpUpdateWorker>(24.hours.toJavaDuration())
                        .setConstraints(online())
                        .build()
                    WorkManager.getInstance(app).enqueueUniquePeriodicWork(
                        PERIODIC,
                        ExistingPeriodicWorkPolicy.KEEP,
                        daily,
                    )
                    periodicQueued = true
                }
            }
        }
        if (isDue(app)) {
            val now = OneTimeWorkRequestBuilder<YtDlpUpdateWorker>().setConstraints(online()).build()
            WorkManager.getInstance(app).enqueueUniqueWork(ONCE, ExistingWorkPolicy.KEEP, now)
        }
    }

    fun isDue(context: Context): Boolean {
        val last = prefs(context).getLong(KEY_ATTEMPT, 0L)
        return last == 0L || currentTimeMillis() - last >= dayMs
    }

    /**
     * Records this attempt and returns the previous stamp, or null when one already happened in the last day.
     * Callers restore the previous stamp if the attempt is cancelled before it finishes.
     */
    fun beginAttempt(context: Context): Long? = synchronized(this) {
        val stored = prefs(context)
        val last = stored.getLong(KEY_ATTEMPT, 0L)
        val now = currentTimeMillis()
        if (last != 0L && now - last < dayMs) return null
        stored.edit().putLong(KEY_ATTEMPT, now).apply()
        last
    }

    fun restoreAttempt(context: Context, previous: Long) {
        synchronized(this) {
            prefs(context).edit().putLong(KEY_ATTEMPT, previous).apply()
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun online() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
}

class YtDlpUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val previous = YtDlpUpdates.beginAttempt(applicationContext) ?: return Result.success()
        try {
            withContext(Dispatchers.IO) {
                if (!YtDlpRuntime.ensureInitialized(applicationContext)) return@withContext
                YoutubeDL.getInstance().updateYoutubeDL(applicationContext, YoutubeDL.UpdateChannel.STABLE)
            }
        } catch (e: CancellationException) {
            YtDlpUpdates.restoreAttempt(applicationContext, previous)
            throw e
        } catch (e: Exception) {
            Log.w(YTDLP_LOG_TAG, "yt-dlp update failed", e)
        }
        return Result.success()
    }
}
