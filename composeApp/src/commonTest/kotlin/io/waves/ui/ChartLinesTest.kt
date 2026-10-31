package io.waves.ui

import io.waves.model.Podcast
import io.waves.search.ChartEpisode
import io.waves.search.ChartShow
import kotlin.test.Test
import kotlin.test.assertEquals

class ChartLinesTest {
    private fun show(id: Long, genre: String?, rank: Int?) =
        ChartShow(Podcast("https://feed/$id", "Show $id", appleId = id), genre, rank)

    private fun episode(show: Long, id: Long) = ChartEpisode("Episode $id", "Show $show", null, show, appleId = id)

    private fun describe(lines: List<ChartLine>) = lines.map { line ->
        when (line) {
            is ChartLine.Section -> "== ${line.title}"
            is ChartLine.Shows -> line.shows.joinToString(",", "[", "]") { it.podcast.appleId.toString() }
            is ChartLine.Featured -> "e${line.episode.appleId} ${line.label}"
        }
    }

    @Test
    fun eachGenreHasUpToThreeRowsOfCoversAndEpisodesTogether() {
        // News: 10 shows ranked 1..10 (ids 1..10); show 2 has a top episode. Comedy: 3 shows, one row short of two.
        val news = (1L..10L).map { show(it, "News", it.toInt()) }
        val comedy = (21L..23L).map { show(it, "Comedy", (it - 20).toInt()) }
        // Show 9 is past News's three rows, show 23 in Comedy's row that can't be filled, show 99 in no genre:
        // their episodes have no room.
        val episodes = listOf(episode(2, 20), episode(9, 90), episode(23, 230), episode(99, 990))
        assertEquals(
            listOf(
                "== News", "[1,3]", "e20 #1 today", "[4,5]",
                "== Comedy", "[21,22]",
            ),
            describe(chartLines(news + comedy, episodes, across = 2)),
        )
    }

    @Test
    fun genresWithTopEpisodesComeFirstByTheirBestEpisode() {
        val news = (1L..4L).map { show(it, "News", it.toInt()) }
        val comedy = (11L..14L).map { show(it, "Comedy", (it - 10).toInt()) }
        val crime = (21L..24L).map { show(it, "True Crime", (it - 20).toInt()) }
        // The best episode is Crime's, then Comedy's; News has none.
        val episodes = listOf(episode(21, 210), episode(11, 110))
        assertEquals(
            listOf("== True Crime", "== Comedy", "== News"),
            describe(chartLines(news + comedy + crime, episodes, across = 2)).filter { it.startsWith("==") },
        )
    }

    @Test
    fun aGenresBetterRankedEpisodeComesFirst() {
        // Room for one episode: show 4's ranks above show 1's, so it's listed and show 1 stays among the covers.
        val shows = (1L..6L).map { show(it, "News", it.toInt()) }
        val episodes = listOf(episode(4, 40), episode(9, 90), episode(1, 10))
        assertEquals(
            listOf("== News", "[1,2]", "[3,5]", "e40 #1 today"),
            describe(chartLines(shows, episodes, across = 2)),
        )
    }

    @Test
    fun aGenresEpisodeRowsGoBestFirst() {
        // Show 1's episode ranks below show 3's; both fit, the better one in the first episode row.
        val shows = (1L..5L).map { show(it, "News", it.toInt()) }
        val episodes = listOf(episode(3, 30), episode(1, 10))
        assertEquals(
            listOf("== News", "[2,4]", "e30 #1 today", "e10 #2 today"),
            describe(chartLines(shows, episodes, across = 2)),
        )
    }

    @Test
    fun aGenreTooSmallForARowIsLeftOut() {
        assertEquals(emptyList(), describe(chartLines(listOf(show(1, "News", 1)), emptyList(), across = 3)))
    }

    @Test
    fun episodesAlternateInTheOrderTheyAppear() {
        val shows = (1L..6L).map { show(it, "News", it.toInt()) }
        // Shows 1 and 2 both have episodes: they follow the first row, [3,4], filling the genre's three rows.
        val lines = chartLines(shows, listOf(episode(1, 10), episode(2, 20)), across = 2)
        assertEquals(listOf(0, 1), lines.filterIsInstance<ChartLine.Featured>().map { it.order })
    }
}
