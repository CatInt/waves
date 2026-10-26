package io.waves.youtube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Uses a locally installed [yt-dlp](https://github.com/yt-dlp/yt-dlp) when there is one. It keeps up with
 * YouTube's changes far faster than an app release can.
 *
 * Only M4A (AAC) is requested: the desktop player can't decode WebM/Opus.
 */
class YtDlpSource(
    private val executable: () -> File? = ::findYtDlp,
    private val timeoutSeconds: Long = 90,
) : YoutubeAudioSource {
    override suspend fun streamUrl(videoId: String): String? {
        if (!YoutubeUrls.isVideoId(videoId)) return null
        val exe = executable() ?: return null
        val command = buildList {
            add(exe.path)
            addAll(listOf("--quiet", "--no-warnings", "--no-playlist", "--no-progress"))
            addAll(listOf("-f", "bestaudio[ext=m4a]/bestaudio[acodec^=mp4a]", "--get-url"))
            add(YoutubeUrls.watchUrl(videoId))
        }
        val output = runInterruptible(Dispatchers.IO) { run(command) }
        return parseOutput(output.first, output.second)
    }

    private fun run(command: List<String>): Pair<Int, String> {
        val log = File.createTempFile("waves-yt-dlp", ".txt")
        try {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(log)
                .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                .apply {
                    // Apps launched from Finder get a minimal PATH; yt-dlp needs to find deno/ffmpeg next to itself.
                    val dirs = listOfNotNull(File(command[0]).parent) + searchDirs().map { it.path } +
                        System.getenv("PATH").orEmpty().split(File.pathSeparator)
                    environment()["PATH"] = dirs.filter { it.isNotEmpty() }.distinct().joinToString(File.pathSeparator)
                }
                .start()
            try {
                if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) throw YoutubeException("yt-dlp took too long.")
                return process.exitValue() to log.readText()
            } finally {
                process.destroyForcibly()
            }
        } finally {
            log.delete()
        }
    }

    companion object {
        /** The URL yt-dlp printed, or a [YoutubeException] carrying its error line. */
        internal fun parseOutput(exitCode: Int, output: String): String = parseYtDlpOutput(exitCode, output)

        fun findYtDlp(): File? {
            val path = System.getenv("PATH").orEmpty().split(File.pathSeparator).filter { it.isNotEmpty() }.map(::File)
            return (path + searchDirs()).asSequence()
                .map { File(it, "yt-dlp") }
                .firstOrNull { it.isFile && it.canExecute() }
        }

        private fun searchDirs(): List<File> {
            val home = System.getProperty("user.home")
            return listOf(
                File("/opt/homebrew/bin"),
                File("/usr/local/bin"),
                File(home, ".local/bin"),
                File(home, "bin"),
            )
        }
    }
}
