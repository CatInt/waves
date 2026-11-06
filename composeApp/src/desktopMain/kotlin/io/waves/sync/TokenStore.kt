package io.waves.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

@Serializable
data class StoredCredentials(val refreshToken: String, val email: String? = null)

/**
 * Persists the Google refresh token in a file readable only by the current user (mode 600),
 * by default `~/Library/Application Support/Waves/google-auth.json` (`Waves Dev` for a development build).
 */
class TokenStore(private val file: File = defaultFile()) {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): StoredCredentials? = runCatching {
        if (file.isFile) json.decodeFromString(StoredCredentials.serializer(), file.readText()) else null
    }.getOrNull()

    fun save(credentials: StoredCredentials) {
        val dir = file.absoluteFile.parentFile
        dir.mkdirs()
        setPermissions(dir, "rwx------")
        // Write to a private temp file first so the token is never world-readable, then move into place.
        val tmp = File.createTempFile("google-auth", ".tmp", dir)
        try {
            setPermissions(tmp, "rw-------")
            tmp.writeText(json.encodeToString(StoredCredentials.serializer(), credentials))
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            setPermissions(file, "rw-------")
        } finally {
            tmp.delete()
        }
    }

    fun clear() {
        file.delete()
    }

    private fun setPermissions(f: File, perms: String) {
        runCatching { Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString(perms)) }
    }

    companion object {
        fun defaultFile(): File {
            val home = System.getProperty("user.home")
            val os = System.getProperty("os.name").orEmpty().lowercase()
            // A development build signs in on its own, like it keeps its own library ([appDataDirectory]).
            val name = if (io.waves.BuildInfo.IS_RELEASE) "Waves" else "Waves Dev"
            val dir = when {
                os.contains("mac") -> io.waves.data.appDataDirectory()
                else -> File(System.getenv("XDG_CONFIG_HOME") ?: "$home/.config", name)
            }
            return File(dir, "google-auth.json")
        }
    }
}
