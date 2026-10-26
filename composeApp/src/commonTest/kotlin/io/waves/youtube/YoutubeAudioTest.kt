package io.waves.youtube

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class YoutubeAudioTest {
    @Test
    fun picksTheHighestBitrateAacAndSkipsVideoAndOpus() {
        val choice = YoutubeAudio.interpret(
            """
            {
              "playabilityStatus": {"status": "OK"},
              "streamingData": {
                "formats": [{"itag": 18, "mimeType": "video/mp4", "bitrate": 500000, "url": "https://rr.googlevideo.com/videoplayback?id=v"}],
                "adaptiveFormats": [
                  {"itag": 251, "mimeType": "audio/webm; codecs=\"opus\"", "bitrate": 160000, "url": "https://rr.googlevideo.com/videoplayback?id=opus"},
                  {"itag": 140, "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"", "bitrate": 128000, "url": "https://rr.googlevideo.com/videoplayback?id=aac"},
                  {"itag": 139, "mimeType": "audio/mp4; codecs=\"mp4a.40.5\"", "bitrate": 48000, "url": "https://rr.googlevideo.com/videoplayback?id=low"},
                  {"itag": 141, "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"", "bitrate": 256000, "signatureCipher": "s=abc"}
                ]
              }
            }
            """.trimIndent(),
        )
        val stream = assertIs<AudioChoice.Stream>(choice)
        assertEquals("https://rr.googlevideo.com/videoplayback?id=aac", stream.url)
    }

    @Test
    fun signInWallIsAPlaybackError() {
        val choice = YoutubeAudio.interpret("""{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in to confirm you’re not a bot"}}""")
        val unavailable = assertIs<AudioChoice.Unavailable>(choice)
        assertEquals("LOGIN_REQUIRED", unavailable.status)
        assertTrue(YoutubeAudio.audioFailureMessage(unavailable).contains("sign-in"))
        assertTrue(YoutubeAudio.audioFailureMessage(unavailable).contains("VPN"))
    }

    @Test
    fun okWithoutAacExplainsThat() {
        val choice = YoutubeAudio.interpret(
            """{"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[{"mimeType":"audio/webm","bitrate":1,"url":"https://rr.googlevideo.com/a"}]}}""",
        )
        val unavailable = assertIs<AudioChoice.Unavailable>(choice)
        assertEquals("This video has no audio stream that can be played here.", YoutubeAudio.audioFailureMessage(unavailable))
    }

    @Test
    fun locatorFallsBackInOrderWhenYouTubeAsksForSignIn() = runTest {
        val tried = mutableListOf<String>()
        val locator = YoutubeMediaLocator(
            signInWall(),
            fallbacks = listOf(
                YoutubeAudioSource { tried += "absent"; null },
                YoutubeAudioSource { tried += "broken"; throw YoutubeException("yt-dlp: nope.") },
                YoutubeAudioSource { tried += "works"; "https://stream.example/a.m4a" },
                YoutubeAudioSource { tried += "never"; "https://unused" },
            ),
        )
        assertEquals("https://stream.example/a.m4a", locator.locate(YoutubeUrls.watchUrl(VIDEO)))
        assertEquals(listOf("absent", "broken", "works"), tried)
        assertEquals("https://feeds.example/ep.mp3", locator.locate("https://feeds.example/ep.mp3"))
    }

    @Test
    fun failureCombinesTheBuiltInAndFallbackMessages() = runTest {
        val unset = YoutubeMediaLocator(signInWall(), listOf(YoutubeAudioSource { null }))
        val e1 = assertFailsWith<YoutubeException> { unset.locate(YoutubeUrls.watchUrl(VIDEO)) }
        assertTrue(e1.message!!.contains("sign-in check"), e1.message)

        val failing = YoutubeMediaLocator(
            signInWall(),
            listOf(YoutubeAudioSource { throw YoutubeException("yt-dlp: Sign in to confirm you’re not a bot.") }),
        )
        val e2 = assertFailsWith<YoutubeException> { failing.locate(YoutubeUrls.watchUrl(VIDEO)) }
        assertTrue(e2.message!!.endsWith("yt-dlp: Sign in to confirm you’re not a bot."), e2.message)
    }

    private fun signInWall() = HttpClient(MockEngine) {
        engine {
            addHandler {
                respond(
                    """{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in to confirm you’re not a bot"}}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
    }

    private companion object {
        const val VIDEO = "sampleVid01"
    }
}
