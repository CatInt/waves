package io.waves.sync

import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthSupportTest {
    @Test
    fun detectsDriveScopeInSpaceSeparatedString() {
        assertTrue(hasDriveAppDataScope("openid $DRIVE_APPDATA_SCOPE email"))
        assertFalse(hasDriveAppDataScope("openid email https://www.googleapis.com/auth/drive.appdata.readonly"))
        assertFalse(hasDriveAppDataScope(null as String?))
        assertFalse(hasDriveAppDataScope(""))
    }

    @Test
    fun detectsDriveScopeInList() {
        assertTrue(hasDriveAppDataScope(listOf("email", DRIVE_APPDATA_SCOPE)))
        assertFalse(hasDriveAppDataScope(listOf("email", "openid")))
    }

    @Test
    fun runCatchingCancellableRethrowsCancellation() {
        assertFailsWith<CancellationException> { runCatchingCancellable<Unit> { throw CancellationException("stop") } }
        assertEquals("boom", runCatchingCancellable<Unit> { error("boom") }.exceptionOrNull()?.message)
        assertEquals(1, runCatchingCancellable { 1 }.getOrNull())
    }
}
