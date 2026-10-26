package io.waves.feed

import io.waves.model.Episode
import io.waves.model.Podcast

data class Feed(val podcast: Podcast, val episodes: List<Episode>)

/** Fetches and parses RSS feeds. */
interface FeedRepository {
    suspend fun fetch(feedUrl: String): Feed
}
