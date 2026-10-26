package io.waves.sync

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Google sign-in on Android via Google Identity Services' [com.google.android.gms.auth.api.identity.AuthorizationClient].
 *
 * No client id is needed in code: Google matches the app by package name + signing certificate SHA-1 registered as
 * an "Android" OAuth client (docs/google-drive-setup.md). Tokens are cached and refreshed by Play services, so
 * [accessToken] simply calls `authorize()` silently.
 *
 * Consent UI hand-off: when user consent is required, Play services returns a PendingIntent that must be started
 * from an Activity. [signIn] launches the transparent [GoogleAuthConsentActivity], which runs it through the
 * Activity Result API and reports back — so callers need no Activity wiring at all (works from any Context).
 * Create one instance per process (e.g. in the Application / DI graph) with the application context.
 */
class AndroidGoogleAuth(
    context: Context,
    private val httpClient: HttpClient,
) : GoogleAuth, InvalidatableAuth {

    private val appContext = context.applicationContext
    private val client = Identity.getAuthorizationClient(appContext)
    private val prefs = appContext.getSharedPreferences("waves_google_auth", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        if (prefs.getBoolean(KEY_SIGNED_IN, false)) AuthState.SignedIn(prefs.getString(KEY_EMAIL, null)) else AuthState.SignedOut
    )
    override val state: StateFlow<AuthState> = _state.asStateFlow()

    private val request = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(DRIVE_APPDATA_SCOPE), Scope("email")))
        .build()

    override suspend fun signIn() {
        var result = client.authorize(request).await()
        if (result.hasResolution()) {
            val pendingIntent = checkNotNull(result.pendingIntent) { "Google authorization needs consent but gave no intent" }
            val activityResult = GoogleAuthConsentActivity.launch(appContext, IntentSenderRequest.Builder(pendingIntent).build())
            // A failed consent UI comes back as RESULT_CANCELED with the status still in the intent
            // (for example UNREGISTERED_ON_API_CONSOLE). Reading it only on RESULT_OK hid that as "cancelled".
            result = authorizationResultFrom(activityResult)
        }
        val token = checkNotNull(result.accessToken) { "Google sign-in returned no access token" }
        // The consent screen lets the user untick Drive while still granting email; sync can't work then.
        if (!hasDriveAppDataScope(result.grantedScopes)) {
            runCatchingCancellable { client.clearToken(ClearTokenRequest.builder().setToken(token).build()).await() }
            throw DriveScopeNotGrantedException()
        }
        val email = fetchEmail(token)
        prefs.edit().putBoolean(KEY_SIGNED_IN, true).putString(KEY_EMAIL, email).apply()
        _state.value = AuthState.SignedIn(email)
    }

    override suspend fun signOut() {
        val token = if (state.value is AuthState.SignedIn) runCatchingCancellable { silentToken() }.getOrNull() else null
        prefs.edit().clear().apply()
        _state.value = AuthState.SignedOut
        if (token != null) {
            runCatchingCancellable { client.clearToken(ClearTokenRequest.builder().setToken(token).build()).await() }
            // Revoke the grant so the next sign-in shows the consent screen again (best effort).
            runCatchingCancellable { httpClient.submitForm("https://oauth2.googleapis.com/revoke", parameters { append("token", token) }) }
        }
    }

    override suspend fun accessToken(): String? {
        if (state.value !is AuthState.SignedIn) return null
        return silentToken() ?: run {
            // Access was revoked (e.g. from the Google account page): consent is needed again.
            prefs.edit().clear().apply()
            _state.value = AuthState.SignedOut
            null
        }
    }

    override suspend fun invalidateAccessToken(token: String) {
        runCatchingCancellable { client.clearToken(ClearTokenRequest.builder().setToken(token).build()).await() }
    }

    private fun authorizationResultFrom(activityResult: ActivityResult): AuthorizationResult {
        val data = activityResult.data
        if (data != null) {
            try {
                return client.getAuthorizationResultFromIntent(data)
            } catch (e: ApiException) {
                throw googleSignInFailure(
                    e.statusCode,
                    e.message,
                    appContext.packageName,
                    signingCertificateSha1(appContext),
                )
            }
        }
        if (activityResult.resultCode != Activity.RESULT_OK) throw IllegalStateException("Google sign-in was cancelled")
        error("Google sign-in returned no result")
    }

    private suspend fun silentToken(): String? {
        val result: AuthorizationResult = client.authorize(request).await()
        return if (result.hasResolution()) null else result.accessToken
    }

    private suspend fun fetchEmail(token: String): String? = runCatchingCancellable {
        val response = httpClient.get("https://openidconnect.googleapis.com/v1/userinfo") { bearerAuth(token) }
        if (!response.status.isSuccess()) return@runCatchingCancellable null
        Json.parseToJsonElement(response.bodyAsText()).jsonObject["email"]?.jsonPrimitive?.content
    }.getOrNull()

    private companion object {
        const val KEY_SIGNED_IN = "signed_in"
        const val KEY_EMAIL = "email"
    }
}

