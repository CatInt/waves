package io.waves.download

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.waves.data.SqlLibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.time.Duration

/**
 * Process-wide Android download manager sharing the library database [driver]. Transfers run in
 * [DownloadWorker]s (WorkManager, network-connected constraint, foreground progress notification), so they keep
 * going while the app is backgrounded and are rescheduled after process death.
 *
 * Idempotent: later calls return the first instance. Create it early (e.g. in `MainActivity.onCreate`) since
 * workers restarted by WorkManager wait for it.
 */
fun createDownloadManager(
    context: Context,
    driver: SqlDriver,
    locate: suspend (String) -> String = { it },
): KtorDownloadManager =
    DownloadManagerHolder.getOrCreate {
        val app = context.applicationContext
        KtorDownloadManager(
            driver = driver,
            client = HttpClient(Android) { downloadDefaults() },
            directory = downloadDirectory(app).absolutePath,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            scheduler = WorkManagerDownloadScheduler(app),
            locate = locate,
        )
    }

/** Same as above, sharing [library]'s connection. [locate] turns a YouTube watch URL into an audio stream. */
fun createDownloadManager(
    context: Context,
    library: SqlLibraryRepository,
    locate: suspend (String) -> String = { it },
): KtorDownloadManager = createDownloadManager(context, library.driver, locate)

/** App-specific external storage (`Android/data/io.waves/files/episodes`, no permission needed), else internal. */
fun downloadDirectory(context: Context): File =
    (context.getExternalFilesDir("episodes") ?: File(context.filesDir, "episodes")).also { it.mkdirs() }

internal object DownloadManagerHolder {
    private val instance = MutableStateFlow<KtorDownloadManager?>(null)

    fun getOrCreate(create: () -> KtorDownloadManager): KtorDownloadManager = synchronized(this) {
        instance.value ?: create().also { instance.value = it }
    }

    suspend fun await(timeout: Duration): KtorDownloadManager? =
        withTimeoutOrNull(timeout) { instance.filterNotNull().first() }
}
