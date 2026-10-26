package io.waves.refresh

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.plugins.cache.storage.FileStorage
import io.waves.R
import io.waves.data.LibraryRepository
import io.waves.data.createLibraryRepository
import io.waves.feed.FeedRepository
import io.waves.youtube.YoutubeFeedRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.toJavaDuration

/**
 * Android wiring. From `Application.onCreate` (or `MainActivity.onCreate`):
 * ```
 * val refresher = AndroidRefresh.init(this, library = myLibraryRepository)  // channel + 6h periodic work
 * refresher.status                                                          // StateFlow<RefreshStatus> for UI
 * AndroidRefresh.refreshNow(this)                                           // one-off, with notifications
 * ```
 * Pass the app's own [LibraryRepository] so UI flows see episodes the worker writes. If the process is started
 * by WorkManager before `init`, a default refresher on `waves.db` is created. POST_NOTIFICATIONS must be granted
 * at runtime by the UI on Android 13+; without it, refresh still runs but posts nothing.
 */
object AndroidRefresh {
    const val CHANNEL_ID = "new_episodes"
    const val EXTRA_FEED_URL = "io.waves.refresh.FEED_URL"
    private const val PERIODIC_WORK = "waves-feed-refresh"
    private const val ONE_TIME_WORK = "waves-feed-refresh-now"

    @Volatile private var instance: FeedRefresher? = null

    /** Sets up the channel, the shared [FeedRefresher] and periodic refresh every [interval] (min 15 min). */
    fun init(
        context: Context,
        library: LibraryRepository? = null,
        feeds: FeedRepository? = null,
        interval: Duration = 6.hours,
    ): FeedRefresher {
        val app = context.applicationContext
        createChannel(app)
        val refresher = synchronized(this) {
            val current = instance
            if (current != null && library == null && feeds == null) current
            else build(app, library, feeds).also { instance = it }
        }
        schedule(app, interval)
        return refresher
    }

    /** The shared refresher (created with defaults if [init] has not run in this process). */
    fun refresher(context: Context): FeedRefresher =
        instance ?: synchronized(this) { instance ?: build(context.applicationContext, null, null).also { instance = it } }

    fun schedule(context: Context, interval: Duration = 6.hours) {
        val request = PeriodicWorkRequestBuilder<FeedRefreshWorker>(interval.toJavaDuration())
            .setConstraints(networkConstraint())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** Refresh soon (when online) and notify about new episodes. */
    fun refreshNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<FeedRefreshWorker>().setConstraints(networkConstraint()).build()
        WorkManager.getInstance(context).enqueueUniqueWork(ONE_TIME_WORK, ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK)
    }

    fun createFeedRepository(context: Context): FeedRepository {
        val cacheDir = File(context.cacheDir, "feed-http-cache").apply { mkdirs() }
        val client = HttpClient(Android) {
            // ETag / If-Modified-Since revalidation for unchanged feeds.
            install(HttpCache) { publicStorage(FileStorage(cacheDir)) }
        }
        return YoutubeFeedRepository(client)
    }

    private fun build(context: Context, library: LibraryRepository?, feeds: FeedRepository?) = FeedRefresher(
        feeds = feeds ?: createFeedRepository(context),
        library = library ?: createLibraryRepository(context),
        stateStore = SharedPrefsRefreshStateStore(context),
    )

    private fun networkConstraint() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    internal fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "New episodes", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "New episodes from your subscriptions"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}

/** Runs [FeedRefresher.refreshAll] and posts a notification per podcast with new episodes. */
class FeedRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val offline = (applicationContext as? io.waves.WavesApplication)?.graphOrNull?.offline?.value == true
        if (offline) return Result.success()
        val result = try {
            AndroidRefresh.refresher(applicationContext).refreshAll()
        } catch (e: CancellationException) {
            throw e // WorkManager stopped us; it reschedules on its own
        } catch (e: Exception) {
            return Result.retry()
        }
        AndroidRefreshNotifier(applicationContext).notifyNewEpisodes(result.updates)
        return if (result.total > 0 && result.succeeded == 0) Result.retry() else Result.success()
    }
}

/** Posts one notification per podcast on the "New episodes" channel; tapping opens the app. */
class AndroidRefreshNotifier(private val context: Context) : RefreshNotifier {
    override fun notifyNewEpisodes(updates: List<PodcastUpdate>) {
        if (updates.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        AndroidRefresh.createChannel(context)
        for (update in updates) {
            val feedUrl = update.podcast.feedUrl
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(AndroidRefresh.EXTRA_FEED_URL, feedUrl)
            }
            val pending = intent?.let {
                PendingIntent.getActivity(
                    context, feedUrl.hashCode(), it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }
            val notification = NotificationCompat.Builder(context, AndroidRefresh.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_waves)
                .setContentTitle(RefreshText.title(update))
                .setContentText(RefreshText.body(update))
                .setStyle(NotificationCompat.InboxStyle().also { s -> update.newEpisodes.take(5).forEach { s.addLine(it.title) } })
                .setNumber(update.newEpisodes.size)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build()
            try {
                manager.notify(NOTIFICATION_TAG, feedUrl.hashCode(), notification)
            } catch (_: SecurityException) {
                return
            }
        }
    }

    private companion object {
        const val NOTIFICATION_TAG = "new_episodes"
    }
}

/** [RefreshStateStore] in SharedPreferences (feedUrl -> epoch ms). */
class SharedPrefsRefreshStateStore(context: Context) : RefreshStateStore {
    private val prefs = context.applicationContext.getSharedPreferences("waves_refresh_state", Context.MODE_PRIVATE)

    override suspend fun lastRefreshedAt(feedUrl: String): Long? = withContext(Dispatchers.IO) {
        if (prefs.contains(feedUrl)) prefs.getLong(feedUrl, 0L) else null
    }

    override suspend fun setLastRefreshedAt(feedUrl: String, epochMs: Long) = withContext(Dispatchers.IO) {
        prefs.edit().putLong(feedUrl, epochMs).apply()
    }
}
