package io.waves.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DriveCloudStoreTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val requests = mutableListOf<Pair<HttpRequestData, String>>()

    private val rawBodies = mutableListOf<ByteArray>()

    /** Plain-JSON writes by default so bodies are readable; see the gzip tests for the default format. */
    private fun store(
        auth: GoogleAuth = FakeAuth(),
        compress: Boolean = false,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): DriveCloudStore {
        val engine = MockEngine { req ->
            val bytes = req.body.toByteArray()
            rawBodies += bytes
            requests += req to String(bytes)
            handler(req)
        }
        return DriveCloudStore(HttpClient(engine), auth, compress = compress)
    }

    private fun MockRequestHandleScope.list(vararg files: Pair<String, String>) = respond(
        files.joinToString(prefix = """{"files":[""", postfix = "]}") { (id, v) -> """{"id":"$id","name":"s.json","version":"$v"}""" },
        HttpStatusCode.OK, jsonHeaders,
    )

    private val HttpRequestData.isList get() = method == HttpMethod.Get && url.encodedPath == "/drive/v3/files"

    @Test
    fun readReturnsNullWhenAbsent() = runTest {
        val s = store { list() }
        assertNull(s.read("s.json"))
        val (req, _) = requests.single()
        assertEquals("appDataFolder", req.url.parameters["spaces"])
        assertEquals("name = 's.json' and trashed = false", req.url.parameters["q"])
        assertEquals("Bearer token-1", req.headers[HttpHeaders.Authorization])
    }

    @Test
    fun readDownloadsContentWithVersionAsRevision() = runTest {
        val s = store { req ->
            when {
                req.isList -> list("FILE1" to "17", "FILE2" to "3")
                req.url.encodedPath == "/drive/v3/files/FILE1" && req.url.parameters["alt"] == "media" ->
                    respond("""{"version":1}""", HttpStatusCode.OK, jsonHeaders)
                else -> error("unexpected ${req.url}")
            }
        }
        assertEquals("""{"version":1}""" to "17", s.read("s.json"))
    }

    @Test
    fun writeCreatesMultipartInAppDataFolder() = runTest {
        val s = store { req ->
            when {
                req.isList -> list()
                req.method == HttpMethod.Post && req.url.encodedPath == "/upload/drive/v3/files" ->
                    respond("""{"id":"NEW","version":"1"}""", HttpStatusCode.OK, jsonHeaders)
                else -> error("unexpected ${req.method} ${req.url}")
            }
        }
        s.write("s.json", """{"hello":1}""", ifRevision = null)
        val (req, body) = requests.last()
        assertEquals("multipart", req.url.parameters["uploadType"])
        assertTrue(req.body.contentType.toString().startsWith("multipart/related; boundary="))
        assertTrue(body.contains(""""name":"s.json""""), body)
        assertTrue(body.contains(""""parents":["appDataFolder"]"""), body)
        assertTrue(body.contains("""{"hello":1}"""), body)
    }

    @Test
    fun writeUpdatesWhenRevisionMatches() = runTest {
        val s = store { req ->
            when {
                req.isList -> list("ID" to "5")
                req.method == HttpMethod.Patch && req.url.encodedPath == "/upload/drive/v3/files/ID" ->
                    respond("""{"id":"ID","version":"6"}""", HttpStatusCode.OK, jsonHeaders)
                else -> error("unexpected ${req.method} ${req.url}")
            }
        }
        s.write("s.json", "content", ifRevision = "5")
        val (req, body) = requests.last()
        assertEquals("media", req.url.parameters["uploadType"])
        assertEquals("content", body)
    }

    @Test
    fun writeThrowsConflictOnVersionMismatch() = runTest {
        val s = store { req -> if (req.isList) list("ID" to "6") else error("must not upload") }
        assertFailsWith<ConflictException> { s.write("s.json", "c", ifRevision = "5") }
    }

    @Test
    fun createThrowsConflictWhenFileAppearedMeanwhile() = runTest {
        // ifRevision = null means the caller saw no file; another device created it since: merge, don't clobber.
        val s = store { req -> if (req.isList) list("ID" to "1") else error("must not upload") }
        assertFailsWith<ConflictException> { s.write("s.json", "c", ifRevision = null) }
        assertTrue(requests.all { it.first.isList })
    }

    @Test
    fun writeThrowsConflictWhenExpectedFileVanished() = runTest {
        val s = store { req -> if (req.isList) list() else error("must not upload") }
        assertFailsWith<ConflictException> { s.write("s.json", "c", ifRevision = "5") }
    }

    @Test
    fun refreshesTokenOnceOn401() = runTest {
        val auth = FakeAuth()
        val s = store(auth) { req ->
            if (req.headers[HttpHeaders.Authorization] == "Bearer token-1") respond("", HttpStatusCode.Unauthorized)
            else list()
        }
        assertNull(s.read("s.json"))
        assertEquals(listOf("token-1"), auth.invalidated)
        assertEquals(listOf("Bearer token-1", "Bearer token-2"), requests.map { it.first.headers[HttpHeaders.Authorization] })
    }

    @Test
    fun persistent401Fails() = runTest {
        val s = store { respond("", HttpStatusCode.Unauthorized) }
        val e = assertFailsWith<GoogleApiException> { s.read("s.json") }
        assertEquals(401, e.status)
        assertEquals(2, requests.size)
    }

    @Test
    fun signedOutThrows() = runTest {
        val s = store(FakeAuth(signedIn = false)) { error("no request expected") }
        assertFailsWith<NotSignedInException> { s.read("s.json") }
    }

    @Test
    fun serverErrorSurfacesAfterRetries() = runTest {
        val s = store { respond("boom", HttpStatusCode.InternalServerError) }
        assertEquals(500, assertFailsWith<GoogleApiException> { s.read("s.json") }.status)
        assertEquals(3, requests.size) // first attempt + 2 backoff retries
    }

    @Test
    fun transientErrorsAreRetriedWithBackoff() = runTest {
        var calls = 0
        val s = store { req ->
            calls++
            when (calls) {
                1 -> respond("", HttpStatusCode.TooManyRequests)
                2 -> respond("""{"error":{"errors":[{"domain":"usageLimits","reason":"userRateLimitExceeded"}],"code":403}}""", HttpStatusCode.Forbidden, jsonHeaders)
                else -> if (req.isList) list() else error("unexpected")
            }
        }
        assertNull(s.read("s.json"))
        assertEquals(3, calls)
    }

    @Test
    fun nonTransient403IsNotRetried() = runTest {
        val s = store {
            respond("""{"error":{"errors":[{"domain":"global","reason":"storageQuotaExceeded"}],"code":403}}""", HttpStatusCode.Forbidden, jsonHeaders)
        }
        assertEquals(403, assertFailsWith<GoogleApiException> { s.read("s.json") }.status)
        assertEquals(1, requests.size)
    }

    @Test
    fun createIsNotRetriedOnServerError() = runTest {
        // The file may have been created despite the 5xx; the next sync will find it instead of duplicating it.
        val s = store { req -> if (req.isList) list() else respond("", HttpStatusCode.ServiceUnavailable) }
        assertEquals(503, assertFailsWith<GoogleApiException> { s.write("s.json", "{}", ifRevision = null) }.status)
        assertEquals(1, requests.count { it.first.method == HttpMethod.Post })
    }

    @Test
    fun writesGzipByDefault() = runTest {
        val s = store(compress = true) { req ->
            when {
                req.isList && requests.none { it.first.method == HttpMethod.Post } -> list()
                req.isList -> list("ID" to "2")
                else -> respond("""{"id":"ID","version":"2"}""", HttpStatusCode.OK, jsonHeaders)
            }
        }
        s.write("s.json", """{"hello":"gzip"}""", ifRevision = null)
        val create = requests.last()
        assertTrue(create.second.contains(""""mimeType":"application/gzip""""), create.second)
        assertTrue(create.second.contains("Content-Type: application/gzip"), create.second)

        s.write("s.json", """{"hello":"again"}""", ifRevision = "2")
        val (patch, _) = requests.last()
        assertEquals(HttpMethod.Patch, patch.method)
        assertEquals("application/gzip", patch.body.contentType?.withoutParameters().toString())
        assertEquals("""{"hello":"again"}""", gunzip(rawBodies.last()).decodeToString())
    }

    @Test
    fun readsGzipAndPlainContent() = runTest {
        var payload = gzip("""{"z":1}""".encodeToByteArray())
        val s = store { req ->
            if (req.isList) list("ID" to "1") else respond(payload, HttpStatusCode.OK)
        }
        assertEquals("""{"z":1}""", s.read("s.json")?.first)
        payload = """{"plain":true}""".encodeToByteArray()
        assertEquals("""{"plain":true}""", s.read("s.json")?.first)
        payload = byteArrayOf(0x1f, 0x8b.toByte(), 1, 2, 3)
        assertFailsWith<SyncFileUnreadableException> { s.read("s.json") }
    }
}
