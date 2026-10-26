package io.waves.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.Base64
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopGoogleAuthTest {
    private val dir = Files.createTempDirectory("waves-auth-test").toFile()
    private val tokenFile = File(dir, "Waves/google-auth.json")
    private val config = DesktopOAuthConfig("client-id", "client-secret")
    private val tokenRequests = mutableListOf<Map<String, String>>()
    private var now = 1_000_000L

    @AfterTest
    fun cleanup() { dir.deleteRecursively() }

    private fun idToken(email: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        return enc.encodeToString("{}".toByteArray()) + "." + enc.encodeToString("""{"email":"$email"}""".toByteArray()) + ".sig"
    }

    private fun client(
        tokenStatus: HttpStatusCode = HttpStatusCode.OK,
        grantedScope: String = "openid https://www.googleapis.com/auth/userinfo.email $DRIVE_APPDATA_SCOPE",
    ) = HttpClient(MockEngine { req ->
        val form = parseQueryString(String(req.body.toByteArray()))
        tokenRequests += form.names().associateWith { form[it]!! }
        when (req.url.toString()) {
            "https://oauth2.googleapis.com/token" -> respond(
                if (tokenStatus != HttpStatusCode.OK) """{"error":"invalid_grant"}"""
                else if (form["grant_type"] == "authorization_code")
                    """{"access_token":"at-1","expires_in":3600,"refresh_token":"rt-1","scope":"$grantedScope","id_token":"${idToken("me@example.com")}"}"""
                else """{"access_token":"at-${tokenRequests.size}","expires_in":3600}""",
                tokenStatus, headersOf(HttpHeaders.ContentType, "application/json"),
            )
            "https://oauth2.googleapis.com/revoke" -> respond("", HttpStatusCode.OK)
            else -> error("unexpected ${req.url}")
        }
    })

    /** Simulates the user's browser: consent instantly and follow the redirect to the loopback server. */
    private fun fakeBrowser(tamperState: Boolean = false): (String) -> Unit = { url ->
        val q = Url(url).parameters
        val state = if (tamperState) "evil" else q["state"]!!
        thread {
            val conn = URI("${q["redirect_uri"]}/?code=the-code&state=$state").toURL().openConnection() as HttpURLConnection
            conn.responseCode
            conn.disconnect()
        }
        lastAuthUrl = url
    }
    private var lastAuthUrl: String? = null

    private fun auth(client: HttpClient = client(), browser: (String) -> Unit = fakeBrowser(), cfg: DesktopOAuthConfig = config) =
        DesktopGoogleAuth(client, cfg, TokenStore(tokenFile), browser, clock = { now })

    @Test
    fun notConfiguredGivesClearError() = runBlocking {
        val e = assertFailsWith<AuthNotConfiguredException> { auth(cfg = DesktopOAuthConfig("", "")).signIn() }
        assertTrue(e.message!!.contains("google.desktop.clientId"))
    }

    @Test
    fun fullSignInFlowWithPkce() = runBlocking {
        val a = auth()
        withContext(Dispatchers.IO) { a.signIn() }

        val q = Url(lastAuthUrl!!).parameters
        assertEquals("client-id", q["client_id"])
        assertEquals("S256", q["code_challenge_method"])
        assertTrue(q["scope"]!!.contains(DRIVE_APPDATA_SCOPE))
        assertTrue(q["redirect_uri"]!!.startsWith("http://127.0.0.1:"))

        val exchange = tokenRequests.single()
        assertEquals("authorization_code", exchange["grant_type"])
        assertEquals("the-code", exchange["code"])
        assertEquals("client-secret", exchange["client_secret"])
        val expectedChallenge = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(exchange["code_verifier"]!!.toByteArray()))
        assertEquals(expectedChallenge, q["code_challenge"])

        assertEquals(AuthState.SignedIn("me@example.com"), a.state.value)
        assertEquals("at-1", a.accessToken())
        assertEquals(StoredCredentials("rt-1", "me@example.com"), TokenStore(tokenFile).load())
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(tokenFile.toPath())))
    }

    @Test
    fun signInFailsWhenDriveScopeNotGranted() = runBlocking {
        val a = auth(client(grantedScope = "openid https://www.googleapis.com/auth/userinfo.email"))
        val e = assertFailsWith<DriveScopeNotGrantedException> { withContext(Dispatchers.IO) { a.signIn() } }
        assertEquals("Drive access wasn't granted — please allow it to enable sync", e.message)
        assertIs<AuthState.SignedOut>(a.state.value)
        assertNull(TokenStore(tokenFile).load())
        assertNull(a.accessToken())
    }

    @Test
    fun stateMismatchRejected() = runBlocking {
        val a = auth(browser = fakeBrowser(tamperState = true))
        assertFailsWith<IllegalStateException> { withContext(Dispatchers.IO) { a.signIn() } }
        assertIs<AuthState.SignedOut>(a.state.value)
        assertTrue(tokenRequests.isEmpty())
    }

    @Test
    fun restoresSessionAndRefreshesAccessToken() = runBlocking {
        TokenStore(tokenFile).save(StoredCredentials("rt-saved", "x@y.z"))
        val a = auth()
        assertEquals(AuthState.SignedIn("x@y.z"), a.state.value)

        val t1 = a.accessToken()
        assertEquals("refresh_token", tokenRequests.single()["grant_type"])
        assertEquals("rt-saved", tokenRequests.single()["refresh_token"])
        assertEquals(t1, a.accessToken(), "cached while valid")

        now += 3600_000
        val t2 = a.accessToken()
        assertTrue(t1 != t2, "refreshed after expiry")

        a.invalidateAccessToken(t2!!)
        assertTrue(a.accessToken() != t2, "refreshed after invalidation")
        assertEquals(3, tokenRequests.size)
    }

    @Test
    fun invalidGrantSignsOut() = runBlocking {
        TokenStore(tokenFile).save(StoredCredentials("rt-revoked"))
        val a = auth(client(HttpStatusCode.BadRequest))
        assertNull(a.accessToken())
        assertIs<AuthState.SignedOut>(a.state.value)
        assertNull(TokenStore(tokenFile).load())
    }

    @Test
    fun signOutClearsAndRevokes() = runBlocking {
        TokenStore(tokenFile).save(StoredCredentials("rt-saved"))
        val a = auth()
        a.signOut()
        assertIs<AuthState.SignedOut>(a.state.value)
        assertNull(a.accessToken())
        assertTrue(!tokenFile.exists())
        assertEquals("rt-saved", tokenRequests.single()["token"])
    }

    @Test
    fun loopbackParsing() {
        assertEquals(LoopbackReceiver.Callback("c", "s", null), LoopbackReceiver.parseRequestLine("GET /?code=c&state=s HTTP/1.1"))
        assertEquals(LoopbackReceiver.Callback(null, "s", "access_denied"), LoopbackReceiver.parseRequestLine("GET /?error=access_denied&state=s HTTP/1.1"))
        assertNull(LoopbackReceiver.parseRequestLine("GET /favicon.ico HTTP/1.1"))
        assertNull(LoopbackReceiver.parseRequestLine(""))
    }
}
