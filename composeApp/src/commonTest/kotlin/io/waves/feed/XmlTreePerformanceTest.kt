package io.waves.feed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime
import kotlin.test.assertTrue

class XmlTreePerformanceTest {
    @Test
    fun consumedItemsAreNotRetainedInTheDocumentTree() {
        val items = 20_000
        val xml = buildString {
            append("<rss><channel><title>Big</title>")
            repeat(items) { n ->
                append("<item><guid>g$n</guid><description>Text <b>bold</b></description></item>\n")
            }
            append("<description>Channel metadata after the items</description></channel></rss>")
        }
        var consumed = 0
        val doc = XmlTree.parse(xml, consumeChildrenOf = "channel") { item ->
            if (item.name != "item") return@parse false
            assertEquals("g${consumed++}", item.childText("guid"))
            assertEquals("Text bold", item.childText("description"))
            true
        }

        assertEquals(items, consumed)
        val channel = doc.find("channel")!!
        assertEquals(listOf("title", "description"), channel.children.map { it.name })
        assertEquals("Channel metadata after the items", channel.childText("description"))
    }

    @Test
    fun consumerClosesSelfClosingAndTruncatedItemsAndOnlyConsumesTheFirstChannel() {
        val consumed = mutableListOf<String>()
        val doc = XmlTree.parse(
            "<rss><channel><item/><item><guid>a</guid></channel>" +
                "<channel><item><guid>untouched</guid></item></channel></rss>",
            consumeChildrenOf = "channel",
        ) { item ->
            if (item.name != "item") return@parse false
            consumed += item.childText("guid").orEmpty()
            true
        }
        assertEquals(listOf("", "a"), consumed)
        val channels = doc.child("rss")!!.children("channel")
        assertTrue(channels[0].children.isEmpty())
        assertEquals("untouched", channels[1].child("item")!!.childText("guid"))

        var truncated = ""
        XmlTree.parse("<rss><channel><item><guid>truncated</guid>", consumeChildrenOf = "channel") {
            truncated = it.childText("guid").orEmpty()
            true
        }
        assertEquals("truncated", truncated)
    }

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
