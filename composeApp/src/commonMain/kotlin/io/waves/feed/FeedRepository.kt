package io.waves.feed

import io.waves.model.Episode
import io.waves.model.Podcast

data class Feed(
    val podcast: Podcast,
    val episodes: List<Episode>,
    /** False when only a prefix was read; unlisted episodes must not be treated as removed. */
    val isComplete: Boolean = true,
)

/** Fetches and parses RSS feeds. */
interface FeedRepository {
    suspend fun fetch(feedUrl: String): Feed

    /**
     * The feed's newest episodes only, from about its first [maxBytes]: feeds list newest first and run to megabytes,
     * so this finds a recent episode at a fraction of the cost. Repositories that can't read part of a feed fetch it
     * all.
     */
    suspend fun fetchLatest(feedUrl: String, maxBytes: Int = LATEST_BYTES): Feed = fetch(feedUrl)

    companion object {
        /** Enough for a few dozen items of even a wordy feed. */
        const val LATEST_BYTES = 128 * 1024
    }
}
