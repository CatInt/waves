package io.waves

import io.waves.model.Episode
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ModelTest {
    @Test
    fun episodeRoundTripsThroughJson() {
        val episode = Episode(
            guid = "ep-1",
            podcastFeedUrl = "https://example.com/feed.xml",
            title = "Pilot",
            audioUrl = "https://example.com/ep1.mp3",
        )
        val json = Json.encodeToString(Episode.serializer(), episode)
        assertEquals(episode, Json.decodeFromString(Episode.serializer(), json))
    }
}
