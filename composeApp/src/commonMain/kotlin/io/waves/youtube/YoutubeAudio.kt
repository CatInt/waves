package io.waves.youtube

import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Resolves a YouTube watch URL to a direct audio-only stream.
 *
 * The file is AAC in an MP4 container (the desktop player plays that, and so does ExoPlayer). Video
 * and WebM/Opus are skipped. Stream URLs expire, so this runs at play and download time; the library
 * keeps the watch URL.
 *
 * YouTube sometimes answers the player call with a sign-in check instead of a stream (common on
 * datacenter and corporate networks). Then each of [fallbacks] is tried in order; when none can help it
 * becomes a normal playback error — the episode stays in the list like any other show.
 */
class YoutubeMediaLocator(
    private val client: HttpClient,
    private val fallbacks: List<YoutubeAudioSource> = emptyList(),
) {
    suspend fun locate(url: String): String {
        val videoId = YoutubeUrls.videoId(url) ?: return url
        val builtIn = try {
            return YoutubeAudio.streamUrl(client, videoId)
        } catch (e: YoutubeException) {
            e
        }
        var lastFailure: Exception? = null
        for (source in fallbacks) {
            try {
                source.streamUrl(videoId)?.let { return it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastFailure = e
            }
        }
        val message = listOfNotNull(builtIn.message, lastFailure?.message).filter { it.isNotBlank() }.joinToString(" ")
        throw YoutubeException(message, lastFailure ?: builtIn)
    }
}

/** Another way to get a playable URL for a video. Returns null when it isn't set up; throws when it fails. */
fun interface YoutubeAudioSource {
    suspend fun streamUrl(videoId: String): String?
}

object YoutubeAudio {
    suspend fun streamUrl(client: HttpClient, videoId: String): String {
        if (!YoutubeUrls.isVideoId(videoId)) throw YoutubeException("That isn't a YouTube video")
        var failure: AudioChoice.Unavailable? = null
        for (spec in clients) {
            val text = try {
                client.postJson(
                    "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
                    spec.body(videoId),
                    spec.userAgent,
                    mapOf(
                        "X-YouTube-Client-Name" to spec.clientId,
                        "X-YouTube-Client-Version" to spec.version,
                    ),
                )
            } catch (e: YoutubeException) {
                failure = AudioChoice.Unavailable(null, e.message)
                continue
            }
            when (val choice = interpret(text)) {
                is AudioChoice.Stream -> return choice.url
                is AudioChoice.Unavailable -> {
                    failure = choice
                    // An IP-level sign-in wall rejects every client; don't wait on the rest.
                    if (choice.status == "LOGIN_REQUIRED") break
                }
            }
        }
        throw YoutubeException(audioFailureMessage(failure))
    }

    internal fun interpret(json: String): AudioChoice {
        val root = jsonObject(json) ?: return AudioChoice.Unavailable(null, null)
        val playability = root.obj("playabilityStatus")
        val status = playability?.string("status")
        val reason = playability?.string("reason")
        val streaming = root.obj("streamingData")
        val formats = buildList {
            (streaming?.get("adaptiveFormats") as? JsonArray)?.let { addAll(it) }
            (streaming?.get("formats") as? JsonArray)?.let { addAll(it) }
        }.mapNotNull { it as? JsonObject }
        val audio = formats.mapNotNull { format ->
            val mime = format.string("mimeType") ?: return@mapNotNull null
            if (!mime.startsWith("audio/mp4")) return@mapNotNull null
            val url = format.string("url")?.takeIf { it.startsWith("https://") && YoutubeUrls.videoId(it) == null }
                ?: return@mapNotNull null
            url to (format.long("bitrate") ?: 0L)
        }
        if (audio.isNotEmpty()) return AudioChoice.Stream(audio.maxBy { it.second }.first)
        if (status != null && status != "OK") return AudioChoice.Unavailable(status, reason)
        return AudioChoice.Unavailable(status, "This video has no audio stream that can be played here.")
    }

    internal fun audioFailureMessage(failure: AudioChoice.Unavailable?): String = when (failure?.status) {
        "LOGIN_REQUIRED" -> "YouTube asked for a sign-in check, so this video can't be played as audio right now. " +
            "YouTube flags some VPN and proxy servers: if you use one, try a different server or region."
        else -> failure?.reason?.trim()?.takeIf { it.isNotEmpty() }
            ?: "This video can't be played as audio right now."
    }

    private data class Client(
        val name: String,
        val version: String,
        val clientId: String,
        val userAgent: String,
        val extra: JsonObject,
    ) {
        fun body(videoId: String) = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", name)
                    put("clientVersion", version)
                    put("userAgent", userAgent)
                    put("hl", "en")
                    put("gl", "US")
                    extra.forEach { (k, v) -> put(k, v) }
                }
            }
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }.toString()
    }

    private val clients = listOf(
        Client(
            name = "ANDROID_VR",
            version = "1.65.10",
            clientId = "28",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            extra = buildJsonObject {
                put("deviceMake", "Oculus")
                put("deviceModel", "Quest 3")
                put("androidSdkVersion", 32)
                put("osName", "Android")
                put("osVersion", "12L")
            },
        ),
        Client(
            name = "ANDROID",
            version = "21.26.364",
            clientId = "3",
            userAgent = "com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip",
            extra = buildJsonObject {
                put("androidSdkVersion", 30)
                put("osName", "Android")
                put("osVersion", "11")
            },
        ),
    )
}

internal sealed interface AudioChoice {
    data class Stream(val url: String) : AudioChoice
    data class Unavailable(val status: String?, val reason: String?) : AudioChoice
}
