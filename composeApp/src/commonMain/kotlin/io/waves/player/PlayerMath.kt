package io.waves.player

import io.waves.model.Episode

/** Small pure helpers shared by the platform players (kept here so they are unit-testable). */
internal object PlayerMath {

    /**
     * Picks a usable duration: the engine's value when known (> 0), otherwise the feed's
     * `itunes:duration` hint, otherwise 0 (unknown).
     */
    fun durationMs(engineDurationMs: Long?, episode: Episode?): Long = when {
        engineDurationMs != null && engineDurationMs > 0 -> engineDurationMs
        episode?.durationSeconds != null && episode.durationSeconds > 0 -> episode.durationSeconds * 1000
        else -> 0
    }

    /** Clamps a seek target into [0, duration] (or just >= 0 when the duration is unknown). */
    fun clampSeek(positionMs: Long, durationMs: Long): Long {
        val nonNegative = positionMs.coerceAtLeast(0)
        return if (durationMs > 0) nonNegative.coerceAtMost(durationMs) else nonNegative
    }

    fun clampSpeed(speed: Float): Float =
        if (speed.isNaN()) 1f else speed.coerceIn(AudioPlayer.MIN_SPEED, AudioPlayer.MAX_SPEED)

    /** Artist line for media notifications when the podcast title wasn't supplied: the feed's host. */
    fun fallbackArtist(feedUrl: String): String =
        feedUrl.substringAfter("://", feedUrl).substringBefore('/').substringBefore(':').removePrefix("www.")
}
