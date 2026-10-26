package io.waves.sync

import io.ktor.http.parseQueryString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Minimal one-shot HTTP server on `http://127.0.0.1:<random port>/` that captures the OAuth redirect.
 * Plain sockets keep the jlink'd runtime free of extra modules (jdk.httpserver).
 */
class LoopbackReceiver(private val timeout: Duration = 5.minutes) : Closeable {
    data class Callback(val code: String?, val state: String?, val error: String?)

    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress()).apply { soTimeout = 500 }

    val redirectUri: String = "http://127.0.0.1:${server.localPort}"

    /** Waits for the browser to hit the redirect URI; ignores unrelated requests such as /favicon.ico. */
    suspend fun awaitCallback(): Callback = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeout.inWholeMilliseconds
        while (true) {
            coroutineContext.ensureActive()
            if (System.currentTimeMillis() > deadline) error("Timed out waiting for Google sign-in in the browser")
            val socket = try {
                server.accept()
            } catch (_: SocketTimeoutException) {
                continue
            }
            socket.use {
                it.soTimeout = 5_000
                val requestLine = runCatching { it.getInputStream().bufferedReader().readLine() }.getOrNull().orEmpty()
                val callback = parseRequestLine(requestLine)
                val (status, html) = when {
                    callback == null -> "404 Not Found" to "Not found"
                    callback.error != null -> "200 OK" to page("Sign-in was cancelled", "You can close this tab and return to Waves.")
                    else -> "200 OK" to page("Signed in to Waves", "You can close this tab and return to the app.")
                }
                val bytes = html.toByteArray(Charsets.UTF_8)
                it.getOutputStream().apply {
                    write(
                        ("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\n" +
                            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
                    )
                    write(bytes)
                    flush()
                }
                if (callback != null) return@withContext callback
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    override fun close() = server.close()

    internal companion object {
        /** Parses `GET /?code=..&state=.. HTTP/1.1`; null if it isn't an OAuth redirect. */
        fun parseRequestLine(line: String): Callback? {
            val parts = line.split(' ')
            if (parts.size < 2 || parts[0] != "GET") return null
            val target = parts[1]
            val path = target.substringBefore('?')
            if (path != "/" && path.isNotEmpty()) return null
            val params = parseQueryString(target.substringAfter('?', ""))
            if (params["code"] == null && params["error"] == null) return null
            return Callback(code = params["code"], state = params["state"], error = params["error"])
        }

        private fun page(title: String, text: String) =
            "<!doctype html><html><head><title>$title</title></head>" +
                "<body style=\"font-family:-apple-system,sans-serif;text-align:center;margin-top:20vh\">" +
                "<h2>$title</h2><p>$text</p></body></html>"
    }
}
