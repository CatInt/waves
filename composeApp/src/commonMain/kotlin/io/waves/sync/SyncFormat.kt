package io.waves.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * On-the-wire format of the sync file and its compatibility rules (see docs/testing/sync-integration.md).
 *
 * - [SyncState.version] is the *format* version. A writer bumps it only for changes an older app can't safely
 *   round-trip: new fields inside existing records, changed meaning of a field, a different encoding.
 * - An app never writes a file whose version is newer than [SUPPORTED_VERSION]; it doesn't import it either
 *   (it can't know what the unknown parts mean). The sync fails with [SyncFormatTooNewException]
 *   ("Update Waves to keep syncing") and leaves both the remote file and the local database untouched.
 * - Within one version, new data may only be added as new *top-level* keys. Older apps of that version keep such
 *   keys verbatim when they rewrite the file ([Decoded.extras]), so nothing a newer app stored is destroyed.
 */
object SyncFormat {
    /** Highest format version this build reads and writes. */
    const val SUPPORTED_VERSION = 1

    private val KNOWN_KEYS = setOf("version", "subscriptions", "progress", "history", "archive", "showSettings", "listening")

    internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** A decoded remote file: the [state] this build understands plus top-level keys it doesn't ([extras]). */
    class Decoded(val state: SyncState, val extras: Map<String, JsonElement>)

    /**
     * @throws SyncFormatTooNewException if the file needs a newer app.
     * @throws SyncFileUnreadableException if it isn't a sync file this build can parse.
     */
    fun decode(text: String): Decoded {
        val obj = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw SyncFileUnreadableException("not a JSON object (${e.message?.take(200)})", e)
        }
        val version = when (val v = obj["version"]) {
            null -> 1
            else -> (v as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
                ?: throw SyncFileUnreadableException("invalid version $v")
        }
        if (version > SUPPORTED_VERSION) throw SyncFormatTooNewException(version)
        val state = try {
            json.decodeFromJsonElement(SyncState.serializer(), obj)
        } catch (e: Exception) {
            throw SyncFileUnreadableException(e.message?.take(300) ?: "invalid content", e)
        }
        return Decoded(state, obj.filterKeys { it !in KNOWN_KEYS })
    }

    /** Encodes [state], carrying along [extras] (unknown top-level keys from the remote file). */
    fun encode(state: SyncState, extras: Map<String, JsonElement> = emptyMap()): String {
        val obj = json.encodeToJsonElement(SyncState.serializer(), state).jsonObject
        return if (extras.isEmpty()) obj.toString() else JsonObject(obj + extras.filterKeys { it !in obj }).toString()
    }
}

/** The remote sync file was written by a newer app version; this build must not touch it. */
class SyncFormatTooNewException(val remoteVersion: Int) : Exception(
    "Your synced library was saved by a newer version of Waves. Update Waves to keep syncing.",
)

/** The remote sync file can't be parsed; sync refuses to overwrite it. */
class SyncFileUnreadableException(detail: String, cause: Throwable? = null) :
    Exception("Remote sync file is unreadable: $detail", cause)
