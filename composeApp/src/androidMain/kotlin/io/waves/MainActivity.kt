package io.waves

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import io.waves.model.Podcast
import io.waves.refresh.AndroidRefresh
import io.waves.ui.Screen
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* playback works either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val graph = (application as WavesApplication).graph
        setContent {
            val session by graph.session.collectAsState()
            session?.let { s ->
                val navigator = s.state.navigator
                // Hardware / gesture back pops the in-app stack; at a tab root the system handles it (exit).
                BackHandler(enabled = navigator.canGoBack) { navigator.pop() }
                App(s.deps, s.state)
            }
        }
        if (savedInstanceState == null) {
            askForNotificationsOnce()
            openPodcastFrom(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openPodcastFrom(intent)
    }

    /** "New episodes" notification tap: open that podcast's page. */
    private fun openPodcastFrom(intent: Intent?) {
        val feedUrl = intent?.getStringExtra(AndroidRefresh.EXTRA_FEED_URL) ?: return
        intent.removeExtra(AndroidRefresh.EXTRA_FEED_URL) // don't reopen on recreation
        val graph = (application as WavesApplication).graph
        lifecycleScope.launch {
            val session = graph.session.filterNotNull().first()
            val known = graph.library.subscriptions.first().firstOrNull { it.feedUrl == feedUrl }
            val podcast = known ?: Podcast(feedUrl = feedUrl, title = "")
            session.state.navigator.push(Screen.PodcastDetail(podcast, autoFetch = known == null))
        }
    }

    /** Android 13+: the media playback notification needs POST_NOTIFICATIONS. Asked on first launch only. */
    private fun askForNotificationsOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences("waves_app", Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ASKED_NOTIFICATIONS, false)) return
        prefs.edit().putBoolean(KEY_ASKED_NOTIFICATIONS, true).apply()
        requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private companion object {
        const val KEY_ASKED_NOTIFICATIONS = "asked_post_notifications"
    }
}