/**
 * Maps a Play services authorization failure. Status 10 and `UNREGISTERED_ON_API_CONSOLE` mean no Android
 * OAuth client matches this package and signing certificate.
 */
internal fun googleSignInFailure(statusCode: Int, statusMessage: String?, packageName: String, sha1: String): Exception {
    val message = statusMessage.orEmpty()
    val unregistered = statusCode == CommonStatusCodes.DEVELOPER_ERROR ||
        message.contains("UNREGISTERED", ignoreCase = true) ||
        message.contains("API_CONSOLE", ignoreCase = true)
    if (unregistered) {
        return AuthNotConfiguredException(
            "Google sign-in isn't registered for this build. Create an Android OAuth client for " +
                "package $packageName with SHA-1:\n$sha1\nSee docs/google-drive-setup.md.",
        )
    }
    val detail = listOf(statusCode.toString(), message).filter { it.isNotBlank() }.joinToString(" ")
    return IllegalStateException("Google sign-in failed: $detail")
}

/** SHA-1 of the certificate that signed this install, in the form Google Cloud expects (`AA:BB:...`). */
internal fun signingCertificateSha1(context: Context): String {
    val certs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val signing = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_SIGNING_CERTIFICATES,
        ).signingInfo
        when {
            signing == null -> emptyArray()
            signing.hasMultipleSigners() -> signing.apkContentsSigners
            else -> signing.signingCertificateHistory
        }
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
            ?: emptyArray()
    }
    val cert = certs.firstOrNull() ?: return "unknown"
    val digest = MessageDigest.getInstance("SHA-1").digest(cert.toByteArray())
    return digest.joinToString(":") { "%02X".format(it) }
}

/**
 * Transparent trampoline that shows Google's consent UI for [AndroidGoogleAuth] and hands the result back.
 * Declared in AndroidManifest.xml (not exported).
 */
class GoogleAuthConsentActivity : ComponentActivity() {
    /** The sign-in attempt this activity serves; a later concurrent attempt never receives our result. */
    private var attempt: Attempt? = null

    private val launcher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        attempt?.deferred?.complete(result)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val current = Companion.current
        if (current == null || current.deferred.isCompleted) {
            finish()
            return
        }
        attempt = current
        if (savedInstanceState == null) launcher.launch(current.request)
    }

    override fun onDestroy() {
        // Back-pressed / process teardown without a result: resolve as cancelled so signIn() doesn't hang.
        if (isFinishing && !isChangingConfigurations) {
            attempt?.deferred?.complete(ActivityResult(Activity.RESULT_CANCELED, null))
        }
        super.onDestroy()
    }

    private class Attempt(val request: IntentSenderRequest, val deferred: CompletableDeferred<ActivityResult>)

    internal companion object {
        @Volatile private var current: Attempt? = null

        suspend fun launch(context: Context, request: IntentSenderRequest): ActivityResult {
            val attempt = Attempt(request, CompletableDeferred())
            // Supersede an older attempt still waiting for its consent screen.
            current?.deferred?.complete(ActivityResult(Activity.RESULT_CANCELED, null))
            current = attempt
            context.startActivity(
                Intent(context, GoogleAuthConsentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return try {
                attempt.deferred.await()
            } finally {
                // Only clear our own attempt: a newer one may already be waiting for its activity's onCreate.
                if (current === attempt) current = null
            }
        }
    }
}

/** Awaits a Play services [Task] without pulling in kotlinx-coroutines-play-services. */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
