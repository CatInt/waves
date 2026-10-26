package io.waves.youtube

import io.waves.feed.FeedDates
import io.waves.feed.FeedParseException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class YoutubeFeedParserTest {
    private val feedUrl = "https://www.youtube.com/feeds/videos.xml?channel_id=UCexampleChannel00000000"

    @Test
    fun atomFeedBecomesEpisodesWithStableWatchUrls() {
        val feed = YoutubeFeedParser.parse(feedUrl, ATOM)
        assertEquals("Example Channel", feed.podcast.title)
        assertNull(feed.podcast.author)
        assertEquals(feedUrl, feed.podcast.feedUrl)
        assertEquals(2, feed.episodes.size)

        val first = feed.episodes[0]
        assertEquals("_unboxing01", first.guid)
        assertEquals("https://www.youtube.com/watch?v=_unboxing01", first.audioUrl)
        assertEquals(feedUrl, first.podcastFeedUrl)
        assertEquals("Unboxing the gadget", first.title)
        assertEquals("Only two things come in the box", first.description)
        assertEquals("https://i.ytimg.com/vi/_unboxing01/hqdefault.jpg", first.artworkUrl)
        assertEquals(FeedDates.parseDate("2026-10-21T14:00:33+00:00"), first.publishedAtEpochMs)
        assertNull(first.durationSeconds)

        assertEquals("abcdefghijk", feed.episodes[1].guid)
        assertEquals("https://www.youtube.com/watch?v=abcdefghijk", feed.episodes[1].audioUrl)
    }

    @Test
    fun alternateYtPrefixStillYieldsTheVideoId() {
        val xml = """
            <feed xmlns:youtube="http://www.youtube.com/xml/schemas/2015">
              <title>Show</title>
              <entry>
                <youtube:videoId>abcdefghijk</youtube:videoId>
                <title>One</title>
              </entry>
            </feed>
        """.trimIndent()
        val feed = YoutubeFeedParser.parse(feedUrl, xml)
        assertEquals("abcdefghijk", feed.episodes.single().guid)
    }

    @Test
    fun shortsAreLeftOut() {
        val xml = """
            <feed xmlns:yt="http://www.youtube.com/xml/schemas/2015" xmlns="http://www.w3.org/2005/Atom">
              <title>Show</title>
              <entry>
                <yt:videoId>shortshort1</yt:videoId>
                <title>A Short</title>
                <link rel="alternate" href="https://www.youtube.com/shorts/shortshort1"/>
              </entry>
              <entry>
                <yt:videoId>abcdefghijk</yt:videoId>
                <title>A video</title>
                <link rel="alternate" href="https://www.youtube.com/watch?v=abcdefghijk"/>
              </entry>
            </feed>
        """.trimIndent()
        assertEquals(listOf("abcdefghijk"), YoutubeFeedParser.parse(feedUrl, xml).episodes.map { it.guid })
    }

    @Test
    fun ownerComesFromTheFeedChannelIdOrTheAuthorLink() {
        val ns = """xmlns:yt="http://www.youtube.com/xml/schemas/2015" xmlns="http://www.w3.org/2005/Atom""""
        assertEquals(
            FeedOwner("UCexampleChannel00000000", "Example Channel"),
            YoutubeFeedParser.owner("<feed $ns><yt:channelId>exampleChannel00000000</yt:channelId><author><name>Example Channel</name></author></feed>"),
        )
        assertEquals(
            FeedOwner("UCexampleChannel00000000", null),
            YoutubeFeedParser.owner("<feed $ns><author><uri>https://www.youtube.com/channel/UCexampleChannel00000000</uri></author></feed>"),
        )
        assertNull(YoutubeFeedParser.owner("<feed $ns><title>No owner</title></feed>"))
    }

    @Test
    fun missingFeedThrows() {
        assertFailsWith<FeedParseException> { YoutubeFeedParser.parse(feedUrl, "<html></html>") }
    }

    private val ATOM = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns:yt="http://www.youtube.com/xml/schemas/2015" xmlns:media="http://search.yahoo.com/mrss/" xmlns="http://www.w3.org/2005/Atom">
         <title>Example Channel</title>
         <author><name>Example Channel</name></author>
         <entry>
          <yt:videoId>_unboxing01</yt:videoId>
          <title>Unboxing the gadget</title>
          <published>2026-10-21T14:00:33+00:00</published>
          <link rel="alternate" href="https://www.youtube.com/watch?v=_unboxing01"/>
          <media:group>
           <media:title>Unboxing the gadget</media:title>
           <media:thumbnail url="https://i.ytimg.com/vi/_unboxing01/default.jpg" width="120" height="90"/>
           <media:thumbnail url="https://i.ytimg.com/vi/_unboxing01/hqdefault.jpg" width="480" height="360"/>
           <media:description>Only two things come in the box</media:description>
          </media:group>
         </entry>
         <entry>
          <id>yt:video:abcdefghijk</id>
          <title>Older</title>
          <published>2026-10-01T00:00:00Z</published>
         </entry>
         <entry>
          <yt:videoId>_unboxing01</yt:videoId>
          <title>duplicate</title>
         </entry>
         <entry>
          <title>no id</title>
         </entry>
        </feed>
    """.trimIndent()
}
