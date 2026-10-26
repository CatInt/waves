package io.waves

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.cache.storage.FileStorage
import io.waves.data.SqlLibraryRepository
import io.waves.data.createDesktopDriver
import io.waves.data.defaultDatabaseFile
import io.waves.download.DownloadAwareAudioPlayer
import io.waves.download.createDownloadManager
import io.waves.download.explicitAudioExtension
import io.waves.player.DesktopAudioPlayer
import io.waves.player.RedirectResolver
import io.waves.player.Mp4Header
import io.waves.player.StreamRelay
import io.waves.refresh.DesktopRefresh
import io.waves.data.SqlSettingsStore
import io.waves.net.desktopOnline
import io.waves.youtube.YoutubeMediaLocator
import io.waves.youtube.YtDlpSource
import io.waves.sync.DesktopGoogleAuth
import io.waves.update.DesktopUpdateInstaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.net.URI

/**
 * Builds the desktop graph. Dispatchers.Main is the Swing EDT (kotlinx-coroutines-swing).
 *
 * @param quitApp shuts the app down cleanly; the updater calls it before swapping in a new version.
 */
fun createDesktopAppGraph(quitApp: () -> Unit): AppGraph {
    // Same cache directory DesktopRefresh.createFeedRepository uses.
    val cacheDir = File(DesktopRefresh.defaultDir(), "http-cache").apply { mkdirs() }
    val http = HttpClient(Java) { wavesDefaults(FileStorage(cacheDir)) }
    val driver = createDesktopDriver(defaultDatabaseFile())
    val library = SqlLibraryRepository(driver)
    val settings = SqlSettingsStore(driver)
    val locate = YoutubeMediaLocator(
        http,
        fallbacks = listOf(YtDlpSource()),
    )::locate
    // Downloads keep their own client (no request timeout for large files, identity encoding, no cache).
    val downloads = createDownloadManager(library, locate = locate)
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    return AppGraph(
        http = http,
        library = library,
        downloads = downloads,
        player = DownloadAwareAudioPlayer(
            DesktopAudioPlayer { url ->
                val located = locate(url)
                when {
                    // Downloads and the playback cache: YouTube's audio needs the same header fix as the relay applies.
                    url.startsWith("file:") -> url.also { runCatching { Mp4Header.clearFragmentedDurations(File(URI(url))) } }
                    located == url -> RedirectResolver.resolve(url)
                    // A resolved YouTube stream without a file extension: JavaFX needs one to play it.
                    explicitAudioExtension(located) == null -> StreamRelay.url(located)
                    else -> located
                }
            },
            downloads,
        ),
        auth = DesktopGoogleAuth(http),
        appScope = appScope,
        closeResources = { driver.close() },
        updateInstaller = DesktopUpdateInstaller(quitApp),
        settings = settings,
        online = desktopOnline(appScope),
    )
}
