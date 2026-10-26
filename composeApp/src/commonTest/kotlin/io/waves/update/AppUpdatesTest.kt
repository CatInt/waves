package io.waves.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.waves.ui.UpdateState
import io.waves.ui.UpdateStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VersionTest {
    @Test
    fun parsesTagsAndComparesNumerically() {
        assertEquals(Version(1, 2, 3), Version.parse("v1.2.3"))
        assertEquals(Version(1, 10, 0), Version.parse("1.10.0"))
        assertTrue(Version.parse("1.10.0")!! > Version.parse("1.9.9")!!)
        assertNull(Version.parse("1.2"))
        assertNull(Version.parse("1.2.3-beta"))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdatesTest {
    private val releaseJson = """
        {"tag_name":"v1.2.0","html_url":"https://github.com/CatInt/waves/releases/tag/v1.2.0","body":"Notes",
         "draft":false,"prerelease":false,"assets":[
           {"name":"waves-1.2.0.apk","browser_download_url":"https://example.com/waves-1.2.0.apk","size":10},
           {"name":"Waves-1.2.0-arm64.dmg","browser_download_url":"https://example.com/Waves-1.2.0-arm64.dmg","size":20}]}
    """.trimIndent()

    private fun releases(status: HttpStatusCode = HttpStatusCode.OK, body: String = releaseJson) = GitHubReleases(
        HttpClient(MockEngine { req ->
            assertEquals("/repos/CatInt/waves/releases/latest", req.url.encodedPath)
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }),
    )

    private class FakeInstaller : UpdateInstaller {
        val installed = mutableListOf<Version>()
        override fun assetFor(release: AppRelease) = release.assets.firstOrNull { it.name.endsWith(".apk") }
        override suspend fun install(release: AppRelease, asset: ReleaseAsset, onProgress: (Float?) -> Unit) {
            onProgress(0.5f)
            installed += release.version
        }
    }

    @Test
    fun readsTheLatestRelease() = runTest {
        val release = releases().latest()!!
        assertEquals(Version(1, 2, 0), release.version)
        assertEquals(listOf("waves-1.2.0.apk", "Waves-1.2.0-arm64.dmg"), release.assets.map { it.name })
    }

    @Test
    fun noReleaseYetIsNotAnError() = runTest {
        assertNull(releases(HttpStatusCode.NotFound, """{"message":"Not Found"}""").latest())
    }

    private fun rateLimited(latestRedirect: String) = GitHubReleases(
        HttpClient(MockEngine { req ->
            when (req.url.encodedPath) {
                "/repos/CatInt/waves/releases/latest" -> respond("""{"message":"API rate limit exceeded"}""", HttpStatusCode.Forbidden)
                "/CatInt/waves/releases/latest" -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, latestRedirect))
                "/CatInt/waves/releases/tag/v1.3.0", "/CatInt/waves/releases" -> respond("")
                "/CatInt/waves/releases/expanded_assets/v1.3.0" -> respond(
                    """<a href="/CatInt/waves/releases/download/v1.3.0/Waves-1.3.0-arm64.dmg">dmg</a>
                       <a href="/CatInt/waves/releases/download/v1.3.0/waves-1.3.0.apk">apk</a>
                       <a href="/CatInt/waves/releases/download/v1.3.0/waves-1.3.0.apk" class="other">apk</a>
                       <a href="/CatInt/waves/archive/refs/tags/v1.3.0.zip">Source code</a>""",
                )
                else -> error("Unexpected request ${req.method.value} ${req.url}")
            }
        }),
    )

    @Test
    fun fallsBackToTheWebsiteWhenTheApiIsRateLimited() = runTest {
        val release = rateLimited("https://github.com/CatInt/waves/releases/tag/v1.3.0").latest()!!
        assertEquals(Version(1, 3, 0), release.version)
        assertEquals(
            listOf(
                ReleaseAsset("Waves-1.3.0-arm64.dmg", "https://github.com/CatInt/waves/releases/download/v1.3.0/Waves-1.3.0-arm64.dmg", 0),
                ReleaseAsset("waves-1.3.0.apk", "https://github.com/CatInt/waves/releases/download/v1.3.0/waves-1.3.0.apk", 0),
            ),
            release.assets,
        )
    }

    @Test
    fun websiteFallbackWithNoReleaseYetIsNotAnError() = runTest {
        assertNull(rateLimited("https://github.com/CatInt/waves/releases").latest())
    }

    @Test
    fun offersANewerReleaseAndInstallsIt() = runTest {
        val installer = FakeInstaller()
        val state = UpdateState(releases(), installer, backgroundScope, currentVersion = "1.1.9", autoCheck = false)
        state.check()
        val available = assertIs<UpdateStatus.Available>(state.status.first { it is UpdateStatus.Available })
        assertEquals("waves-1.2.0.apk", available.asset?.name)

        state.install()
        runCurrent()
        assertEquals(listOf(Version(1, 2, 0)), installer.installed)
    }

    @Test
    fun sameVersionIsUpToDate() = runTest {
        val state = UpdateState(releases(), FakeInstaller(), backgroundScope, currentVersion = "1.2.0", autoCheck = false)
        state.check()
        assertEquals(UpdateStatus.UpToDate, state.status.first { it != UpdateStatus.Idle && it != UpdateStatus.Checking })
    }

    @Test
    fun automaticCheckFailuresStaySilent() = runTest {
        val state = UpdateState(releases(HttpStatusCode.InternalServerError, "{}"), FakeInstaller(), backgroundScope, "1.0.0", autoCheck = true)
        advanceTimeBy(UpdateState.AUTO_CHECK_DELAY_MS + 1)
        runCurrent()
        state.status.first { it == UpdateStatus.Idle }

        state.check()
        assertIs<UpdateStatus.Failed>(state.status.first { it is UpdateStatus.Failed })
    }
}
