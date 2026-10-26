package io.waves.download

import app.cash.sqldelight.db.SqlDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.waves.data.SqlLibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * Desktop downloads into [directory] (default `~/Library/Application Support/Waves/episodes/`), sharing the
 * library database [driver]. Downloads run in-process (at most 2 at a time) while the app is open; interrupted
 * ones resume on the next launch.
 */
fun createDownloadManager(
    driver: SqlDriver,
    directory: File = defaultDownloadDirectory(),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    client: HttpClient = HttpClient(Java) { downloadDefaults() },
    locate: suspend (String) -> String = { it },
): KtorDownloadManager {
    directory.mkdirs()
    return KtorDownloadManager(driver, client, directory.absolutePath, scope, locate = locate)
}

/** Same as above, sharing [library]'s connection. [locate] turns a YouTube watch URL into an audio stream. */
fun createDownloadManager(
    library: SqlLibraryRepository,
    directory: File = defaultDownloadDirectory(),
    locate: suspend (String) -> String = { it },
): KtorDownloadManager = createDownloadManager(library.driver, directory, locate = locate)

/** `~/Library/Application Support/Waves/episodes` on macOS, `~/.waves/episodes` elsewhere (next to waves.db). */
fun defaultDownloadDirectory(): File = File(io.waves.data.defaultDatabaseFile().parentFile, "episodes")
