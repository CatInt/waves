package io.waves.player

import io.waves.model.Episode
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Manual smoke test against real podcast CDNs; skipped unless WAVES_PLAYER_SMOKE=1:
 *
 *   WAVES_PLAYER_SMOKE=1 ./gradlew :composeApp:desktopTest --tests '*DesktopPlayerSmokeTest*' --rerun -i
 *
 * Optional: WAVES_PLAYER_SMOKE_URL=<enclosure url> to try a specific episode,
 * WAVES_PLAYER_SMOKE_AWT=1 to boot AWT first (like the Compose app does) before JavaFX.
 */
class DesktopPlayerSmokeTest {
    private val urls = System.getenv("WAVES_PLAYER_SMOKE_URL")?.let { listOf(it) } ?: listOf(
        // MP3 behind a swap.fm -> spotify prefix -> podtrac -> simplecast redirect chain (NPR Planet Money).
        "https://tracking.swap.fm/track/XvDEoI11TR00olTUO8US/prfx.byspotify.com/e/play.podtrac.com/npr-510289/npr.simplecastaudio.com/43b5acee-463e-4612-95ad-d2596d9dd337/episodes/080b75be-92f2-4af7-b874-89008175bcd2/audio/128/default.mp3",
        // AAC/M4A behind an anchor.fm redirect.
        "https://anchor.fm/s/8831168/podcast/play/127600778/https%3A%2F%2Fd3ctxlq1ktw2nl.cloudfront.net%2Fstaging%2F2026-9-17%2F434341789-44100-2-bc3bd56b2b4b5.m4a",
    )

    @Test
    fun streamsSeeksAndAdvances() {
        if (System.getenv("WAVES_PLAYER_SMOKE") != "1") return
        useEnvProxy()
        // Native-lib extraction dir; defaults to ~/.openjfx/cache, which may be read-only in sandboxes.
        System.getenv("WAVES_PLAYER_SMOKE_FXCACHE")?.let { System.setProperty("javafx.cachedir", it) }
        if (System.getenv("WAVES_PLAYER_SMOKE_AWT") == "1") {
            java.awt.EventQueue.invokeAndWait { java.awt.Toolkit.getDefaultToolkit() }
        }
        for (url in urls) smoke(url)
    }

    /** Honors HTTPS_PROXY (with credentials) in sandboxed/CI environments; the JDK ignores it by default. */
    private fun useEnvProxy() {
        val proxy = System.getenv("HTTPS_PROXY") ?: System.getenv("https_proxy") ?: return
        val uri = java.net.URI(proxy)
        for (scheme in listOf("http", "https")) {
            System.setProperty("$scheme.proxyHost", uri.host)
            System.setProperty("$scheme.proxyPort", uri.port.toString())
        }
        System.setProperty("http.nonProxyHosts", "localhost|127.0.0.1")
        val userInfo = uri.userInfo ?: return
        System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "")
        System.setProperty("jdk.http.auth.proxying.disabledSchemes", "")
        val (user, pass) = userInfo.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        java.net.Authenticator.setDefault(object : java.net.Authenticator() {
            override fun getPasswordAuthentication() =
                if (requestorType == RequestorType.PROXY) java.net.PasswordAuthentication(user, pass.toCharArray()) else null
        })
    }

    private fun smoke(url: String) {
        println("=== $url")
        println("resolved -> ${RedirectResolver.resolve(url)}")
        val player = DesktopAudioPlayer()
        val episode = Episode(guid = "smoke", podcastFeedUrl = "https://example.com/feed", title = "Smoke", audioUrl = url)
        try {
            player.play(episode)
            val firstPos = awaitPosition(player, minMs = 500, timeoutMs = 20_000)
            sample(player, seconds = 5)
            val beforeSeek = player.state.value.positionMs
            assertTrue(beforeSeek > firstPos, "position should advance: $firstPos -> $beforeSeek")

            player.setSpeed(1.5f)
            player.seekTo(60_000)
            Thread.sleep(500)
            sample(player, seconds = 3)
            val afterSeek = player.state.value.positionMs
            assertTrue(afterSeek in 60_500..70_000, "after seek to 60s position should be ~61-65s, was $afterSeek")

            player.pause()
            Thread.sleep(700)
            val paused = player.state.value.positionMs
            Thread.sleep(1_000)
            println("paused at $paused -> ${player.state.value.positionMs}, state=${player.state.value}")
            assertTrue(player.state.value.positionMs - paused < 300, "position must not advance while paused")
            assertNull(player.state.value.error)
        } finally {
            player.release()
        }
    }

    private fun awaitPosition(player: AudioPlayer, minMs: Long, timeoutMs: Long): Long {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val s = player.state.value
            check(s.error == null) { "playback error: ${s.error}" }
            if (s.positionMs >= minMs && !s.isBuffering) return s.positionMs
            Thread.sleep(100)
        }
        error("playback did not start within ${timeoutMs}ms: ${player.state.value}")
    }

    private fun sample(player: AudioPlayer, seconds: Int) = repeat(seconds * 2) {
        val s = player.state.value
        println("  pos=${s.positionMs}ms dur=${s.durationMs}ms playing=${s.isPlaying} buffering=${s.isBuffering} speed=${s.speed}")
        Thread.sleep(500)
    }
}
