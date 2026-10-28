package io.waves.youtube

import io.waves.currentTimeMillis
import io.waves.feed.Feed
import io.waves.feed.FeedDates
import io.waves.model.Episode
import io.waves.model.Podcast
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Stand-in for YouTube's Atom feeds, which at times answer 404 or 500 for every channel and playlist for hours: the
 * same newest videos as read from a channel's Videos tab or a playlist's page, each with its exact publish time and
 * description from the player API. A video the player doesn't answer for is dated from the page's "3 days ago".
 */
internal class YoutubeWebFeed(
    private val channels: YoutubeChannels,
    private val clock: () -> Long = ::currentTimeMillis,
) {
    /**
     * The first [WEB_FEED_SIZE] of [listed] (as many as an Atom feed carries) as the feed at [feedUrl], titled with
     * their channel's name; null when there are none.
     */
    suspend fun feed(feedUrl: String, listed: List<ListedVideo>): Feed? {
        val videos = listed.take(WEB_FEED_SIZE).ifEmpty { return null }
        val details = coroutineScope { videos.map { async { channels.videoDetails(it.id) } }.awaitAll() }
        val now = clock()
        val episodes = videos.zip(details) { video, d ->
            Episode(
                guid = video.id,
                podcastFeedUrl = feedUrl,
                title = video.title,
                audioUrl = YoutubeUrls.watchUrl(video.id),
                publishedAtEpochMs = d?.publishedAtEpochMs ?: video.age?.let { approximateTime(it, now) },
                durationSeconds = video.seconds ?: d?.seconds,
                description = d?.description,
                artworkUrl = "https://i.ytimg.com/vi/${video.id}/hqdefault.jpg",
            )
        }
        val channel = details.firstNotNullOfOrNull { it?.author }
        return Feed(Podcast(feedUrl = feedUrl, title = channel ?: feedUrl), episodes)
    }
}

/** Videos an Atom feed would carry. */
internal const val WEB_FEED_SIZE = 15

/** A video as a channel's Videos tab or a playlist's page lists it. */
internal data class ListedVideo(val id: String, val title: String, val seconds: Long?, val age: String?)

/**
 * The videos on a Videos tab or playlist page, in order: `lockupViewModel`s (the length is a thumbnail badge, the age
 * a metadata part like "3 days ago") or, in older layouts, `videoRenderer`s.
 */
internal fun parseListedVideos(root: JsonElement): List<ListedVideo> {
    val out = ArrayList<ListedVideo>()
    val seen = HashSet<String>()
    fun add(id: String?, title: String?, length: String?, age: String?) {
        if (id == null || !YoutubeUrls.isVideoId(id) || !seen.add(id)) return
        val seconds = length?.takeIf { CLOCK_TIME.matches(it) }?.let(FeedDates::parseDuration)
        out += ListedVideo(id, title.orEmpty(), seconds, age)
    }
    fun walk(node: JsonElement) {
        when (node) {
            is JsonObject -> {
                val lockup = node.obj("lockupViewModel")
                val renderer = node.obj("videoRenderer")
                when {
                    lockup != null -> if (lockup.string("contentType") in VIDEO_LOCKUPS) {
                        val meta = lockup.obj("metadata")?.obj("lockupMetadataViewModel")
                        add(
                            lockup.string("contentId"),
                            meta?.obj("title")?.string("content"),
                            badgeTexts(lockup).firstOrNull { CLOCK_TIME.matches(it) },
                            meta?.let(::metadataTexts)?.firstOrNull { AGE.containsMatchIn(it) },
                        )
                    }
                    renderer != null -> add(
                        renderer.string("videoId"),
                        renderer.obj("title")?.let { it.string("simpleText") ?: runsText(it) },
                        renderer.obj("lengthText")?.string("simpleText"),
                        renderer.obj("publishedTimeText")?.string("simpleText"),
                    )
                    else -> node.values.forEach(::walk)
                }
            }
            is JsonArray -> node.forEach(::walk)
            else -> Unit
        }
    }
    walk(root)
    return out
}

/** The channel that made the playlist on a playlist `browse` page: its sidebar's owner. */
internal fun parsePlaylistOwner(root: JsonObject): FeedOwner? {
    val owner = find(root, "videoOwnerRenderer") ?: return null
    val run = (owner.obj("title")?.get("runs") as? JsonArray)?.firstOrNull() as? JsonObject
    val id = (run ?: owner).obj("navigationEndpoint")?.obj("browseEndpoint")?.string("browseId")
        ?.takeIf { YoutubeUrls.isChannelId(it) } ?: return null
    return FeedOwner(id, run?.string("text")?.trim()?.takeIf { it.isNotEmpty() })
}

/** What the player API tells about one video, whether or not it lets this client play it. */
internal data class VideoDetails(
    val publishedAtEpochMs: Long?,
    val description: String?,
    val seconds: Long?,
    val author: String?,
)

internal fun parseVideoDetails(root: JsonObject): VideoDetails? {
    val details = root.obj("videoDetails")
    val micro = root.obj("microformat")?.obj("playerMicroformatRenderer")
    if (details == null && micro == null) return null
    return VideoDetails(
        publishedAtEpochMs = FeedDates.parseDate(micro?.string("publishDate") ?: micro?.string("uploadDate")),
        description = details?.string("shortDescription") ?: micro?.obj("description")?.string("simpleText"),
        seconds = details?.string("lengthSeconds")?.toLongOrNull()?.takeIf { it > 0 },
        author = details?.string("author") ?: micro?.string("ownerChannelName"),
    )
}

/** "3 days ago", "Streamed 2 weeks ago" → that long before [now]; null for anything else. */
internal fun approximateTime(age: String, now: Long): Long? {
    val m = AGE.find(age) ?: return null
    val unit = when (m.groupValues[2].lowercase()) {
        "second" -> 1_000L
        "minute" -> 60_000L
        "hour" -> 3_600_000L
        "day" -> 86_400_000L
        "week" -> 7 * 86_400_000L
        "month" -> 30 * 86_400_000L
        else -> 365 * 86_400_000L
    }
    return now - m.groupValues[1].toLong() * unit
}

private val VIDEO_LOCKUPS = setOf(null, "LOCKUP_CONTENT_TYPE_VIDEO")
private val AGE = Regex("""(\d+)\s+(second|minute|hour|day|week|month|year)s?\s+ago""", RegexOption.IGNORE_CASE)

private fun metadataTexts(meta: JsonObject): List<String> {
    val rows = meta.obj("metadata")?.obj("contentMetadataViewModel")?.get("metadataRows") as? JsonArray ?: return emptyList()
    return rows.flatMap { row ->
        ((row as? JsonObject)?.get("metadataParts") as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.obj("text")?.string("content") }
    }
}

private fun runsText(node: JsonObject): String? =
    (node["runs"] as? JsonArray)?.joinToString("") { (it as? JsonObject)?.string("text").orEmpty() }?.takeIf { it.isNotEmpty() }

private fun find(node: JsonElement, key: String): JsonObject? = when (node) {
    is JsonObject -> node.obj(key) ?: node.values.firstNotNullOfOrNull { find(it, key) }
    is JsonArray -> node.firstNotNullOfOrNull { find(it, key) }
    else -> null
}
