package io.waves.feed

import io.waves.model.Episode
import io.waves.model.Podcast

/** Thrown when a document is not a parseable RSS podcast feed. */
class FeedParseException(message: String) : Exception(message)

/** Pure RSS 2.0 (+ iTunes / content namespaces) parser. */
object FeedParser {

    /**
     * Parses [xml] into a [Feed]. The returned podcast and episodes are keyed on [feedUrl] (as given, so the
     * caller's identity for the feed stays stable). Items without an enclosure URL are skipped.
     *
     * @throws FeedParseException when the document contains no `<channel>`.
     */
    fun parse(feedUrl: String, xml: String): Feed {
        val doc = XmlTree.parse(xml)
        val channel = doc.find("channel")
            ?: throw FeedParseException("Not an RSS feed (no <channel>): $feedUrl")

        val channelArtwork = channel.child("itunes:image")?.attr("href")
            ?: channel.child("image")?.childText("url")
        val podcast = Podcast(
            feedUrl = feedUrl,
            title = channel.childText("title") ?: channel.childText("itunes:title") ?: feedUrl,
            author = channel.childText("itunes:author")
                ?: channel.child("itunes:owner")?.childText("itunes:name")
                ?: channel.childText("managingEditor"),
            description = channel.childText("description")
                ?: channel.childText("itunes:summary")
                ?: channel.childText("itunes:subtitle"),
            artworkUrl = channelArtwork,
        )

        val seenGuids = HashSet<String>()
        val episodes = channel.children("item").mapNotNull { item ->
            val enclosure = item.children("enclosure").firstNotNullOfOrNull { it.attr("url") } ?: return@mapNotNull null
            val title = item.childText("title") ?: item.childText("itunes:title") ?: ""
            val guid = item.childText("guid")
                ?: enclosure
            // Feeds occasionally repeat guids; keep the first so the (feedUrl, guid) key stays unique.
            if (!seenGuids.add(guid)) return@mapNotNull null
            Episode(
                guid = guid,
                podcastFeedUrl = feedUrl,
                title = title,
                audioUrl = enclosure,
                publishedAtEpochMs = FeedDates.parseDate(item.childText("pubDate") ?: item.childText("dc:date")),
                durationSeconds = FeedDates.parseDuration(item.childText("itunes:duration")),
                description = item.childText("description")
                    ?: item.childText("content:encoded")
                    ?: item.childText("itunes:summary"),
                artworkUrl = item.child("itunes:image")?.attr("href"),
            )
        }
        return Feed(podcast, episodes)
    }

    private fun XmlElement.attr(name: String): String? = attributes[name]?.trim()?.takeIf { it.isNotEmpty() }
}
