package io.waves

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.cache.storage.FileStorage
import io.waves.data.createLibraryRepository
import io.waves.download.DownloadAwareAudioPlayer
import io.waves.download.createDownloadManager
import io.waves.data.SqlSettingsStore
import io.waves.net.androidOnline
import io.waves.offline.SqlPlaybackCache
import io.waves.player.AndroidAudioPlayer
import io.waves.player.PlaybackCacheBridge
import io.waves.youtube.YoutubeMediaLocator
import io.waves.youtube.YtDlpAndroidSource
import io.waves.sync.AndroidGoogleAuth
import io.waves.update.AndroidUpdateInstaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** Builds the Android graph from the application context. Call on the main thread (the player requires it). */
fun createAndroidAppGraph(context: Context): AppGraph {
    val appContext = context.applicationContext
    // Same cache directory AndroidRefresh.createFeedRepository uses.
    val cacheDir = File(appContext.cacheDir, "feed-http-cache").apply { mkdirs() }
    val http = HttpClient(Android) { wavesDefaults(FileStorage(cacheDir)) }
    val library = createLibraryRepository(appContext)
    val settings = SqlSettingsStore(library.driver)
    val locate = YoutubeMediaLocator(
        http,
        fallbacks = listOf(YtDlpAndroidSource(appContext)),
    )::locate
    // Created early: DownloadWorkers rescheduled after process death wait for this instance.
    // Downloads keep their own client (no request timeout for large files, identity encoding, no cache).
    val downloads = createDownloadManager(appContext, library, locate)
    val playbackCache = SqlPlaybackCache(library.driver) { id, audioUrl ->
        PlaybackCacheBridge.removeResource(id, audioUrl)
    }
    PlaybackCacheBridge.catalog = playbackCache
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    return AppGraph(
        http = http,
        library = library,
        downloads = downloads,
        player = DownloadAwareAudioPlayer(AndroidAudioPlayer(appContext, locate), downloads),
        auth = AndroidGoogleAuth(appContext, http),
        appScope = appScope,
        updateInstaller = AndroidUpdateInstaller(appContext),
        settings = settings,
        playbackCache = playbackCache,
        online = androidOnline(appContext, appScope),
    )
}
