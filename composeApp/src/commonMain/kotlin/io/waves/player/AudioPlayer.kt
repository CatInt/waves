package io.waves.player

import io.waves.model.Episode
import kotlinx.coroutines.flow.StateFlow

/** Platform-neutral playback contract; Android and desktop each supply an implementation. */
interface AudioPlayer {
    val state: StateFlow<PlaybackState>

    fun play(episode: Episode, startPositionMs: Long = 0)

    /**
     * Like [play], additionally passing the podcast title so it can be shown as the artist in the
     * system media notification / lock screen. Implementations that don't care fall back to [play].
     */
    fun play(episode: Episode, startPositionMs: Long, podcastTitle: String?) = play(episode, startPositionMs)

    fun pause()
    fun resume()
    fun seekTo(positionMs: Long)

    /** Playback speed multiplier (1.0 = normal). Clamped to [MIN_SPEED]..[MAX_SPEED]. */
    fun setSpeed(speed: Float) {}

    fun release()

    companion object {
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 3.0f
    }
}

data class PlaybackState(
    val episode: Episode? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** Playback speed multiplier (1.0 = normal). */
    val speed: Float = 1f,
    /** True while waiting for network data (playback intends to run but is stalled/loading). */
    val isBuffering: Boolean = false,
    /** True once the current episode played to its end. */
    val isEnded: Boolean = false,
    /** Human-readable error of the last failed load/playback, null when fine. */
    val error: String? = null,
)
