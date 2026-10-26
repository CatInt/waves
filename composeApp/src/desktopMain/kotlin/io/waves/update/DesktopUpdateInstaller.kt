package io.waves.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File

/**
 * macOS: downloads the release DMG, then quits through [quitApp] while a helper script waits for this process
 * to exit, replaces the running bundle with the one in the DMG (taking that one's name, so a renamed app follows)
 * and relaunches it. When the app doesn't run from a writable bundle (`./gradlew run`, or a read-only location)
 * the DMG is opened for a manual install.
 */
class DesktopUpdateInstaller(private val quitApp: () -> Unit) : UpdateInstaller {
    /** Own client: no request timeout or HTTP cache for a file of ~100 MB. */
    private val http = HttpClient(Java) {
        followRedirects = true
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 60_000
        }
    }

    override fun assetFor(release: AppRelease): ReleaseAsset? {
        if (!isMac) return null
        val dmgs = release.assets.filter { it.name.endsWith(".dmg", ignoreCase = true) }
        val mine = if (System.getProperty("os.arch") in setOf("aarch64", "arm64")) ARM_TAGS else INTEL_TAGS
        return dmgs.firstOrNull { asset -> mine.any { asset.name.contains(it, ignoreCase = true) } }
            ?: dmgs.firstOrNull { asset -> (ARM_TAGS + INTEL_TAGS).none { asset.name.contains(it, ignoreCase = true) } }
    }

    override suspend fun install(release: AppRelease, asset: ReleaseAsset, onProgress: (Float?) -> Unit) {
        val dmg = withContext(Dispatchers.IO) {
            val dir = File(System.getProperty("java.io.tmpdir"), "waves-updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, asset.name.substringAfterLast('/'))
            file.outputStream().use { out -> http.downloadTo(asset.url, { bytes, n -> out.write(bytes, 0, n) }, onProgress) }
            file
        }
        val bundle = runningBundle()
        if (bundle == null || !bundle.parentFile.canWrite()) {
            Desktop.getDesktop().open(dmg)
            return
        }
        withContext(Dispatchers.IO) {
            val script = File(dmg.parentFile, "install.sh").apply { writeText(SWAP_SCRIPT) }
            ProcessBuilder("/bin/sh", script.path, ProcessHandle.current().pid().toString(), dmg.path, bundle.path)
                .redirectErrorStream(true)
                .redirectOutput(File(dmg.parentFile, "install.log"))
                .start()
        }
        quitApp()
    }

    /** `/Applications/Waves.app` when launched from a jpackage bundle, else null. */
    private fun runningBundle(): File? {
        val launcher = System.getProperty("jpackage.app-path")?.let(::File) ?: return null
        return launcher.parentFile?.parentFile?.parentFile?.takeIf { it.name.endsWith(".app") }
    }

    private companion object {
        val isMac = System.getProperty("os.name").orEmpty().lowercase().contains("mac")
        val ARM_TAGS = listOf("arm64", "aarch64", "apple-silicon")
        val INTEL_TAGS = listOf("x64", "x86_64", "intel")

        /** Args: pid to wait for, DMG, bundle to replace. Keeps the old bundle if copying fails. */
        val SWAP_SCRIPT = """
            |#!/bin/sh
            |pid="${'$'}1"; dmg="${'$'}2"; app="${'$'}3"
            |while kill -0 "${'$'}pid" 2>/dev/null; do sleep 0.5; done
            |mnt=${'$'}(mktemp -d /tmp/waves-update.XXXXXX)
            |if ! hdiutil attach -nobrowse -noautoopen -quiet -mountpoint "${'$'}mnt" "${'$'}dmg"; then
            |  open "${'$'}dmg"; exit 1
            |fi
            |src=${'$'}(find "${'$'}mnt" -maxdepth 1 -name '*.app' | head -n 1)
            |if [ -n "${'$'}src" ]; then
            |  dest="${'$'}(dirname "${'$'}app")/${'$'}(basename "${'$'}src")"
            |  rm -rf "${'$'}app.previous"
            |  if mv "${'$'}app" "${'$'}app.previous"; then
            |    [ "${'$'}dest" = "${'$'}app" ] || rm -rf "${'$'}dest"
            |    if ditto "${'$'}src" "${'$'}dest"; then
            |      rm -rf "${'$'}app.previous"; app="${'$'}dest"
            |    else
            |      rm -rf "${'$'}dest"; mv "${'$'}app.previous" "${'$'}app"
            |    fi
            |  fi
            |fi
            |hdiutil detach -quiet "${'$'}mnt"
            |rm -f "${'$'}dmg"
            |open "${'$'}app"
            |""".trimMargin()
    }
}
