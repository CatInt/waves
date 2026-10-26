package io.waves.player

import io.waves.model.Episode
import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerMathTest {
    private val episode = Episode(guid = "g", podcastFeedUrl = "https://www.example.com:443/feed.xml", title = "t", audioUrl = "u", durationSeconds = 60)

    @Test
    fun durationPrefersEngineThenFeedHint() {
        assertEquals(1234, PlayerMath.durationMs(1234, episode))
        assertEquals(60_000, PlayerMath.durationMs(null, episode))
        assertEquals(60_000, PlayerMath.durationMs(-1, episode))
        assertEquals(0, PlayerMath.durationMs(null, episode.copy(durationSeconds = null)))
        assertEquals(0, PlayerMath.durationMs(0, null))
    }

    @Test
    fun clampSeek() {
        assertEquals(0, PlayerMath.clampSeek(-5, 1000))
        assertEquals(1000, PlayerMath.clampSeek(5000, 1000))
        assertEquals(500, PlayerMath.clampSeek(500, 1000))
        assertEquals(5000, PlayerMath.clampSeek(5000, 0)) // unknown duration: no upper bound
    }

    @Test
    fun clampSpeed() {
        assertEquals(1f, PlayerMath.clampSpeed(Float.NaN))
        assertEquals(AudioPlayer.MIN_SPEED, PlayerMath.clampSpeed(0.1f))
        assertEquals(AudioPlayer.MAX_SPEED, PlayerMath.clampSpeed(10f))
        assertEquals(1.5f, PlayerMath.clampSpeed(1.5f))
    }

    @Test
    fun fallbackArtistIsFeedHost() {
        assertEquals("example.com", PlayerMath.fallbackArtist(episode.podcastFeedUrl))
        assertEquals("feeds.example.org", PlayerMath.fallbackArtist("https://feeds.example.org/123456/podcast.xml"))
    }
}
