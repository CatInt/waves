package io.waves.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Downloads the release APK into the cache and hands it to the system package installer, which asks the user
 * to confirm. Android only accepts it if it is signed with the same key as the installed app.
 */
class AndroidUpdateInstaller(context: Context) : UpdateInstaller {
    private val appContext = context.applicationContext

    /** Own client: no request timeout or HTTP cache for a file of tens of MB. */
    private val http = HttpClient(Android) {
        followRedirects = true
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 60_000
        }
    }

    override fun assetFor(release: AppRelease): ReleaseAsset? =
        release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }

    override suspend fun install(release: AppRelease, asset: ReleaseAsset, onProgress: (Float?) -> Unit) {
        if (!appContext.packageManager.canRequestPackageInstalls()) {
            val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${appContext.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(settings)
            throw UpdateNeedsPermissionException("Allow Waves to install apps, then tap Update again.")
        }
        val file = withContext(Dispatchers.IO) {
            val dir = File(appContext.cacheDir, UPDATES_DIR).apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "waves-${release.version}.apk")
            file.outputStream().use { out -> http.downloadTo(asset.url, { bytes, n -> out.write(bytes, 0, n) }, onProgress) }
            file
        }
        val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.updates", file)
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(view)
    }

    private companion object {
        /** Must match res/xml/update_paths.xml. */
        const val UPDATES_DIR = "updates"
    }
}
