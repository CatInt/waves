package io.waves.player

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Hosts the ExoPlayer and its MediaSession so playback survives the UI: Media3 turns it into a
 * foreground service with a media notification while playing, and wires lock-screen, Bluetooth and
 * headset controls. Audio focus and "becoming noisy" (headphones unplugged) are handled by ExoPlayer.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var playbackCache: PlaybackCacheController? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        // Podcast enclosures typically hop through http->https tracking redirects.
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
        // NoOp evictor: LocalMediaCleaner drops cache bytes, and it refuses to while offline mode is on.
        val caching = runCatching { PlaybackCacheController(this, httpFactory) }.getOrNull()
        playbackCache = caching
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                caching?.mediaSourceFactory
                    ?: androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                        androidx.media3.datasource.DefaultDataSource.Factory(this, httpFactory),
                    ),
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(15_000)
            .setSeekForwardIncrementMs(30_000)
            .build()
        caching?.attachTo(player)

        val builder = MediaSession.Builder(this, player)
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            launch.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            builder.setSessionActivity(
                PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        }
        session = builder.build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Swiping the app away while paused stops the service; while playing it keeps going. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        // ExoPlayer keeps playWhenReady = true after an episode ends (STATE_ENDED) or fails (STATE_IDLE).
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0 ||
            player.playbackState == Player.STATE_ENDED || player.playbackState == Player.STATE_IDLE
        ) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        playbackCache?.release()
        playbackCache = null
        super.onDestroy()
    }

    private companion object {
        const val USER_AGENT = "Waves/1.0 (Android; podcast player)"
    }
}
