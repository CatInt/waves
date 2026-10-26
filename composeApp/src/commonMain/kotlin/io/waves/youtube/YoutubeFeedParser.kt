package io.waves.youtube

import io.waves.feed.Feed
import io.waves.feed.FeedDates
import io.waves.feed.FeedParseException
import io.waves.feed.XmlElement
import io.waves.feed.XmlTree
import io.waves.model.Episode
import io.waves.model.Podcast

/**
 * YouTube's public Atom feed (`/feeds/videos.xml`, for a channel or a playlist) → the same [Feed] shape as an RSS
 * podcast. A playlist feed is titled with the playlist and authored by its channel, which becomes the creator.
 */
object YoutubeFeedParser {

    /**
     * [feedUrl] is kept as given so the caller's subscription key stays stable. Each episode's audio URL is
     * the watch page; the expiring audio stream is resolved later, at playback and download.
     */
    fun parse(feedUrl: String, xml: String): Feed {
        val feed = XmlTree.parse(xml).find("feed")
            ?: throw FeedParseException("Not a YouTube channel feed: $feedUrl")
        val author = feed.child("author")?.childText("name")
        val title = feed.childText("title") ?: author ?: feedUrl
        val podcast = Podcast(
            feedUrl = feedUrl,
            title = title,
            // The channel name is already the show title; repeating it as the author just duplicates the line.
            author = author?.takeUnless { it.equals(title, ignoreCase = true) },
        )
        val seen = HashSet<String>()
        val episodes = feed.children("entry").mapNotNull { entry ->
            if (isShort(entry)) return@mapNotNull null
            val id = videoIdOf(entry) ?: return@mapNotNull null
            if (!seen.add(id)) return@mapNotNull null
            val group = entry.child("media:group")
            Episode(
                guid = id,
                podcastFeedUrl = feedUrl,
                title = entry.childText("title") ?: group?.childText("media:title") ?: "",
                audioUrl = YoutubeUrls.watchUrl(id),
                publishedAtEpochMs = FeedDates.parseDate(entry.childText("published")),
                description = group?.childText("media:description") ?: entry.childText("media:description"),
                artworkUrl = thumbnail(group) ?: thumbnail(entry),
            )
        }
        return Feed(podcast, episodes)
    }

    /** The channel that owns the feed (for a playlist: the one that made it), or null when the feed doesn't say. */
    fun owner(xml: String): FeedOwner? {
        val feed = XmlTree.parse(xml).find("feed") ?: return null
        val author = feed.child("author")
        val raw = feed.childText("yt:channelId")?.trim()
        // Some feeds drop the "UC" prefix from this element.
        val id = raw?.let { if (it.startsWith("UC")) it else "UC$it" }?.takeIf { YoutubeUrls.isChannelId(it) }
            ?: author?.childText("uri")?.let { (YoutubeUrls.parse(it) as? YoutubeTarget.Channel)?.id }
            ?: return null
        return FeedOwner(id, author?.childText("name")?.trim()?.takeIf { it.isNotEmpty() })
    }

    /** The channel feed links Shorts as `/shorts/<id>` rather than `/watch?v=<id>`. */
    private fun isShort(entry: XmlElement): Boolean =
        entry.children("link").any { "/shorts/" in it.attributes["href"].orEmpty() }

    private fun videoIdOf(entry: XmlElement): String? {
        entry.childText("yt:videoId")?.takeIf { YoutubeUrls.isVideoId(it) }?.let { return it }
        val raw = entry.childText("id") ?: return null
        return raw.substringAfter("yt:video:", "").takeIf { YoutubeUrls.isVideoId(it) }
    }

    private fun thumbnail(parent: XmlElement?): String? {
        val thumbs = parent?.children("media:thumbnail").orEmpty()
        if (thumbs.isEmpty()) return null
        return thumbs.maxByOrNull { it.attributes["width"]?.toIntOrNull() ?: 0 }
            ?.attributes["url"]?.trim()?.takeIf { it.isNotEmpty() }
    }
}

data class FeedOwner(val channelId: String, val name: String?)
