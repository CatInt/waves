package io.waves.sync

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random

/**
 * [CloudStore] backed by the Google Drive v3 REST API, confined to the hidden `appDataFolder` space.
 *
 * The revision tag is the Drive file `version` (a monotonically increasing counter bumped on every change).
 * Drive has no conditional write, so [write] re-checks the file (existence for a create, version for an update)
 * right before uploading; the remaining window is tiny and a lost race only delays convergence (the losing
 * device's data stays local and is merged next sync).
 * If duplicates with the same name ever appear, every device consistently picks the oldest one.
 *
 * Transient Drive errors (429, 403 `rateLimitExceeded`/`userRateLimitExceeded`, 5xx) are retried after
 * [retryDelaysMs] (plus jitter, or the server's `Retry-After` if longer), as the Drive error guide recommends.
 * A failed *create* is only retried for rate limits: after a 5xx the file may exist anyway, and the next sync
 * would find it, whereas a blind retry could create a duplicate.
 *
 * Content is stored gzip-compressed ([compress], `application/gzip`): a large library is ~4 MB of JSON, close to
 * Drive's 5 MB limit for simple/multipart uploads, and compresses ~20x. Reads accept both gzip (detected by
 * its magic number) and plain UTF-8 JSON as written by earlier builds.
 */
