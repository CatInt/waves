package io.waves.youtube

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Hits the real network. Skipped unless WAVES_LIVE_TESTS=1, e.g.
 * `WAVES_LIVE_TESTS=1 ./gradlew :composeApp:desktopTest --tests io.waves.youtube.LiveYoutubeFeedTest --rerun`
 */
class LiveYoutubeFeedTest {
    private val enabled = System.getenv("WAVES_LIVE_TESTS") == "1"

    @Test
    fun mkbhdChannelIsAFeedOfEpisodes() = runBlocking {
        if (!enabled) return@runBlocking
        HttpClient(Java).use { client ->
            val podcast = YoutubeChannels(client).resolve("https://www.youtube.com/@mkbhd")
            assertEquals("https://www.youtube.com/feeds/videos.xml?channel_id=UCBJycsmduvYEL83R_U4JriQ", podcast.feedUrl)
            assertEquals("Marques Brownlee", podcast.title)
            assertTrue(!podcast.artworkUrl.isNullOrBlank())

            val feed = YoutubeFeedRepository(client).fetch(podcast.feedUrl)
            println("youtube: ${feed.podcast.title}, ${feed.episodes.size} episodes, art=${feed.podcast.artworkUrl}")
            feed.episodes.take(3).forEach { println("  ${it.title} | ${it.audioUrl}") }
            assertTrue(feed.episodes.isNotEmpty())
            assertTrue(feed.episodes.all { it.audioUrl.startsWith("https://www.youtube.com/watch?v=") })
            assertTrue(feed.episodes.all { it.podcastFeedUrl == podcast.feedUrl })
        }
    }

    @Test
    fun lexFridmanPlaylistIsAShowByItsChannel() = runBlocking {
        if (!enabled) return@runBlocking
        HttpClient(Java).use { client ->
            val link = "https://www.youtube.com/playlist?list=PLrAXtmErZgOdP_8GztsuKi9nrraNbKKp4"
            val channels = YoutubeChannels(client)
            val podcast = channels.resolve(link)
            println("playlist: ${podcast.title} by ${podcast.author}, art=${podcast.artworkUrl}")
            assertEquals(YoutubeUrls.playlistFeedUrl("PLrAXtmErZgOdP_8GztsuKi9nrraNbKKp4"), podcast.feedUrl)
            assertEquals("Lex Fridman", podcast.author)
            assertTrue(!podcast.artworkUrl.isNullOrBlank())

            val feed = YoutubeFeedRepository(client).fetch(podcast.feedUrl)
            feed.episodes.take(3).forEach { println("  ${it.title} | ${it.durationSeconds}s | ${it.audioUrl}") }
            assertTrue(feed.episodes.isNotEmpty())
            assertTrue(feed.episodes.count { it.durationSeconds != null } >= feed.episodes.size - 1)

            val creator = channels.creator(podcast.feedUrl)!!
            assertEquals("https://www.youtube.com/feeds/videos.xml?channel_id=UCSHZKyawb77ixDdsGog4iWA", creator.feedUrl)
        }
    }
}
