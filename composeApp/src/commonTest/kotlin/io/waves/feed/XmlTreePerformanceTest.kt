package io.waves.feed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime
import kotlin.test.assertTrue

class XmlTreePerformanceTest {
    /** Entity decoding must not rescan the rest of the document for every text run (quadratic on big feeds). */
    @Test
    fun largeFeedWithoutEntitiesParsesInLinearTime() {
        val items = 20_000
        val xml = buildString {
            append("<rss><channel><title>Big</title>\n")
            repeat(items) { n ->
                append("<item>\n  <title>Episode $n</title>\n  <guid>g$n</guid>\n")
                append("  <enclosure url=\"https://cdn.example.com/$n.mp3\" type=\"audio/mpeg\"/>\n")
                append("  <description>Plain text description number $n</description>\n</item>\n")
            }
            append("</channel></rss>")
        }
        lateinit var feed: Feed
        val took = measureTime { feed = FeedParser.parse("https://example.com/feed", xml) }
        assertEquals(items, feed.episodes.size)
        assertTrue(took < 5.seconds, "parsing ${xml.length} chars took $took")
    }

    @Test
    fun ampersandFarFromSemicolonStaysLiteral() {
        val doc = XmlTree.parse("<a>Cat & Mouse ${"x".repeat(50)}; end</a>")
        assertEquals("Cat & Mouse ${"x".repeat(50)}; end", doc.child("a")!!.text)
    }
}
