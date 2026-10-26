package io.waves.refresh

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.plugins.cache.storage.FileStorage
import io.waves.data.FeedFetchLog
import io.waves.data.LibraryRepository
import io.waves.data.SqlFeedFetchLog
import io.waves.data.SqlLibraryRepository
import io.waves.data.defaultDatabaseFile
import io.waves.feed.FeedRepository
import io.waves.youtube.YoutubeFeedRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import java.io.File
import java.util.Properties
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Desktop wiring. From `main()`:
 * ```
 * val refresh = DesktopRefresh.start(library)   // refreshes now, then hourly; tray notification on new episodes
 * refresh.status                                 // StateFlow<RefreshStatus> for the UI
 * refresh.refreshNow()                           // manual refresh
 * ```
 */
object DesktopRefresh {
    /** Next to the database: `~/Library/Application Support/Waves` on macOS, `~/.waves` elsewhere. */
    fun defaultDir(): File = defaultDatabaseFile().parentFile

    /**
     * Feed repository whose HTTP client keeps an on-disk HTTP cache, so unchanged feeds are revalidated with
     * ETag / If-Modified-Since (304) instead of re-downloaded. YouTube channel feeds go through the same client.
     */
    fun createFeedRepository(cacheDir: File = File(defaultDir(), "http-cache")): FeedRepository {
        cacheDir.mkdirs()
        val client = HttpClient(Java) {
            install(HttpCache) { publicStorage(FileStorage(cacheDir)) }
        }
        return YoutubeFeedRepository(client)
    }

    fun createRefresher(
        library: LibraryRepository,
        feeds: FeedRepository = createFeedRepository(),
        stateStore: RefreshStateStore = PropertiesRefreshStateStore(File(defaultDir(), "refresh-state.properties")),
        fetchLog: FeedFetchLog? = (library as? SqlLibraryRepository)?.let { SqlFeedFetchLog(it.driver) },
    ): FeedRefresher = FeedRefresher(feeds, library, stateStore, fetchLog)

    /** Creates and starts the scheduler: refresh on start, then every [period]. */
    fun start(
        library: LibraryRepository,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        refresher: FeedRefresher = createRefresher(library),
        notifier: RefreshNotifier? = TrayRefreshNotifier.createOrNull(),
        period: Duration = 1.hours,
        enabled: Flow<Boolean> = flowOf(true),
        initiallyEnabled: Boolean = true,
    ): RefreshScheduler = RefreshScheduler(
        refresher, scope, notifier, period,
        enabled = enabled, initiallyEnabled = initiallyEnabled,
    ).also { it.start() }
}

/** [RefreshStateStore] persisted in a small properties file (feedUrl -> epoch ms). */
class PropertiesRefreshStateStore(private val file: File) : RefreshStateStore {
    private val lock = Any()
    private var cache: Properties? = null

    private fun props(): Properties = cache ?: Properties().also { p ->
        if (file.isFile) runCatching { file.inputStream().use { p.load(it) } }
        cache = p
    }

    override suspend fun lastRefreshedAt(feedUrl: String): Long? = withContext(Dispatchers.IO) {
        synchronized(lock) { props().getProperty(feedUrl)?.toLongOrNull() }
    }

    override suspend fun setLastRefreshedAt(feedUrl: String, epochMs: Long) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val p = props()
            p.setProperty(feedUrl, epochMs.toString())
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.outputStream().use { p.store(it, "Waves feed refresh state") }
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
            Unit
        }
    }
}

/**
 * Desktop notification through the AWT system tray (macOS shows these as Notification Center banners).
 * The tray icon is only added the first time there is something to announce.
 */
class TrayRefreshNotifier private constructor() : RefreshNotifier {
    private var icon: TrayIcon? = null

    override fun notifyNewEpisodes(updates: List<PodcastUpdate>) {
        if (updates.isEmpty()) return
        EventQueue.invokeLater {
            runCatching {
                val tray = icon ?: TrayIcon(appImage(), "Waves").also {
                    it.isImageAutoSize = true
                    SystemTray.getSystemTray().add(it)
                    icon = it
                }
                if (updates.size > MAX_INDIVIDUAL) {
                    tray.displayMessage("New episodes", RefreshText.summary(updates), TrayIcon.MessageType.INFO)
                } else {
                    updates.forEach {
                        tray.displayMessage(RefreshText.title(it), RefreshText.body(it), TrayIcon.MessageType.INFO)
                    }
                }
            }
        }
    }

    companion object {
        private const val MAX_INDIVIDUAL = 3

        /** Null when there is no display or the platform has no system tray. */
        fun createOrNull(): TrayRefreshNotifier? = try {
            if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) null else TrayRefreshNotifier()
        } catch (_: Throwable) {
            null
        }

        private fun appImage(): BufferedImage {
            val size = 32
            return BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB).apply {
                val g = createGraphics()
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.color = java.awt.Color(0x6750A4)
                g.fillOval(2, 2, size - 4, size - 4)
                g.dispose()
            }
        }
    }
}
