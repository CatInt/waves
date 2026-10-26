package io.waves.search

import io.waves.model.Podcast

/** Podcast discovery backed by Apple Podcasts (iTunes Search / lookup / charts). */
interface PodcastDirectory {
    suspend fun search(term: String, limit: Int = 30): List<Podcast>

    /**
     * Search the [country] store (ISO 3166-1 alpha-2, e.g. "gb"). Implementations without per-store search fall
     * back to [search].
     */
    suspend fun search(term: String, country: String, limit: Int = 30): List<Podcast> = search(term, limit)
    suspend fun topPodcasts(country: String = "us", limit: Int = 50): List<Podcast>
    /** Resolve an Apple collection id (e.g. from a podcasts.apple.com URL) to a podcast with its feed URL. */
    suspend fun lookup(appleId: Long): Podcast?
}
