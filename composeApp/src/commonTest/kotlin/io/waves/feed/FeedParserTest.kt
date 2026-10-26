package io.waves.feed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeedParserTest {
    private val feed = FeedParser.parse(FeedFixtures.FEED_URL, FeedFixtures.rss)

    @Test
    fun channelMetadata() {
        val p = feed.podcast
        assertEquals(FeedFixtures.FEED_URL, p.feedUrl)
        assertEquals("Cat & Mouse's “Show”", p.title)
        assertEquals("Cat & Mouse", p.author)
        assertEquals("A show about <b>cats</b> & mice.", p.description)
        assertEquals("https://img.example.com/itunes-3000.jpg?a=1&b=2", p.artworkUrl)
        assertNull(p.appleId)
    }

    @Test
    fun skipsItemsWithoutEnclosureAndDuplicateGuids() {
        assertEquals(listOf("177fe27d-cc52-4fad-bc7d-3a2ba67a0949", "https://cdn.example.com/ep2.m4a", "ep3", "ep5"), feed.episodes.map { it.guid })
        assertTrue(feed.episodes.all { it.podcastFeedUrl == FeedFixtures.FEED_URL })
    }

    @Test
    fun firstEpisodeFields() {
        val e = feed.episodes[0]
        assertEquals("Episode 1: <Pilot>", e.title)
        assertEquals("https://cdn.example.com/ep1.mp3?x=1&y=2", e.audioUrl)
        assertEquals(1_792_317_600_000L, e.publishedAtEpochMs) // 2026-10-18T10:00:00Z
        assertEquals(3723L, e.durationSeconds)
        assertEquals("<p>The comedian “Alex” &amp; friends</p><p>&nbsp;</p>", e.description) // CDATA is verbatim
        assertEquals("https://img.example.com/ep1.jpg", e.artworkUrl)
    }

    @Test
    fun fallbacks() {
        val e2 = feed.episodes[1]
        assertEquals(2730L, e2.durationSeconds)
        assertEquals("Only an itunes summary", e2.description)
        assertEquals(1_767_619_800_000L, e2.publishedAtEpochMs) // 2026-01-05T13:30:00Z (08:30 EST)
        assertNull(e2.artworkUrl)

        val e3 = feed.episodes[2]
        assertEquals("Encoded <i>only</i>", e3.description)
        assertEquals(1_772_366_400_000L, e3.publishedAtEpochMs) // ISO 8601
        assertEquals(3600L, e3.durationSeconds)
    }

    @Test
    fun sloppyItemIsKept() {
        val e = feed.episodes[3]
        assertNull(e.publishedAtEpochMs)
        assertNull(e.durationSeconds)
        assertEquals("Unescaped bold HTML & a stray < sign &unknown; entity", e.description)
    }

    @Test
    fun nonStandardNamespacePrefixAndRssImageFallback() {
        val f = FeedParser.parse("u", FeedFixtures.oddPrefix)
        assertEquals("Prefix Author", f.podcast.author)
        assertEquals("https://img.example.com/fallback.png", f.podcast.artworkUrl)
        assertEquals(90L, f.episodes.single().durationSeconds)
        assertEquals("https://cdn.example.com/1.mp3", f.episodes.single().guid)
    }

    @Test
    fun truncatedFeedKeepsCompleteItems() {
        val f = FeedParser.parse("u", FeedFixtures.truncated)
        assertEquals("Cut", f.podcast.title)
        assertEquals(listOf("a"), f.episodes.map { it.guid })
    }

    @Test
    fun nonFeedThrows() {
        assertFailsWith<FeedParseException> { FeedParser.parse("u", "<html><body>Not found</body></html>") }
        assertFailsWith<FeedParseException> { FeedParser.parse("u", "") }
    }
}
