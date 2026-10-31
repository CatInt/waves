package io.waves.refresh

import io.waves.model.Episode
import io.waves.model.Podcast
import kotlin.test.Test
import kotlin.test.assertEquals

class RefreshNotifierTest {
    private fun update(feed: String) = PodcastUpdate(
        Podcast(feed, feed),
        listOf(Episode(podcastFeedUrl = feed, guid = "1", title = "New", audioUrl = "https://example.com/1.mp3")),
    )

    @Test
    fun silencedShowsAreLeftOutAndNothingIsSentWhenAllAre() {
        val sent = mutableListOf<List<String>>()
        var silenced = setOf("b")
        val notifier = RefreshNotifier { updates -> sent += updates.map { it.podcast.feedUrl } }.except { silenced }

        notifier.notifyNewEpisodes(listOf(update("a"), update("b")))
        silenced = setOf("a", "b")
        notifier.notifyNewEpisodes(listOf(update("a"), update("b")))

        assertEquals(listOf(listOf("a")), sent)
    }
}
