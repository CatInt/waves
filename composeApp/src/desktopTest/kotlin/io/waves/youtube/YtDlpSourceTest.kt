package io.waves.youtube

import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class YtDlpSourceTest {
    @Test
    fun printedUrlIsTheStream() {
        assertEquals(
            "https://rr1.googlevideo.com/videoplayback?itag=140",
            YtDlpSource.parseOutput(0, "\nhttps://rr1.googlevideo.com/videoplayback?itag=140\n"),
        )
    }

    @Test
    fun errorLineBecomesAShortMessage() {
        val output = "ERROR: [youtube] sampleVid01: Sign in to confirm you’re not a bot. Use --cookies-from-browser or " +
            "--cookies for the authentication. See https://github.com/yt-dlp/yt-dlp/wiki/FAQ\n"
        val e = assertFailsWith<YoutubeException> { YtDlpSource.parseOutput(1, output) }
        assertEquals("yt-dlp: Sign in to confirm you’re not a bot.", e.message)
        val silent = assertFailsWith<YoutubeException> { YtDlpSource.parseOutput(1, "") }
        assertEquals("yt-dlp couldn't get this video's audio.", silent.message)
    }

    @Test
    fun notInstalledMeansNotApplicable() = runTest {
        assertNull(YtDlpSource(executable = { null }).streamUrl("sampleVid01"))
    }

    @Test
    fun passesTheFormatToTheExecutable() = runTest {
        val dir = createTempDirectory()
        val args = File(dir, "args.txt")
        val fake = File(dir, "yt-dlp").apply {
            writeText("#!/bin/sh\nprintf '%s\\n' \"$@\" > '${args.path}'\necho https://rr.googlevideo.com/videoplayback?ok\n")
            setExecutable(true)
        }
        try {
            val url = YtDlpSource(executable = { fake }).streamUrl("sampleVid01")
            assertEquals("https://rr.googlevideo.com/videoplayback?ok", url)
            val passed = args.readLines()
            assertEquals("bestaudio[ext=m4a]/bestaudio[acodec^=mp4a]", passed[passed.indexOf("-f") + 1])
            assertFalse("--cookies" in passed)
            assertEquals("https://www.youtube.com/watch?v=sampleVid01", passed.last())
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun createTempDirectory(): File = kotlin.io.path.createTempDirectory("ytdlp-test").toFile()
}
