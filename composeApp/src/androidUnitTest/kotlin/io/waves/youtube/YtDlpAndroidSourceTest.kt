package io.waves.youtube

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class YtDlpAndroidSourceTest {
    @Test
    fun argumentsPreferM4aAndPrintOnlyTheUrl() {
        val args = ytdlpAudioArguments("sampleVid01")
        assertEquals("bestaudio[ext=m4a]/bestaudio[acodec^=mp4a]/bestaudio", args[args.indexOf("-f") + 1])
        assertEquals("--get-url", args[args.indexOf("-f") + 2])
        assertFalse("--cookies" in args)
        assertEquals("https://www.youtube.com/watch?v=sampleVid01", args.last())
        assertEquals(listOf("--quiet", "--no-warnings", "--no-playlist", "--no-progress"), args.take(4))
    }

    @Test
    fun requestBuildsTheSameCommand() {
        assertEquals(ytdlpAudioArguments("sampleVid01"), ytdlpAudioRequest("sampleVid01").buildCommand())
    }

    @Test
    fun printedUrlIsTheStream() {
        assertEquals(
            "https://rr1.googlevideo.com/videoplayback?itag=140",
            parseYtDlpOutput(0, "\nhttps://rr1.googlevideo.com/videoplayback?itag=140\n"),
        )
    }

    @Test
    fun warningsBeforeTheUrlAreIgnored() {
        val output = "WARNING: [youtube] n challenge\nhttps://rr1.googlevideo.com/videoplayback?ok\n"
        assertEquals("https://rr1.googlevideo.com/videoplayback?ok", parseYtDlpOutput(0, output))
    }

    @Test
    fun errorLineBecomesAShortMessage() {
        val output = "ERROR: [youtube] sampleVid01: Sign in to confirm you’re not a bot. Use --cookies-from-browser or " +
            "--cookies for the authentication. See https://github.com/yt-dlp/yt-dlp/wiki/FAQ\n"
        val e = assertFailsWith<YoutubeException> { parseYtDlpOutput(1, output) }
        assertEquals("yt-dlp: Sign in to confirm you’re not a bot.", e.message)
        assertEquals("yt-dlp: Sign in to confirm you’re not a bot.", ytDlpFailureMessage(output))
        val silent = assertFailsWith<YoutubeException> { parseYtDlpOutput(1, "") }
        assertEquals("yt-dlp couldn't get this video's audio.", silent.message)
        assertEquals("yt-dlp couldn't get this video's audio.", ytDlpFailureMessage(null))
    }

    @Test
    fun successWithoutAUrlIsAnError() {
        val e = assertFailsWith<YoutubeException> { parseYtDlpOutput(0, "WARNING: nothing") }
        assertEquals("yt-dlp couldn't get this video's audio.", e.message)
    }
}
