package io.waves.update

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** GitHub repository whose releases carry the APK and DMG (see .github/workflows/release.yml). */
const val RELEASES_REPO = "CatInt/waves"

/** MAJOR.MINOR.PATCH, the format of release tags (`v1.2.3`) and of [io.waves.BuildInfo.VERSION]. */
data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {
    override fun compareTo(other: Version): Int =
        compareValuesBy(this, other, Version::major, Version::minor, Version::patch)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        /** Parses `1.2.3` or `v1.2.3`; null for anything else (pre-release suffixes included). */
        fun parse(text: String): Version? {
            val parts = text.trim().removePrefix("v").split('.')
            if (parts.size != 3) return null
            val numbers = parts.map { it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: return null }
            return Version(numbers[0], numbers[1], numbers[2])
        }
    }
}

data class ReleaseAsset(val name: String, val url: String, val sizeBytes: Long)

data class AppRelease(val version: Version, val notes: String, val pageUrl: String, val assets: List<ReleaseAsset>)

/** Reads the latest published (non-draft, non-prerelease) release of [repo] from the GitHub REST API. */
class GitHubReleases(private val http: HttpClient, private val repo: String = RELEASES_REPO) {
    /** Null when the repository has no usable release yet. */
    suspend fun latest(): AppRelease? {
        val response = http.get("https://api.github.com/repos/$repo/releases/latest") {
            header("Accept", "application/vnd.github+json")
            header("X-GitHub-Api-Version", "2022-11-28")
        }
        if (response.status == HttpStatusCode.NotFound) return null
        // Anonymous API calls are capped at 60 an hour per IP address, which a shared network can use up.
        if (response.status == HttpStatusCode.Forbidden || response.status == HttpStatusCode.TooManyRequests) {
            return latestFromWebsite()
        }
        if (!response.status.isSuccess()) error("GitHub returned HTTP ${response.status.value}")
        return parseRelease(response.bodyAsText())
    }

    /** The release pages on github.com aren't rate limited; they give the tag and file links but no notes or sizes. */
    private suspend fun latestFromWebsite(): AppRelease? {
        val latest = http.head("https://github.com/$repo/releases/latest")
        if (latest.status == HttpStatusCode.NotFound) return null
        if (!latest.status.isSuccess()) error("GitHub returned HTTP ${latest.status.value}")
        // Redirects to /releases/tag/<tag>, or to /releases when nothing is published.
        val tag = TAG_PATH.find(latest.call.request.url.encodedPath)?.groupValues?.get(1) ?: return null
        val version = Version.parse(tag) ?: return null
        val assets = http.get("https://github.com/$repo/releases/expanded_assets/$tag")
        if (!assets.status.isSuccess()) error("GitHub returned HTTP ${assets.status.value}")
        return AppRelease(
            version = version,
            notes = "",
            pageUrl = "https://github.com/$repo/releases/tag/$tag",
            assets = parseAssetLinks(assets.bodyAsText(), repo, tag),
        )
    }

    internal companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val TAG_PATH = Regex("/releases/tag/([^/]+)$")

        fun parseAssetLinks(html: String, repo: String, tag: String): List<ReleaseAsset> =
            Regex("""href="(/${Regex.escape(repo)}/releases/download/${Regex.escape(tag)}/([^"/]+))"""")
                .findAll(html)
                .map { ReleaseAsset(name = it.groupValues[2], url = "https://github.com${it.groupValues[1]}", sizeBytes = 0) }
                .distinctBy { it.name }
                .toList()

        fun parseRelease(body: String): AppRelease? {
            val dto = json.decodeFromString(ReleaseDto.serializer(), body)
            if (dto.draft || dto.prerelease) return null
            val version = Version.parse(dto.tagName) ?: return null
            return AppRelease(
                version = version,
                notes = dto.body.orEmpty(),
                pageUrl = dto.htmlUrl,
                assets = dto.assets.map { ReleaseAsset(it.name, it.browserDownloadUrl, it.size) },
            )
        }
    }
}

@Serializable
private class ReleaseDto(
    @SerialName("tag_name") val tagName: String,
    @SerialName("html_url") val htmlUrl: String = "",
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<AssetDto> = emptyList(),
)

@Serializable
private class AssetDto(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val size: Long = 0,
)

/** Platform side of an update: which release file fits this device, and how to install it. */
interface UpdateInstaller {
    /** This platform's file in [release], or null when the release has none. */
    fun assetFor(release: AppRelease): ReleaseAsset?

    /**
     * Downloads [asset] and starts installing it. On Android this returns once the system installer is shown;
     * on macOS the app quits and relaunches as the new version.
     *
     * @param onProgress download fraction in 0..1, or null while the size is unknown.
     * @throws UpdateNeedsPermissionException when the user must allow installs first (a settings screen is open).
     */
    suspend fun install(release: AppRelease, asset: ReleaseAsset, onProgress: (Float?) -> Unit)
}

class UpdateNeedsPermissionException(message: String) : Exception(message)

/**
 * Streams [url] into [write], reporting progress. Meant for a client without a request timeout
 * (release files are tens of MB).
 */
suspend fun HttpClient.downloadTo(url: String, write: (ByteArray, Int) -> Unit, onProgress: (Float?) -> Unit): Long {
    return prepareGet(url).execute { response ->
        if (!response.status.isSuccess()) error("Download failed: HTTP ${response.status.value}")
        val total = response.contentLength()?.takeIf { it > 0 }
        val channel = response.bodyAsChannel()
        val buffer = ByteArray(64 * 1024)
        var received = 0L
        onProgress(if (total != null) 0f else null)
        while (true) {
            val n = channel.readAvailable(buffer, 0, buffer.size)
            if (n == -1) break
            if (n == 0) continue
            write(buffer, n)
            received += n
            onProgress(total?.let { (received.toFloat() / it).coerceIn(0f, 1f) })
        }
        if (total != null && received != total) error("Download ended early ($received of $total bytes)")
        received
    }
}
