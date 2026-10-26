package io.waves.youtube

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * On-device [yt-dlp](https://github.com/yt-dlp/yt-dlp), via youtubedl-android, for when Innertube won't
 * return a stream. AAC in an M4A is preferred; any other audio is only a last resort, because downloads of
 * watch URLs are named `.m4a`.
 *
 * [YoutubeDL.init] extracts Python out of the native libraries, so it runs on first use off the main thread;
 * if that fails this source is treated as not set up.
 */
class YtDlpAndroidSource(
    context: Context,
    private val timeout: Duration = 90.seconds,
) : YoutubeAudioSource {
    private val appContext = context.applicationContext

    init {
        // A cookies.txt imported by earlier versions; nothing reads it any more.
        File(appContext.filesDir, "youtube-cookies.txt").delete()
        // Schedules the daily binary update only. The native library stays uninitialized until a real request.
        YtDlpUpdates.poke(appContext)
    }

    override suspend fun streamUrl(videoId: String): String? {
        if (!YoutubeUrls.isVideoId(videoId)) return null
        YtDlpUpdates.poke(appContext)
        val processId = "waves-$videoId-${System.nanoTime()}"
        return try {
            withContext(Dispatchers.IO) {
                if (!YtDlpRuntime.ensureInitialized(appContext)) return@withContext null
                try {
                    withTimeout(timeout) {
                        runInterruptible {
                            val response = YoutubeDL.getInstance().execute(
                                ytdlpAudioRequest(videoId),
                                processId,
                                false,
                            )
                            parseYtDlpOutput(response.exitCode, response.out + "\n" + response.err)
                        }
                    }
                } catch (e: YoutubeDL.CanceledException) {
                    throw CancellationException("yt-dlp was cancelled", e)
                } catch (e: YoutubeDLException) {
                    // Non-zero exits throw with stderr as the message; stdout is dropped by the library.
                    throw YoutubeException(ytDlpFailureMessage(e.message), e)
                }
            }
        } catch (e: TimeoutCancellationException) {
            YoutubeDL.getInstance().destroyProcessById(processId)
            throw YoutubeException("yt-dlp took too long.", e)
        } catch (e: CancellationException) {
            YoutubeDL.getInstance().destroyProcessById(processId)
            throw e
        }
    }
}

internal const val YTDLP_LOG_TAG = "YtDlp"

/**
 * AAC (M4A) first, then any audio. yt-dlp's `-f` fallbacks are left to right.
 * Watch-URL downloads are stored as `.m4a` ([io.waves.download.audioExtension]).
 */
internal const val YTDLP_AUDIO_FORMAT = "bestaudio[ext=m4a]/bestaudio[acodec^=mp4a]/bestaudio"

/** Arguments passed to yt-dlp for one watch URL. The library adds its own flags (`--no-cache-dir`, a JS runtime) later. */
internal fun ytdlpAudioArguments(videoId: String): List<String> = buildList {
    addAll(listOf("--quiet", "--no-warnings", "--no-playlist", "--no-progress"))
    add("-f")
    add(YTDLP_AUDIO_FORMAT)
    add("--get-url")
    add(YoutubeUrls.watchUrl(videoId))
}

internal fun ytdlpAudioRequest(videoId: String): YoutubeDLRequest {
    val request = YoutubeDLRequest(YoutubeUrls.watchUrl(videoId))
    request.addOption("--quiet")
    request.addOption("--no-warnings")
    request.addOption("--no-playlist")
    request.addOption("--no-progress")
    request.addOption("-f", YTDLP_AUDIO_FORMAT)
    request.addOption("--get-url")
    return request
}

/** [parseYtDlpOutput] for a failed run (yt-dlp's stderr). */
internal fun ytDlpFailureMessage(output: String?): String =
    try {
        parseYtDlpOutput(1, output.orEmpty())
    } catch (e: YoutubeException) {
        e.message ?: "yt-dlp couldn't get this video's audio."
    }

internal object YtDlpRuntime {
    private val lock = Any()

    @Volatile private var ready = false

    @Volatile private var failed = false

    /**
     * False when the bundled Python/yt-dlp couldn't be unpacked. A failure sticks for this process so playback
     * doesn't unpack again on every episode; the next process start tries once more.
     */
    fun ensureInitialized(context: Context): Boolean {
        if (ready) return true
        if (failed) return false
        return synchronized(lock) {
            if (ready) return@synchronized true
            if (failed) return@synchronized false
            try {
                YoutubeDL.getInstance().init(context.applicationContext)
                ready = true
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(YTDLP_LOG_TAG, "yt-dlp isn't available", e)
                failed = true
                false
            }
        }
    }
}
