package io.waves.sync

/** OAuth scope for the hidden, app-private Drive folder. */
const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"

/**
 * Optional capability of a [GoogleAuth]: drop a cached access token the server rejected (HTTP 401), so the
 * next [GoogleAuth.accessToken] call fetches a fresh one.
 */
interface InvalidatableAuth {
    suspend fun invalidateAccessToken(token: String)
}

/** No account signed in (or access was revoked). */
class NotSignedInException(message: String = "Not signed in to Google") : Exception(message)

/** Google sign-in can't run because OAuth client ids weren't provided at build time. */
class AuthNotConfiguredException(message: String) : Exception(message)

/** Google OAuth / Drive returned an unexpected HTTP response. */
class GoogleApiException(val status: Int, message: String) : Exception(message)

/** The user signed in but unticked the Drive permission on Google's consent screen, so sync can't work. */
class DriveScopeNotGrantedException(
    message: String = "Drive access wasn't granted — please allow it to enable sync",
) : Exception(message)

/** True if a space-separated OAuth `scope` string (or a collection of scopes) includes [DRIVE_APPDATA_SCOPE]. */
fun hasDriveAppDataScope(scopes: Iterable<String>): Boolean = scopes.any { it.trim() == DRIVE_APPDATA_SCOPE }

fun hasDriveAppDataScope(scope: String?): Boolean = scope != null && hasDriveAppDataScope(scope.split(' '))

/**
 * Like [runCatching], but never swallows [kotlinx.coroutines.CancellationException]: a cancelled coroutine
 * must stop, not carry on as if a best-effort step merely failed.
 */
internal inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}
