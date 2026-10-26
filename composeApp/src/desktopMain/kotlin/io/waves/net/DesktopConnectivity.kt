package io.waves.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI

/**
 * Hosts whose answer means "online". Several, because any one may be blocked on a given network (Google's
 * check is unreachable in some regions); any HTTP response counts, whatever its status.
 */
private val probes = listOf(
    "https://itunes.apple.com/",
    "http://captive.apple.com/hotspot-detect.html",
    "https://www.gstatic.com/generate_204",
)

/** Whether any of [probes] answers; desktop JVMs have no system connectivity signal. */
fun desktopOnline(scope: CoroutineScope): StateFlow<Boolean> = pollOnline(scope) {
    coroutineScope { probes.map { async { answers(it) } }.awaitAll().any { it } }
}

private suspend fun answers(url: String): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "HEAD"
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.instanceFollowRedirects = false
            connection.responseCode > 0
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(false)
}
