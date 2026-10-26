package io.waves.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import io.waves.model.Episode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import java.util.concurrent.Executor

/**
 * Android [AudioPlayer]: a thin [MediaController] client of [PlaybackService], which owns the ExoPlayer.
 * Playback therefore continues in the background with a media notification, lock-screen and headset
 * controls. Create one per process (e.g. app-level singleton) from the main thread; methods may be
 * called from any thread (they hop to the main looper).
 *
 * [release] only disconnects this client — an ongoing playback keeps running in the service until the
 * user pauses/dismisses it. A new instance re-attaches to it (the episode is restored from the session).
 */
class AndroidAudioPlayer(
    context: Context,
    /** Ordinary enclosures pass through. YouTube watch URLs become a direct audio stream. */
    private val locate: suspend (String) -> String = { it },
) : AudioPlayer {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { if (Looper.myLooper() == Looper.getMainLooper()) it.run() else mainHandler.post(it) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    // ---- main-thread confined ----
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val connection = ControllerConnection<MediaController>(
        startConnect = ::startConnect,
        isConnected = { it.isConnected },
        onDetached = ::onControllerDetached,
    )
    private var currentEpisode: Episode? = null
    private var currentPodcastTitle: String? = null
    private var playGeneration = 0L
    private var wantPlaying = false
    private var released = false
    private val ticker = PositionTicker(scope) { publish() }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish()
    }

    init {
        // Connect eagerly once to re-attach to playback that outlived a previous UI/process instance.
        // Later disconnects (service crash / system kill) reconnect lazily on the next command.
        onMain { if (!released) connection.connect() }
    }

    /** Main thread: builds a controller for [PlaybackService] and reports the outcome to [connection]. */
    private fun startConnect() {
        val future = MediaController.Builder(
            appContext,
            SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java)),
        )
            .setApplicationLooper(Looper.getMainLooper())
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) {
                    onMain { connection.onDisconnected(controller) }
                }
            })
            .buildAsync()
        controllerFuture = future
        future.addListener({
            if (controllerFuture !== future) return@addListener
            val c = try {
                future.get()
            } catch (e: Exception) {
                controllerFuture = null
                connection.onFailed()
                ticker.stop()
                _state.update { it.copy(isPlaying = false, isBuffering = false, error = "Playback service unavailable: ${e.message}") }
                return@addListener
            }
            c.addListener(listener)
            // Re-attach to playback that outlived a previous UI/process instance.
            if (currentEpisode == null) currentEpisode = c.currentMediaItem?.let(::episodeOf)
            if (!connection.onConnected(c)) {
                c.removeListener(listener)
                MediaController.releaseFuture(future)
                return@addListener
            }
            publish()
        }, mainExecutor)
    }

    /** Main thread: the controller went away; keep the episode/position but stop claiming we're playing. */
    private fun onControllerDetached(c: MediaController) {
        c.removeListener(listener)
        controllerFuture?.let(MediaController::releaseFuture)
        controllerFuture = null
        ticker.stop()
        if (!released) _state.update { it.copy(isPlaying = false, isBuffering = false) }
    }

    override fun play(episode: Episode, startPositionMs: Long) = play(episode, startPositionMs, null)

    override fun play(episode: Episode, startPositionMs: Long, podcastTitle: String?) =
        load(episode, startPositionMs, podcastTitle)

    /**
     * Shows [episode] immediately (buffering), then opens it. YouTube watch URLs are resolved to an audio
     * stream first; the episode stored on the media item keeps the stable watch URL.
     */
    private fun load(episode: Episode, startPositionMs: Long, podcastTitle: String?) {
        val gen = ++playGeneration
        wantPlaying = true
        val start = startPositionMs.coerceAtLeast(0)
        _state.update {
            PlaybackState(
                episode = episode,
                isPlaying = true,
                positionMs = start,
                durationMs = PlayerMath.durationMs(null, episode),
                speed = it.speed,
                isBuffering = true,
            )
        }
        scope.launch {
            currentEpisode = episode
            currentPodcastTitle = podcastTitle
            val mediaUrl = try {
                locate(episode.audioUrl)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen == playGeneration) {
                    wantPlaying = false
                    _state.update { it.copy(isPlaying = false, isBuffering = false, error = e.message ?: "Couldn't open this episode") }
                }
                return@launch
            }
            if (gen != playGeneration) return@launch
            withController { c ->
                if (gen != playGeneration) return@withController
                c.setMediaItem(mediaItemOf(episode, podcastTitle, mediaUrl), start)
                c.setPlaybackSpeed(_state.value.speed)
                c.prepare()
                if (wantPlaying) c.play()
            }
        }
    }

    override fun pause() {
        wantPlaying = false
        _state.update { it.copy(isPlaying = false, isBuffering = false) }
        withController { it.pause() }
    }

    override fun resume() {
        val episode = currentEpisode ?: _state.value.episode ?: return
        wantPlaying = true
        _state.update { it.copy(isPlaying = true, isEnded = false, error = null) }
        withController { c ->
            if (c.mediaItemCount == 0) {
                // The service died and came back empty (crash / system kill): reload where we left off.
                val position = if (_state.value.isEnded) 0 else _state.value.positionMs
                load(episode, position, currentPodcastTitle)
            } else {
                if (c.playbackState == Player.STATE_ENDED) c.seekTo(0)
                if (c.playbackState == Player.STATE_IDLE) c.prepare() // after an error: retry
                c.play()
            }
        }
    }

    override fun seekTo(positionMs: Long) {
        val target = PlayerMath.clampSeek(positionMs, _state.value.durationMs)
        _state.update { it.copy(positionMs = target, isEnded = false) }
        withController { it.seekTo(target) }
    }

    override fun setSpeed(speed: Float) {
        val clamped = PlayerMath.clampSpeed(speed)
        _state.update { it.copy(speed = clamped) }
        withController { it.setPlaybackSpeed(clamped) }
    }

    override fun release() = onMain {
        if (released) return@onMain
        released = true
        playGeneration++
        ticker.stop()
        scope.cancel()
        connection.release()
        controllerFuture?.let(MediaController::releaseFuture)
        controllerFuture = null
    }

    /** Main thread: copies the controller's state into [state] and runs the ticker while playing. */
    private fun publish() {
        val c = connection.current ?: return
        if (c.mediaItemCount == 0) {
            // Nothing loaded (fresh or restarted service): keep our episode/position, just reflect idle.
            ticker.stop()
            // Our speed survives a service restart (it's re-applied on resume); otherwise adopt the service's.
            val speed = if (currentEpisode == null) c.playbackParameters.speed else _state.value.speed
            _state.update { it.copy(isPlaying = false, isBuffering = false, speed = speed) }
            return
        }
        // Keep our episode in sync if another controller (e.g. the notification) changed the item.
        val itemEpisode = c.currentMediaItem?.let(::episodeOf)
        if (itemEpisode != null && itemEpisode.id != currentEpisode?.id) currentEpisode = itemEpisode
        val episode = currentEpisode
        val error = c.playerError
        val flags = AndroidStateMapping.flags(c.playbackState, c.playWhenReady, error != null)
        val duration = PlayerMath.durationMs(AndroidStateMapping.durationOrNull(c.duration), episode)
        val position = if (flags.isEnded) duration else c.currentPosition
        _state.value = PlaybackState(
            episode = episode,
            isPlaying = flags.isPlaying,
            positionMs = PlayerMath.clampSeek(position, duration),
            durationMs = duration,
            speed = c.playbackParameters.speed,
            isBuffering = flags.isBuffering,
            isEnded = flags.isEnded,
            error = error?.let { it.message ?: it.errorCodeName },
        )
        ticker.setRunning(flags.isPlaying)
    }

    private fun withController(block: (MediaController) -> Unit) = onMain {
        if (!released) connection.run(block)
    }

    private fun onMain(block: () -> Unit) = mainExecutor.execute(block)

    internal companion object {
        const val EXTRA_EPISODE_JSON = "io.waves.episode"
        val json = Json { ignoreUnknownKeys = true }

        fun mediaItemOf(episode: Episode, podcastTitle: String?, mediaUrl: String = episode.audioUrl): MediaItem {
            val artist = podcastTitle ?: PlayerMath.fallbackArtist(episode.podcastFeedUrl)
            val metadata = MediaMetadata.Builder()
                .setTitle(episode.title)
                .setArtist(artist)
                .setAlbumTitle(artist)
                .setArtworkUri(episode.artworkUrl?.let(Uri::parse))
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
                .setExtras(Bundle().apply { putString(EXTRA_EPISODE_JSON, json.encodeToString(Episode.serializer(), episode)) })
                .build()
            return MediaItem.Builder()
                .setMediaId(episode.id.toString())
                // Cache key so streamed audio is recorded against the episode, not the redirected enclosure URL.
                .setCustomCacheKey(episode.id.toString())
                .setUri(mediaUrl)
                .setMediaMetadata(metadata)
                .build()
        }

        internal fun episodeOf(item: MediaItem): Episode? = item.mediaMetadata.extras?.getString(EXTRA_EPISODE_JSON)?.let {
            runCatching { json.decodeFromString(Episode.serializer(), it) }.getOrNull()
        }
    }
}
