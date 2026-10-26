package io.waves.sync

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.time.Instant
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * A local HTTP server emulating the subset of the Google Drive v3 REST API that [DriveCloudStore] uses, modelled
 * on the public reference (developers.google.com/drive/api/reference/rest/v3) rather than on our client:
 *
 * - `GET /drive/v3/files` (files.list): `spaces` decides the corpus (default `drive`, so appDataFolder files are
 *   invisible without `spaces=appDataFolder`); `q` supports `name = '...'` (with `\'` and `\\` escapes) and
 *   `trashed = true|false` joined by `and`; `orderBy` (createdTime, modifiedTime, name, optional `desc`);
 *   `pageSize` 1..1000 with `nextPageToken`; `fields` partial responses. Without `fields`, a File only carries
 *   the v3 defaults (kind, id, name, mimeType) — notably no `version`.
 * - `GET /drive/v3/files/{id}?alt=media`: content; without `alt=media`: metadata.
 * - `POST /upload/drive/v3/files?uploadType=multipart`: `multipart/related`, metadata part (JSON) first, media
 *   second; `parents: ["appDataFolder"]` puts the file in the appDataFolder space.
 * - `PATCH /upload/drive/v3/files/{id}?uploadType=media`: replaces content (media type from Content-Type).
 * - `version`: an int64 rendered as a JSON string, increasing on every change by an unspecified amount (here a
 *   random 1..3, so clients can't rely on +1).
 * - Bearer auth: missing token or an [expire]d one -> 401 `authError`.
 * - Fault injection: [failNext] returns Google-style error bodies (403 rate limits, 429, 5xx, ...), [dropNext]
 *   closes the connection without a response, [beforeHandle] runs arbitrary code (e.g. another device's sync)
 *   before a request is processed, to force interleavings.
 * Uploads over [MULTIPART_LIMIT] (5 MB, the documented limit for simple/multipart uploads) are accepted but
 * counted in [oversizedUploads] so tests can assert we stay under it.
 */
class FakeDriveServer : AutoCloseable {
    class StoredFile(
        val id: String,
        val name: String,
        val space: String,
        var content: ByteArray,
        var mimeType: String,
        var version: Long,
        val createdTime: Long,
        var modifiedTime: Long,
        var trashed: Boolean = false,
    )

    data class Recorded(val method: String, val path: String, val query: Map<String, String>, val token: String?, val bodySize: Int)

    private class Fault(val status: Int, val reason: String, val message: String, val match: (Recorded) -> Boolean, var remaining: Int)

    private val lock = Any()
    private val files = mutableListOf<StoredFile>()
    private val faults = mutableListOf<Fault>()
    private val drops = mutableListOf<Pair<(Recorded) -> Boolean, IntArray>>()
    private val expired = mutableSetOf<String>()
    private var clock = 1_700_000_000_000L
    private var nextId = 1

    val requests: MutableList<Recorded> = java.util.Collections.synchronizedList(mutableListOf())
    @Volatile var oversizedUploads = 0
    @Volatile var beforeHandle: (Recorded) -> Unit = {}

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = Executors.newCachedThreadPool()
        createContext("/") { ex -> handle(ex) }
        start()
    }

    val baseUrl: String = "http://127.0.0.1:${server.address.port}"

    override fun close() {
        server.stop(0)
        (server.executor as java.util.concurrent.ExecutorService).shutdownNow()
    }

    // ---- test controls ----

    fun expire(token: String) = synchronized(lock) { expired += token }

    /** The next [times] requests matching [match] fail with a Google-style error. */
    fun failNext(status: Int, reason: String = defaultReason(status), times: Int = 1, match: (Recorded) -> Boolean = { true }) =
        synchronized(lock) { faults += Fault(status, reason, defaultMessage(status, reason), match, times) }

    /** The next [times] requests matching [match] get no response at all (connection closed). */
    fun dropNext(times: Int = 1, match: (Recorded) -> Boolean = { true }) =
        synchronized(lock) { drops += match to intArrayOf(times) }

    fun clearFaults() = synchronized(lock) { faults.clear(); drops.clear() }

    fun appDataFiles(): List<StoredFile> = synchronized(lock) { files.filter { it.space == APP_DATA && !it.trashed }.toList() }

    fun file(name: String): StoredFile? = appDataFiles().filter { it.name == name }.minByOrNull { it.createdTime }

    /** The file's content as text, inflating gzip content. */
    fun text(name: String): String? = file(name)?.content?.let { if (isGzip(it)) gunzip(it) else it }?.decodeToString()

    /** Puts a file directly into appDataFolder, as another (possibly newer or broken) client would. */
    fun put(name: String, content: ByteArray, mimeType: String = "application/json"): StoredFile = synchronized(lock) {
        val now = tick()
        StoredFile("f${nextId++}", name, APP_DATA, content, mimeType, Random.nextLong(1, 10), now, now).also { files += it }
    }

    fun put(name: String, content: String) = put(name, content.encodeToByteArray())

    // ---- HTTP ----

    private fun handle(ex: HttpExchange) {
        try {
            val body = ex.requestBody.readBytes()
            val query = parseQuery(ex.requestURI.rawQuery)
            val token = ex.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")?.takeIf { it.isNotBlank() }
            val rec = Recorded(ex.requestMethod, ex.requestURI.path, query, token, body.size)
            requests += rec
            beforeHandle(rec)

            val drop = synchronized(lock) {
                drops.firstOrNull { (m, left) -> left[0] > 0 && m(rec) }?.also { it.second[0]-- } != null
            }
            if (drop) { ex.close(); return } // no status line: the client sees a broken connection

            if (token == null || synchronized(lock) { token in expired }) {
                return error(ex, 401, "authError", "Invalid Credentials", location = "Authorization")
            }
            val fault = synchronized(lock) {
                faults.firstOrNull { it.remaining > 0 && it.match(rec) }?.also { it.remaining-- }
            }
            if (fault != null) return error(ex, fault.status, fault.reason, fault.message)

            route(ex, rec, body)
        } catch (e: Throwable) {
            runCatching { error(ex, 500, "backendError", "Fake server bug: $e") }
        }
    }

    private fun route(ex: HttpExchange, rec: Recorded, body: ByteArray) {
        val p = rec.path
        when {
            rec.method == "GET" && p == "/drive/v3/files" -> list(ex, rec.query)
            rec.method == "GET" && p.startsWith("/drive/v3/files/") -> get(ex, p.removePrefix("/drive/v3/files/"), rec.query)
            rec.method == "POST" && p == "/upload/drive/v3/files" -> create(ex, rec.query, body)
            rec.method == "PATCH" && p.startsWith("/upload/drive/v3/files/") ->
                updateMedia(ex, p.removePrefix("/upload/drive/v3/files/"), rec.query, body)
            else -> error(ex, 404, "notFound", "Not Found: ${rec.method} $p")
        }
    }

    private fun list(ex: HttpExchange, q: Map<String, String>) {
        val spaces = (q["spaces"] ?: "drive").split(',').map { it.trim() }
        if (spaces.any { it != "drive" && it != APP_DATA }) return error(ex, 400, "invalid", "Invalid Value: spaces")
        val filter = q["q"]?.let { parseQ(it) ?: return error(ex, 400, "invalid", "Invalid Value: q") } ?: { _: StoredFile -> true }
        val order = parseOrderBy(q["orderBy"]) ?: return error(ex, 400, "invalid", "Invalid Value: orderBy")
        val pageSize = q["pageSize"]?.toIntOrNull()?.takeIf { it >= 1 }?.coerceAtMost(1000)
            ?: if (q["pageSize"] == null) 100 else return error(ex, 400, "invalid", "Invalid Value: pageSize")
        val start = q["pageToken"]?.toIntOrNull() ?: 0
        val matching = synchronized(lock) { files.filter { it.space in spaces && filter(it) }.sortedWith(order).toList() }
        val page = matching.drop(start).take(pageSize)
        val nextToken = if (start + pageSize < matching.size) (start + pageSize).toString() else null
        val fields = q["fields"]
        val fileFields = fields?.let { Regex("files\\(([^)]*)\\)").find(it)?.groupValues?.get(1)?.split(',')?.map(String::trim) }
        val obj = buildJsonObject {
            if (fields == null || "kind" in fields.split(',')) put("kind", "drive#fileList")
            if (nextToken != null && (fields == null || "nextPageToken" in fields)) put("nextPageToken", nextToken)
            put("files", JsonArray(page.map { fileJson(it, fileFields ?: DEFAULT_FILE_FIELDS) }))
        }
        json(ex, 200, obj.toString(), cacheControl = "no-cache, no-store, max-age=0, must-revalidate")
    }

    private fun get(ex: HttpExchange, id: String, q: Map<String, String>) {
        val file = synchronized(lock) { files.firstOrNull { it.id == id && !it.trashed } }
            ?: return error(ex, 404, "notFound", "File not found: $id.")
        if (q["alt"] == "media") {
            val bytes = synchronized(lock) { file.content.copyOf() }
            ex.responseHeaders.add("Content-Type", file.mimeType)
            ex.responseHeaders.add("Cache-Control", "private, max-age=0, must-revalidate")
            send(ex, 200, bytes)
        } else {
            json(ex, 200, fileJson(file, fieldsList(q["fields"])).toString())
        }
    }

    private fun create(ex: HttpExchange, q: Map<String, String>, body: ByteArray) {
        if (q["uploadType"] != "multipart") return error(ex, 400, "invalid", "Invalid Value: uploadType (only multipart emulated)")
        val contentType = ex.requestHeaders.getFirst("Content-Type").orEmpty()
        if (!contentType.startsWith("multipart/related")) return error(ex, 400, "badContent", "Media type '$contentType' is not supported.")
        val boundary = Regex("boundary=\"?([^\";]+)\"?").find(contentType)?.groupValues?.get(1)
            ?: return error(ex, 400, "badContent", "Missing boundary")
        val parts = parseMultipart(body, boundary) ?: return error(ex, 400, "badContent", "Malformed multipart body")
        if (parts.size != 2) return error(ex, 400, "badContent", "Expected metadata and media parts, got ${parts.size}")
        val (metaHeaders, metaBody) = parts[0]
        if (!metaHeaders["content-type"].orEmpty().startsWith("application/json")) return error(ex, 400, "badContent", "First part must be JSON metadata")
        val meta = runCatching { Json.parseToJsonElement(metaBody.decodeToString()).jsonObject }.getOrNull()
            ?: return error(ex, 400, "parseError", "Invalid metadata JSON")
        val parents = meta["parents"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        val space = if (parents == listOf(APP_DATA)) APP_DATA else "drive"
        val (mediaHeaders, media) = parts[1]
        if (media.size > MULTIPART_LIMIT) oversizedUploads++
        val file = synchronized(lock) {
            val now = tick()
            StoredFile(
                id = "f${nextId++}",
                name = meta["name"]?.jsonPrimitive?.content ?: "Untitled",
                space = space,
                content = media,
                mimeType = meta["mimeType"]?.jsonPrimitive?.content ?: mediaHeaders["content-type"]?.substringBefore(';') ?: "application/octet-stream",
                version = Random.nextLong(1, 4),
                createdTime = now,
                modifiedTime = now,
            ).also { files += it }
        }
        json(ex, 200, fileJson(file, fieldsList(q["fields"])).toString())
    }

    private fun updateMedia(ex: HttpExchange, id: String, q: Map<String, String>, body: ByteArray) {
        if (q["uploadType"] != "media") return error(ex, 400, "invalid", "Invalid Value: uploadType (only media emulated for updates)")
        if (body.size > MULTIPART_LIMIT) oversizedUploads++
        val file = synchronized(lock) {
            files.firstOrNull { it.id == id && !it.trashed }?.also {
                it.content = body
                it.mimeType = ex.requestHeaders.getFirst("Content-Type")?.substringBefore(';')?.trim() ?: it.mimeType
                it.version += Random.nextLong(1, 4)
                it.modifiedTime = tick()
            }
        } ?: return error(ex, 404, "notFound", "File not found: $id.")
        json(ex, 200, fileJson(file, fieldsList(q["fields"])).toString())
    }

    // ---- helpers ----

    private fun tick(): Long = ++clock

    private fun fieldsList(fields: String?) = fields?.split(',')?.map { it.trim() } ?: DEFAULT_FILE_FIELDS

    private fun fileJson(f: StoredFile, fields: List<String>): JsonObject = buildJsonObject {
        for (field in fields) when (field) {
            "kind" -> put("kind", "drive#file")
            "id" -> put("id", f.id)
            "name" -> put("name", f.name)
            "mimeType" -> put("mimeType", f.mimeType)
            "version" -> put("version", f.version.toString()) // int64 as string, like the real API
            "size" -> put("size", f.content.size.toString())
            "createdTime" -> put("createdTime", Instant.ofEpochMilli(f.createdTime).toString())
            "modifiedTime" -> put("modifiedTime", Instant.ofEpochMilli(f.modifiedTime).toString())
            "trashed" -> put("trashed", f.trashed)
            "parents" -> put("parents", JsonArray(listOf(JsonPrimitive(f.space))))
        }
    }

    /** Minimal Drive query language: `term op value` clauses joined by `and`. Returns null if unsupported. */
    private fun parseQ(q: String): ((StoredFile) -> Boolean)? {
        val clauses = mutableListOf<(StoredFile) -> Boolean>()
        var i = 0
        fun skipWs() { while (i < q.length && q[i].isWhitespace()) i++ }
        fun word(): String { skipWs(); val s = i; while (i < q.length && (q[i].isLetterOrDigit() || q[i] == '_')) i++; return q.substring(s, i) }
        while (true) {
            val term = word()
            skipWs()
            if (i >= q.length || q[i] != '=') return null
            i++
            skipWs()
            when (term) {
                "name" -> {
                    if (i >= q.length || q[i] != '\'') return null
                    i++
                    val sb = StringBuilder()
                    while (true) {
                        if (i >= q.length) return null
                        val c = q[i++]
                        when (c) {
                            '\\' -> { if (i >= q.length) return null; sb.append(q[i++]) }
                            '\'' -> break
                            else -> sb.append(c)
                        }
                    }
                    val name = sb.toString()
                    clauses += { it.name == name }
                }
                "trashed" -> when (word()) {
                    "true" -> clauses += { it.trashed }
                    "false" -> clauses += { !it.trashed }
                    else -> return null
                }
                else -> return null
            }
            skipWs()
            if (i >= q.length) break
            if (word() != "and") return null
        }
        return { f -> clauses.all { it(f) } }
    }

    private fun parseOrderBy(orderBy: String?): Comparator<StoredFile>? {
        if (orderBy == null) return compareBy { it.name } // unspecified by the API; any stable order
        var cmp: Comparator<StoredFile>? = null
        for (key in orderBy.split(',').map { it.trim() }) {
            val desc = key.endsWith(" desc")
            val c: Comparator<StoredFile> = when (key.removeSuffix(" desc").trim()) {
                "createdTime" -> compareBy<StoredFile> { it.createdTime }
                "modifiedTime" -> compareBy<StoredFile> { it.modifiedTime }
                "name" -> compareBy<StoredFile> { it.name }
                else -> return null
            }.let { if (desc) it.reversed() else it }
            cmp = cmp?.then(c) ?: c
        }
        return cmp
    }

    private fun parseMultipart(body: ByteArray, boundary: String): List<Pair<Map<String, String>, ByteArray>>? {
        val text = String(body, Charsets.ISO_8859_1) // byte-preserving
        val delimiter = "--$boundary"
        if (!text.startsWith(delimiter)) return null
        val parts = mutableListOf<Pair<Map<String, String>, ByteArray>>()
        var pos = delimiter.length
        while (true) {
            if (text.startsWith("--", pos)) break // closing delimiter
            if (!text.startsWith("\r\n", pos)) return null
            pos += 2
            val headerEnd = text.indexOf("\r\n\r\n", pos).takeIf { it >= 0 } ?: return null
            val headers = text.substring(pos, headerEnd).split("\r\n").filter { it.isNotBlank() }.associate {
                it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim()
            }
            val contentStart = headerEnd + 4
            val next = text.indexOf("\r\n$delimiter", contentStart).takeIf { it >= 0 } ?: return null
            parts += headers to text.substring(contentStart, next).toByteArray(Charsets.ISO_8859_1)
            pos = next + 2 + delimiter.length
        }
        return parts
    }

    private fun parseQuery(raw: String?): Map<String, String> = raw.orEmpty().split('&').filter { it.isNotEmpty() }.associate {
        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
    }

    private fun error(ex: HttpExchange, status: Int, reason: String, message: String, location: String? = null) {
        val domain = when (reason) {
            "rateLimitExceeded", "userRateLimitExceeded", "dailyLimitExceeded" -> "usageLimits"
            else -> "global"
        }
        val body = buildJsonObject {
            put("error", buildJsonObject {
                put("code", status)
                put("message", message)
                put("errors", JsonArray(listOf(buildJsonObject {
                    put("domain", domain)
                    put("reason", reason)
                    put("message", message)
                    if (location != null) { put("locationType", "header"); put("location", location) }
                })))
            })
        }
        json(ex, status, body.toString())
    }

    private fun json(ex: HttpExchange, status: Int, body: String, cacheControl: String = "private, max-age=0") {
        ex.responseHeaders.add("Content-Type", "application/json; charset=UTF-8")
        ex.responseHeaders.add("Cache-Control", cacheControl)
        send(ex, status, body.encodeToByteArray())
    }

    private fun send(ex: HttpExchange, status: Int, bytes: ByteArray) {
        ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    companion object {
        const val APP_DATA = "appDataFolder"
        const val MULTIPART_LIMIT = 5 * 1024 * 1024
        val DEFAULT_FILE_FIELDS = listOf("kind", "id", "name", "mimeType")

        fun defaultReason(status: Int) = when (status) {
            400 -> "badRequest"
            403 -> "userRateLimitExceeded"
            404 -> "notFound"
            429 -> "rateLimitExceeded"
            500 -> "backendError"
            else -> "backendError"
        }

        fun defaultMessage(status: Int, reason: String) = when (reason) {
            "userRateLimitExceeded" -> "User Rate Limit Exceeded"
            "rateLimitExceeded" -> "Rate Limit Exceeded"
            "dailyLimitExceeded" -> "Daily Limit Exceeded"
            "storageQuotaExceeded" -> "The user's Drive storage quota has been exceeded."
            else -> when (status) {
                502 -> "Bad Gateway"
                503 -> "Service Unavailable"
                504 -> "Gateway Timeout"
                else -> "Backend Error"
            }
        }
    }
}
