package io.waves.model

import kotlinx.serialization.Serializable

@Serializable
data class Podcast(
    val feedUrl: String,
    val title: String,
    val author: String? = null,
    val description: String? = null,
    val artworkUrl: String? = null,
    /** Apple Podcasts collection id, when the podcast came from the Apple directory. */
    val appleId: Long? = null,
)

@Serializable
data class Episode(
    val guid: String,
    val podcastFeedUrl: String,
    val title: String,
    val audioUrl: String,
    val publishedAtEpochMs: Long? = null,
    val durationSeconds: Long? = null,
    val description: String? = null,
    val artworkUrl: String? = null,
) {
    /** Stable cross-device key: guids are only unique within a feed. */
    val id: EpisodeId get() = EpisodeId(podcastFeedUrl, guid)
}

@Serializable
data class EpisodeId(val feedUrl: String, val guid: String) {
    override fun toString() = "$feedUrl#$guid"
}

/** Last known playback position of one episode. Merged across devices last-writer-wins on [updatedAtEpochMs]. */
@Serializable
data class PlaybackProgress(
    val episodeId: EpisodeId,
    val positionMs: Long,
    val durationMs: Long,
    val completed: Boolean = false,
    val updatedAtEpochMs: Long,
)

/** One listening session; history is the union of entries from all devices. */
@Serializable
data class HistoryEntry(
    val episodeId: EpisodeId,
    val episodeTitle: String,
    val podcastTitle: String,
    val playedAtEpochMs: Long,
    val deviceId: String,
)