class DriveCloudStore(
    private val client: HttpClient,
    private val auth: GoogleAuth,
    private val apiBase: String = "https://www.googleapis.com",
    private val retryDelaysMs: List<Long> = listOf(1_000, 4_000),
    /** Write gzip (default). False writes plain JSON, as builds before compression did. */
    private val compress: Boolean = true,
) : CloudStore {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    internal data class DriveFile(val id: String, val name: String? = null, val version: String? = null)

    @Serializable
    internal data class FileList(val files: List<DriveFile> = emptyList())

    override suspend fun read(name: String): Pair<String, String>? {
        val file = find(name) ?: return null
        val response = call(HttpMethod.Get, "$apiBase/drive/v3/files/${file.id}") {
            url.parameters.append("alt", "media")
        }
        if (response.status == HttpStatusCode.NotFound) return null
        response.ensureSuccess("download")
        return decodeContent(response.body<ByteArray>()) to (file.version ?: "")
    }

    private fun decodeContent(bytes: ByteArray): String {
        if (!isGzip(bytes)) return bytes.decodeToString()
        val inflated = try {
            gunzip(bytes)
        } catch (e: Exception) {
            throw SyncFileUnreadableException("corrupt gzip data (${e.message})", e)
        }
        return inflated.decodeToString()
    }

    private fun encodeContent(content: String): Pair<ByteArray, ContentType> =
        if (compress) gzip(content.encodeToByteArray()) to GZIP
        else content.encodeToByteArray() to ContentType.Application.Json.withParameter("charset", "UTF-8")

    override suspend fun write(name: String, content: String, ifRevision: String?) {
        // Re-check right before uploading: Drive has no conditional write.
        val existing = find(name)
        when {
            ifRevision == null && existing != null ->
                // Another device created the file since our read: merge with it instead of clobbering it.
                throw ConflictException("Remote $name was created meanwhile (revision ${existing.version})")
            ifRevision == null -> create(name, content)
            existing == null -> throw ConflictException("Expected revision $ifRevision of $name but it no longer exists")
            existing.version != ifRevision ->
                throw ConflictException("Remote $name is at revision ${existing.version}, expected $ifRevision")
            else -> update(existing.id, content)
        }
    }

    private suspend fun find(name: String): DriveFile? {
        val response = call(HttpMethod.Get, "$apiBase/drive/v3/files") {
            url.parameters.apply {
                append("spaces", "appDataFolder")
                append("q", "name = '${name.replace("\\", "\\\\").replace("'", "\\'")}' and trashed = false")
                append("fields", "files(id,name,version)")
                append("orderBy", "createdTime")
                append("pageSize", "10")
            }
        }
        response.ensureSuccess("list")
        return json.decodeFromString<FileList>(response.bodyAsText()).files.firstOrNull()
    }

    private suspend fun create(name: String, content: String) {
        val (media, mediaType) = encodeContent(content)
        val boundary = "waves-" + Random.nextLong().toULong().toString(16)
        val metadata = """{"name":${JsonPrimitive(name)},"parents":["appDataFolder"],"mimeType":${JsonPrimitive(mediaType.withoutParameters().toString())}}"""
        val head = buildString {
            append("--").append(boundary).append("\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(metadata).append("\r\n")
            append("--").append(boundary).append("\r\n")
            append("Content-Type: ").append(mediaType.toString()).append("\r\n\r\n")
        }
        val tail = "\r\n--$boundary--\r\n"
        val body = head.encodeToByteArray() + media + tail.encodeToByteArray()
        val response = call(HttpMethod.Post, "$apiBase/upload/drive/v3/files", retryServerErrors = false) {
            url.parameters.append("uploadType", "multipart")
            url.parameters.append("fields", "id,version")
            setBody(ByteArrayContent(body, ContentType.parse("multipart/related; boundary=$boundary")))
        }
        response.ensureSuccess("create")
    }

    private suspend fun update(fileId: String, content: String) {
        val (media, mediaType) = encodeContent(content)
        val response = call(HttpMethod.Patch, "$apiBase/upload/drive/v3/files/$fileId") {
            url.parameters.append("uploadType", "media")
            url.parameters.append("fields", "id,version")
            setBody(ByteArrayContent(media, mediaType))
        }
        response.ensureSuccess("update")
    }

    /** [authorized], retrying transient failures (see the class doc). */
    private suspend fun call(
        method: HttpMethod,
        url: String,
        retryServerErrors: Boolean = true,
        block: HttpRequestBuilder.() -> Unit,
    ): HttpResponse {
        var attempt = 0
        while (true) {
            val response = authorized(method, url, block)
            val backoff = retryDelaysMs.getOrNull(attempt++) ?: return response
            if (!response.isTransient(retryServerErrors)) return response
            val retryAfterMs = response.headers[HttpHeaders.RetryAfter]?.trim()?.toLongOrNull()?.times(1_000)
            delay(maxOf(backoff + Random.nextLong(backoff / 2 + 1), minOf(retryAfterMs ?: 0, MAX_RETRY_AFTER_MS)))
        }
    }

    private suspend fun HttpResponse.isTransient(retryServerErrors: Boolean): Boolean = when (status.value) {
        429 -> true
        500, 502, 503, 504 -> retryServerErrors
        403 -> runCatchingCancellable { bodyAsText() }.getOrDefault("").let {
            "\"rateLimitExceeded\"" in it || "\"userRateLimitExceeded\"" in it
        }
        else -> false
    }

    /** Sends a request with a bearer token; on 401 invalidates the token and retries exactly once. */
    private suspend fun authorized(
        method: HttpMethod,
        url: String,
        block: HttpRequestBuilder.() -> Unit,
    ): HttpResponse {
        suspend fun send(token: String) = client.request {
            this.method = method
            this.url.takeFrom(url)
            bearerAuth(token)
            block()
        }
        val token = auth.accessToken() ?: throw NotSignedInException()
        val first = send(token)
        if (first.status != HttpStatusCode.Unauthorized) return first
        (auth as? InvalidatableAuth)?.invalidateAccessToken(token)
        val fresh = auth.accessToken() ?: throw NotSignedInException("Google session expired; sign in again")
        return send(fresh)
    }

    private suspend fun HttpResponse.ensureSuccess(operation: String) {
        if (status.isSuccess()) return
        val detail = runCatchingCancellable { bodyAsText() }.getOrDefault("").take(500)
        throw GoogleApiException(status.value, "Drive $operation failed: HTTP ${status.value} $detail")
    }

    private companion object {
        const val MAX_RETRY_AFTER_MS = 30_000L
        val GZIP = ContentType("application", "gzip")
    }
}
