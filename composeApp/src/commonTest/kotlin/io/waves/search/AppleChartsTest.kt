package io.waves.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppleChartsTest {
    @Test
    fun genreChartIdsComeInChartOrderAndALoneEntryCounts() {
        val two = """{"feed":{"entry":[{"id":{"label":"x","attributes":{"im:id":"1200361736"}}},{"id":{"attributes":{"im:id":"42"}}}]}}"""
        assertEquals(listOf(1200361736L, 42L), genreChartIds(two))
        val one = """{"feed":{"entry":{"id":{"attributes":{"im:id":"7"}}}}}"""
        assertEquals(listOf(7L), genreChartIds(one))
        assertEquals(emptyList(), genreChartIds("""{"feed":{}}"""))
    }

    @Test
    fun aChartEpisodeKnowsItsShowFromTheLinkAndTakesLargerArtwork() {
        val entry = ApplePodcastDirectory.EpisodeChartEntry(
            name = "Trump Puts the Mid in Midterms",
            artistName = "Pod Save America",
            artworkUrl100 = "https://is1-ssl.mzstatic.com/image/thumb/a/100x100bb.png",
            url = "https://podcasts.apple.com/us/podcast/trump-puts-the-mid-in-midterms/id1192761536?i=1000798822096",
        )
        assertEquals(
            ChartEpisode("Trump Puts the Mid in Midterms", "Pod Save America", "https://is1-ssl.mzstatic.com/image/thumb/a/600x600bb.png", 1192761536L),
            entry.toChartEpisode(),
        )
        assertNull(entry.copy(url = "https://example.com/no-id").toChartEpisode())
    }
}
