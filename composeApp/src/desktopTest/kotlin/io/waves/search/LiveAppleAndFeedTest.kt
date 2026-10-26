package io.waves.search

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.waves.feed.RssFeedRepository
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Hits the real network. Skipped unless WAVES_LIVE_TESTS=1, e.g.
 * `WAVES_LIVE_TESTS=1 ./gradlew :composeApp:desktopTest --tests 'io.waves.search.LiveAppleAndFeedTest' --rerun`
 */
class LiveAppleAndFeedTest {
    private val enabled = System.getenv("WAVES_LIVE_TESTS") == "1"

    @Test
    fun searchTopChartsAndFetchFeed() = runBlocking {
        if (!enabled) return@runBlocking
        HttpClient(Java).use { client ->
            val directory = ApplePodcastDirectory(client)
            val results = directory.search("the daily", 5)
            println("search: " + results.joinToString { "${it.title} (${it.appleId}) ${it.feedUrl}" })
            val daily = results.first { it.appleId == 1200361736L }

            val top = directory.topPodcasts("us", 10)
            println("top: " + top.joinToString { it.title })
            assertTrue(top.size >= 5)

            val linked = directory.lookup(parseApplePodcastsUrl("https://podcasts.apple.com/us/podcast/the-daily/id1200361736")!!)
            assertTrue(linked?.feedUrl == daily.feedUrl)

            val feed = RssFeedRepository(client).fetch(daily.feedUrl)
            println("feed: ${feed.podcast.title} by ${feed.podcast.author}, ${feed.episodes.size} episodes, art=${feed.podcast.artworkUrl}")
            feed.episodes.take(3).forEach {
                println("  ${it.title} | ${it.publishedAtEpochMs} | ${it.durationSeconds}s | ${it.audioUrl.take(80)}")
            }
            assertTrue(feed.episodes.isNotEmpty())
            assertTrue(feed.episodes.all { it.publishedAtEpochMs != null })
            assertTrue(feed.episodes.all { it.durationSeconds != null })
        }
    }

    @Test
    fun parseTopChartFeeds() = runBlocking {
        if (!enabled) return@runBlocking
        HttpClient(Java).use { client ->
            val repo = RssFeedRepository(client)
            val top = ApplePodcastDirectory(client).topPodcasts("us", 15)
            for (p in top) {
                val feed = repo.fetch(p.feedUrl)
                val eps = feed.episodes
                println(
                    "${p.title}: ${eps.size} eps, undated=${eps.count { it.publishedAtEpochMs == null }}, " +
                        "noDuration=${eps.count { it.durationSeconds == null }}, art=${feed.podcast.artworkUrl != null}",
                )
                assertTrue(eps.isNotEmpty(), p.feedUrl)
                assertTrue(eps.all { it.publishedAtEpochMs != null }, "undated episodes in ${p.feedUrl}")
            }
        }
    }
}
