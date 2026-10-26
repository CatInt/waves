package io.waves.player

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Resolves podcast enclosure URLs through their tracking-redirect chains (podtrac, chartable, …) to the
 * final media URL. JavaFX's media stack does not follow cross-protocol (http -> https) redirects, so the
 * desktop player always hands it the resolved URL.
 */
object RedirectResolver {
    const val MAX_HOPS = 10
    private const val TIMEOUT_MS = 10_000
    private const val USER_AGENT = "Waves/1.0 (podcast player)"

    /** Result of a single request: the status code and the raw `Location` header, if any. */
    data class Hop(val status: Int, val location: String?)

    /** Follows redirects over the network; on any failure returns the best URL reached so far. */
    fun resolve(url: String): String = resolve(url, ::fetchHop)

    /** Redirect-following loop with an injectable transport (unit-testable). */
    fun resolve(url: String, fetch: (String) -> Hop): String {
        var current = url
        repeat(MAX_HOPS) {
            val hop = try {
                fetch(current)
            } catch (e: Exception) {
                return current
            }
            if (hop.status !in 300..399 || hop.location.isNullOrBlank()) return current
            current = nextUrl(current, hop.location) ?: return current
        }
        return current
    }

    /** Resolves a (possibly relative) `Location` header against the current URL. */
    fun nextUrl(current: String, location: String): String? = try {
        URI(current).resolve(location.trim().replace(" ", "%20")).toString()
    } catch (e: Exception) {
        null
    }

    private fun fetchHop(url: String): Hop {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.requestMethod = "GET"
            // Ask for (almost) nothing: we only want the status line and headers.
            connection.setRequestProperty("Range", "bytes=0-1")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            return Hop(connection.responseCode, connection.getHeaderField("Location"))
        } finally {
            connection.disconnect()
        }
    }
}
