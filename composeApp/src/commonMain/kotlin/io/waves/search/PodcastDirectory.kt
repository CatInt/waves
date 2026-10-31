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

    /**
     * The top shows by genre, each with its genre and its place on that genre's chart, genre by genre; implementations
     * without genre charts give [topPodcasts] without them.
     */
    suspend fun topShows(country: String = "us", limit: Int = 50): List<ChartShow> =
        topPodcasts(country, limit).map { ChartShow(it) }

    /** The top episodes now; implementations without an episode chart return none. */
    suspend fun topEpisodes(country: String = "us", limit: Int = 50): List<ChartEpisode> = emptyList()

    /** The chart [episode] as one that plays, with its show; null when it can't be found (or there's no way to). */
    suspend fun playable(episode: ChartEpisode, country: String = "us"): Pair<Podcast, io.waves.model.Episode>? = null
    /** Resolve an Apple collection id (e.g. from a podcasts.apple.com URL) to a podcast with its feed URL. */
    suspend fun lookup(appleId: Long): Podcast?
}

/** An episode on a chart, with the Apple id of its show (which a [PodcastDirectory.lookup] turns into the show). */
@kotlinx.serialization.Serializable
data class ChartEpisode(
    val title: String,
    val showTitle: String,
    val artworkUrl: String?,
    val showAppleId: Long,
    /** Apple's id for the episode itself (0 when unknown). */
    val appleId: Long = 0,
)

/** A show on a chart, with its [genre] and where it stands on that genre's chart ([genreRank], 1 the top; null off it). */
@kotlinx.serialization.Serializable
data class ChartShow(
    val podcast: Podcast,
    val genre: String? = null,
    val genreRank: Int? = null,
)
