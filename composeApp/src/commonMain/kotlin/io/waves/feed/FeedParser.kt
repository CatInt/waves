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
        val seenGuids = HashSet<String>()
        val episodes = ArrayList<Episode>()
        val doc = XmlTree.parse(xml, consumeChildrenOf = "channel") { item ->
            if (item.name != "item") return@parse false
            parseEpisode(feedUrl, item)?.let { episode ->
                // Keep the first occurrence of each guid, as the library keys episodes by (feedUrl, guid).
                if (seenGuids.add(episode.guid)) episodes.add(episode)
            }
            true
        }
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

        return Feed(podcast, episodes)
    }

    private fun parseEpisode(feedUrl: String, item: XmlElement): Episode? {
        val enclosure = item.children.firstNotNullOfOrNull {
            if (it.name == "enclosure") it.attr("url") else null
        } ?: return null
        return Episode(
            guid = item.childText("guid") ?: enclosure,
            podcastFeedUrl = feedUrl,
            title = item.childText("title") ?: item.childText("itunes:title") ?: "",
            audioUrl = enclosure,
            publishedAtEpochMs = FeedDates.parseDate(item.childText("pubDate") ?: item.childText("dc:date")),
            durationSeconds = FeedDates.parseDuration(item.childText("itunes:duration")),
            description = item.childText("description")
                ?: item.childText("content:encoded")
                ?: item.childText("itunes:summary"),
            artworkUrl = item.child("itunes:image")?.attr("href"),
        )
    }

    private fun XmlElement.attr(name: String): String? = attributes[name]?.trim()?.takeIf { it.isNotEmpty() }
}
