package io.waves.sync

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.URLBuilder
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import io.waves.currentTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.Desktop
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** OAuth "Desktop app" client credentials. For installed apps Google doesn't treat the secret as confidential. */
data class DesktopOAuthConfig(val clientId: String, val clientSecret: String) {
    val isConfigured: Boolean get() = clientId.isNotBlank()

    companion object {
        /** Values baked in at build time from local.properties (see docs/google-drive-setup.md). */
        val fromBuild = DesktopOAuthConfig(GoogleOAuthBuildConfig.DESKTOP_CLIENT_ID, GoogleOAuthBuildConfig.DESKTOP_CLIENT_SECRET)
    }
}

/**
 * Google sign-in for the desktop app: OAuth 2.0 installed-app flow with a loopback redirect and PKCE
 * (https://developers.google.com/identity/protocols/oauth2/native-app). The refresh token is persisted in
 * [tokenStore]; access tokens live in memory and are refreshed on demand.
 */
class DesktopGoogleAuth(
    private val client: HttpClient,
    private val config: DesktopOAuthConfig = DesktopOAuthConfig.fromBuild,
    private val tokenStore: TokenStore = TokenStore(),
    private val openBrowser: (String) -> Unit = ::openInSystemBrowser,
    private val receiverFactory: () -> LoopbackReceiver = { LoopbackReceiver() },
    private val clock: () -> Long = ::currentTimeMillis,
    private val authEndpoint: String = "https://accounts.google.com/o/oauth2/v2/auth",
    private val tokenEndpoint: String = "https://oauth2.googleapis.com/token",
    private val revokeEndpoint: String = "https://oauth2.googleapis.com/revoke",
) : GoogleAuth, InvalidatableAuth {

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var stored: StoredCredentials? = tokenStore.load()
    private var accessToken: String? = null
    private var accessTokenExpiresAt: Long = 0

    private val _state = MutableStateFlow(stored.toAuthState())
    override val state: StateFlow<AuthState> = _state.asStateFlow()

    @Serializable
    internal data class TokenResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("expires_in") val expiresIn: Long = 3600,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("id_token") val idToken: String? = null,
        /** Space-separated scopes actually granted; the user can untick Drive on the consent screen. */
        @SerialName("scope") val scope: String? = null,
    )

    override suspend fun signIn() {
        if (!config.isConfigured) {
            throw AuthNotConfiguredException(
                "Google sign-in is not configured for this build: set google.desktop.clientId and " +
                    "google.desktop.clientSecret in local.properties and rebuild (see docs/google-drive-setup.md)."
            )
        }
        val verifier = randomUrlSafe(64)
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        val expectedState = randomUrlSafe(24)

        receiverFactory().use { receiver ->
            val redirectUri = receiver.redirectUri
            val authUrl = URLBuilder(authEndpoint).apply {
                parameters.append("client_id", config.clientId)
                parameters.append("redirect_uri", redirectUri)
                parameters.append("response_type", "code")
                parameters.append("scope", "openid email $DRIVE_APPDATA_SCOPE")
                parameters.append("code_challenge", challenge)
                parameters.append("code_challenge_method", "S256")
                parameters.append("state", expectedState)
                // Ensure a refresh token is issued even if the user granted access before.
                parameters.append("access_type", "offline")
                parameters.append("prompt", "consent")
            }.buildString()
            openBrowser(authUrl)

            val callback = receiver.awaitCallback()
            callback.error?.let { throw IllegalStateException("Google sign-in was not completed: $it") }
            check(callback.state == expectedState) { "Google sign-in failed: state mismatch" }
            val code = checkNotNull(callback.code) { "Google sign-in failed: no authorization code" }

            val tokens = tokenRequest(
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to redirectUri,
                "code_verifier" to verifier,
            )
            if (!hasDriveAppDataScope(tokens.scope)) {
                // Don't keep a grant that can't sync; drop it so the next attempt asks again.
                revokeQuietly(tokens.refreshToken ?: tokens.accessToken)
                throw DriveScopeNotGrantedException()
            }
            val refresh = checkNotNull(tokens.refreshToken) { "Google did not return a refresh token" }
            mutex.withLock {
                val creds = StoredCredentials(refreshToken = refresh, email = tokens.idToken?.let(::emailFromIdToken))
                tokenStore.save(creds)
                stored = creds
                remember(tokens)
                _state.value = creds.toAuthState()
            }
        }
    }

    override suspend fun signOut() {
        val toRevoke = mutex.withLock {
            val token = stored?.refreshToken ?: accessToken
            stored = null
            accessToken = null
            tokenStore.clear()
            _state.value = AuthState.SignedOut
            token
        }
        // Best effort: revoking the refresh token removes the app's grant entirely.
        if (toRevoke != null) revokeQuietly(toRevoke)
    }

    private suspend fun revokeQuietly(token: String) {
        try {
            client.submitForm(revokeEndpoint, parameters { append("token", token) })
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best effort.
        }
    }

    override suspend fun accessToken(): String? = mutex.withLock {
        val creds = stored ?: return@withLock null
        accessToken?.let { if (clock() < accessTokenExpiresAt - 60_000) return@withLock it }
        try {
            val tokens = tokenRequest("grant_type" to "refresh_token", "refresh_token" to creds.refreshToken)
            remember(tokens)
            tokens.accessToken
        } catch (e: GoogleApiException) {
            if (e.status == 400 || e.status == 401) {
                // invalid_grant: the user revoked access or the token expired. Require a fresh sign-in.
                stored = null
                accessToken = null
                tokenStore.clear()
                _state.value = AuthState.SignedOut
                null
            } else throw e
        }
    }

    override suspend fun invalidateAccessToken(token: String) = mutex.withLock {
        if (accessToken == token) accessToken = null
    }

    private fun remember(tokens: TokenResponse) {
        accessToken = tokens.accessToken
        accessTokenExpiresAt = clock() + tokens.expiresIn * 1000
    }

    private suspend fun tokenRequest(vararg params: Pair<String, String>): TokenResponse {
        val response: HttpResponse = client.submitForm(tokenEndpoint, parameters {
            append("client_id", config.clientId)
            if (config.clientSecret.isNotBlank()) append("client_secret", config.clientSecret)
            params.forEach { (k, v) -> append(k, v) }
        })
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw GoogleApiException(response.status.value, "Google token request failed: HTTP ${response.status.value} ${body.take(300)}")
        }
        return json.decodeFromString(TokenResponse.serializer(), body)
    }

    private fun StoredCredentials?.toAuthState(): AuthState = if (this == null) AuthState.SignedOut else AuthState.SignedIn(email)

    internal companion object {
        private val random = SecureRandom()

        fun randomUrlSafe(bytes: Int): String = base64Url(ByteArray(bytes).also(random::nextBytes))

        fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        /** Reads the `email` claim; the token came straight from Google over TLS, so no signature check needed. */
        fun emailFromIdToken(idToken: String): String? = runCatching {
            val payload = String(Base64.getUrlDecoder().decode(idToken.split('.')[1].trimEnd('=')), Charsets.UTF_8)
            Json.parseToJsonElement(payload).jsonObject["email"]?.jsonPrimitive?.content
        }.getOrNull()
    }
}

/** Opens [url] in the default browser (java.awt.Desktop, falling back to macOS `open`). */
fun openInSystemBrowser(url: String) {
    val desktop = if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
    if (desktop != null && desktop.isSupported(Desktop.Action.BROWSE)) {
        desktop.browse(URI(url))
    } else {
        ProcessBuilder("open", url).start()
    }
}
