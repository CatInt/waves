package io.waves

import android.app.Activity
import android.app.Application
import android.os.Bundle
import io.waves.refresh.AndroidRefresh
import kotlinx.coroutines.launch

/**
 * Owns the process-wide [AppGraph] (HTTP client, database, player, sync), so it survives Activity recreation.
 * Holds no Activity references.
 *
 * Foreground/background is derived from started-activity counts (what ProcessLifecycleOwner does, without
 * the extra dependency): background → save playback progress; back to foreground → sync right away.
 */
class WavesApplication : Application() {
    lateinit var graph: AppGraph
        private set

    val graphOrNull: AppGraph? get() = if (::graph.isInitialized) graph else null

    private var startedActivities = 0
    private var wasBackgrounded = false

    override fun onCreate() {
        super.onCreate()
        graph = createAndroidAppGraph(this)
        graph.start()
        // Background feed refresh (WorkManager) sharing the app's database and cached HTTP client.
        AndroidRefresh.init(this, library = graph.library, feeds = graph.feeds)
        AndroidRefresh.refresher(this).onEpisodesAbsent = graph::noteEpisodesAbsent
        // Runs only with a network; FeedRefreshWorker also skips a run while the Internet is unreachable.
        AndroidRefresh.schedule(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (startedActivities++ == 0 && wasBackgrounded) graph.onForeground()
            }

            override fun onActivityStopped(activity: Activity) {
                // A configuration-change recreation is not "going to the background".
                if (--startedActivities == 0 && !activity.isChangingConfigurations) {
                    wasBackgrounded = true
                    graph.onBackground()
                    graph.appScope.launch { graph.saveProgress() }
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
