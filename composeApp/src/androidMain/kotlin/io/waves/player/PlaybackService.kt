package io.waves.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.waves.WavesApplication
import io.waves.data.UserSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Hosts the ExoPlayer and its MediaSession so playback survives the UI: Media3 turns it into a
 * foreground service with a media notification while playing, and wires lock-screen, Bluetooth and
 * headset controls. Audio focus and "becoming noisy" (headphones unplugged) are handled by ExoPlayer.
 *
 * The notification and lock screen get skip back and skip forward buttons where track buttons would be (an episode has
 * no previous or next track), stepping by the skip intervals in the app's settings and showing them on the icons.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var playbackCache: PlaybackCacheController? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settings get() = (application as? WavesApplication)?.graphOrNull?.settings?.settings
    private val skip get() = settings?.value ?: UserSettings()

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
            .setCallback(SkipCallback())
            .setMediaButtonPreferences(skipButtons(skip))
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            launch.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            builder.setSessionActivity(
                PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        }
        session = builder.build()
        settings?.let { flow ->
            scope.launch {
                flow.map { it.skipBackSeconds to it.skipForwardSeconds }.distinctUntilChanged().collect {
                    session?.setMediaButtonPreferences(skipButtons(flow.value))
                }
            }
        }
    }

    private inner class SkipCallback : MediaSession.Callback {
        @OptIn(UnstableApi::class)
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(SkipBack).add(SkipForward).build(),
                )
                .build()

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            val player = session.player
            val step = when (customCommand.customAction) {
                SkipBack.customAction -> -skip.skipBackSeconds * 1000L
                SkipForward.customAction -> skip.skipForwardSeconds * 1000L
                else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            val end = player.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
            player.seekTo((player.currentPosition + step).coerceIn(0L, end))
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    /** Skip back in the back slot and skip forward in the forward slot, each icon showing its step where one fits. */
    @OptIn(UnstableApi::class)
    private fun skipButtons(settings: UserSettings): List<CommandButton> = listOf(
        CommandButton.Builder(skipBackIcon(settings.skipBackSeconds))
            .setDisplayName("Back ${settings.skipBackSeconds} seconds")
            .setSessionCommand(SkipBack)
            .setSlots(CommandButton.SLOT_BACK)
            .build(),
        CommandButton.Builder(skipForwardIcon(settings.skipForwardSeconds))
            .setDisplayName("Forward ${settings.skipForwardSeconds} seconds")
            .setSessionCommand(SkipForward)
            .setSlots(CommandButton.SLOT_FORWARD)
            .build(),
    )

    @OptIn(UnstableApi::class)
    private fun skipBackIcon(seconds: Int) = when (seconds) {
        5 -> CommandButton.ICON_SKIP_BACK_5
        10 -> CommandButton.ICON_SKIP_BACK_10
        15 -> CommandButton.ICON_SKIP_BACK_15
        30 -> CommandButton.ICON_SKIP_BACK_30
        else -> CommandButton.ICON_SKIP_BACK
    }

    @OptIn(UnstableApi::class)
    private fun skipForwardIcon(seconds: Int) = when (seconds) {
        5 -> CommandButton.ICON_SKIP_FORWARD_5
        10 -> CommandButton.ICON_SKIP_FORWARD_10
        15 -> CommandButton.ICON_SKIP_FORWARD_15
        30 -> CommandButton.ICON_SKIP_FORWARD_30
        else -> CommandButton.ICON_SKIP_FORWARD
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
        scope.cancel()
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
        val SkipBack = SessionCommand("io.waves.SKIP_BACK", Bundle.EMPTY)
        val SkipForward = SessionCommand("io.waves.SKIP_FORWARD", Bundle.EMPTY)
    }
}
