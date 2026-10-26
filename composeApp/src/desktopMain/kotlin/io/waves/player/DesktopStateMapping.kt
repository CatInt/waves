package io.waves.player

import javafx.scene.media.MediaPlayer
import javafx.util.Duration

/** Pure mapping from JavaFX media status to [PlaybackState] flags. */
internal object DesktopStateMapping {

    data class Flags(val isPlaying: Boolean, val isBuffering: Boolean)

    /**
     * [wantPlaying] is the user's intent (play/resume vs pause). `isPlaying` follows that intent so the UI
     * shows "pause" while loading/stalled; `isBuffering` flags that sound isn't actually coming out yet.
     */
    fun flags(status: MediaPlayer.Status?, wantPlaying: Boolean, ended: Boolean): Flags = when {
        ended -> Flags(isPlaying = false, isBuffering = false)
        status == MediaPlayer.Status.HALTED || status == MediaPlayer.Status.DISPOSED -> Flags(false, false)
        status == MediaPlayer.Status.PLAYING -> Flags(isPlaying = wantPlaying, isBuffering = false)
        status == null ||
            status == MediaPlayer.Status.UNKNOWN ||
            status == MediaPlayer.Status.READY ||
            status == MediaPlayer.Status.STALLED -> Flags(isPlaying = wantPlaying, isBuffering = wantPlaying)
        else -> Flags(isPlaying = wantPlaying, isBuffering = false) // PAUSED, STOPPED
    }

    /** JavaFX durations may be UNKNOWN (NaN) or INDEFINITE (infinite, e.g. live streams). */
    fun millisOrNull(duration: Duration?): Long? {
        if (duration == null || duration.isUnknown || duration.isIndefinite) return null
        val ms = duration.toMillis()
        return if (ms.isNaN() || ms.isInfinite() || ms < 0) null else ms.toLong()
    }
}
