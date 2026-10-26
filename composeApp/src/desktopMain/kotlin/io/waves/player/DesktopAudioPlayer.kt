package io.waves.player

import io.waves.model.Episode
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.util.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Desktop (JVM/macOS) player backed by JavaFX Media (bundled native GStreamer-lite/AVFoundation backend,
 * so no external install such as VLC is needed). Streams http(s) MP3 and AAC/M4A, supports seek and
 * playback speed (JavaFX `rate`).
 *
 * Threading: all [MediaPlayer] access happens on the JavaFX application thread; public methods may be
 * called from any thread. [state] is a thread-safe [StateFlow].
 */
class DesktopAudioPlayer(
    private val resolveUrl: suspend (String) -> String = { RedirectResolver.resolve(it) },
) : AudioPlayer {

    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loadJob: Job? = null
    private val ticker = PositionTicker(scope) { fx { publishFromPlayer() } }

    // ---- confined to the JavaFX thread (or written before the player exists) ----
    @Volatile private var generation = 0L
    @Volatile private var player: MediaPlayer? = null
    private var ready = false
    private var ended = false
    @Volatile private var wantPlaying = false
    @Volatile private var pendingSeekMs = 0L
    @Volatile private var speed = 1f

    override fun play(episode: Episode, startPositionMs: Long) = play(episode, startPositionMs, null)

    override fun play(episode: Episode, startPositionMs: Long, podcastTitle: String?) {
        val gen = ++generation
        loadJob?.cancel()
        wantPlaying = true
        pendingSeekMs = startPositionMs.coerceAtLeast(0)
        _state.value = PlaybackState(
            episode = episode,
            isPlaying = true,
            positionMs = pendingSeekMs,
            durationMs = PlayerMath.durationMs(null, episode),
            speed = speed,
            isBuffering = true,
        )
        // Silence the previous episode now: resolving the new URL can take seconds, and until load() runs the
        // old player would keep playing and the ticker would publish its position/ended flag into the new state.
        fx {
            if (gen == generation) {
                disposePlayer()
                ended = false
            }
        }
        loadJob = scope.launch {
            val url = try {
                withContext(Dispatchers.IO) { resolveUrl(episode.audioUrl) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(gen, e.message ?: "Couldn't open this episode")
                return@launch
            }
            try {
                withContext(Dispatchers.IO) { JavaFxRuntime.ensureStarted() }
            } catch (e: CancellationException) {
                throw e // superseded by a newer play() or release()
            } catch (t: Throwable) {
                fail(gen, "Audio engine unavailable: ${t.message ?: t::class.simpleName}")
                return@launch
            }
            fx { if (gen == generation) load(gen, url, episode) }
        }
        ticker.start()
    }

    /** FX thread. */
    private fun load(gen: Long, url: String, episode: Episode) {
        disposePlayer()
        val mp = try {
            MediaPlayer(Media(url))
        } catch (e: Exception) {
            fail(gen, "Cannot play this episode: ${e.message ?: e::class.simpleName}")
            return
        }
        player = mp
        ready = false
        ended = false
        mp.setOnReady {
            if (gen != generation) return@setOnReady
            ready = true
            mp.rate = speed.toDouble()
            if (pendingSeekMs > 0) mp.seek(Duration.millis(pendingSeekMs.toDouble()))
            if (wantPlaying) mp.play()
            publishFromPlayer()
        }
        mp.setOnEndOfMedia {
            if (gen != generation) return@setOnEndOfMedia
            ended = true
            wantPlaying = false
            mp.pause()
            val duration = PlayerMath.durationMs(DesktopStateMapping.millisOrNull(mp.totalDuration), episode)
            _state.update { it.copy(isPlaying = false, isBuffering = false, isEnded = true, positionMs = duration, durationMs = duration) }
            ticker.stop()
        }
        mp.setOnError {
            if (gen != generation) return@setOnError
            fail(gen, mp.error?.message ?: "Playback error")
        }
        mp.statusProperty().addListener { _, _, _ -> if (gen == generation) publishFromPlayer() }
    }

    override fun pause() {
        wantPlaying = false
        ticker.stop()
        _state.update { it.copy(isPlaying = false, isBuffering = false) }
        fx {
            player?.pause()
            publishFromPlayer()
        }
    }

    override fun resume() {
        val current = _state.value
        if (current.episode == null) return
        if (player == null && current.error != null) {
            // Last load failed: retry from where we were.
            play(current.episode, current.positionMs)
            return
        }
        wantPlaying = true
        _state.update { it.copy(isPlaying = true, isEnded = false, error = null) }
        ticker.start()
        fx {
            val mp = player ?: return@fx
            if (ended) {
                ended = false
                mp.seek(Duration.ZERO)
            }
            if (ready) mp.play()
            publishFromPlayer()
        }
    }

    override fun seekTo(positionMs: Long) {
        val target = PlayerMath.clampSeek(positionMs, _state.value.durationMs)
        pendingSeekMs = target
        _state.update { it.copy(positionMs = target, isEnded = false) }
        fx {
            val mp = player ?: return@fx
            ended = false
            if (ready) mp.seek(Duration.millis(target.toDouble()))
        }
    }

    override fun setSpeed(speed: Float) {
        val clamped = PlayerMath.clampSpeed(speed)
        this.speed = clamped
        _state.update { it.copy(speed = clamped) }
        fx { player?.rate = clamped.toDouble() }
    }

    override fun release() {
        generation++
        loadJob?.cancel()
        ticker.stop()
        fx { disposePlayer() }
        scope.cancel()
        _state.value = PlaybackState()
    }

    /** FX thread: re-reads position/duration/status from the engine into [state]. */
    private fun publishFromPlayer() {
        val mp = player ?: return
        if (!ready && mp.status == MediaPlayer.Status.UNKNOWN) {
            _state.update { it.copy(isBuffering = wantPlaying) }
            return
        }
        val flags = DesktopStateMapping.flags(mp.status, wantPlaying, ended)
        _state.update { s ->
            val duration = PlayerMath.durationMs(DesktopStateMapping.millisOrNull(mp.totalDuration), s.episode)
            val position = if (ended) duration else DesktopStateMapping.millisOrNull(mp.currentTime) ?: s.positionMs
            s.copy(
                isPlaying = flags.isPlaying,
                isBuffering = flags.isBuffering,
                positionMs = PlayerMath.clampSeek(position, duration),
                durationMs = duration,
                isEnded = ended,
            )
        }
    }

    private fun fail(gen: Long, message: String) {
        if (gen != generation) return
        wantPlaying = false
        ticker.stop()
        _state.update { it.copy(isPlaying = false, isBuffering = false, error = message) }
        fx { if (gen == generation) disposePlayer() }
    }

    /** FX thread. */
    private fun disposePlayer() {
        player?.let {
            it.setOnReady(null)
            it.setOnEndOfMedia(null)
            it.setOnError(null)
            it.dispose()
        }
        player = null
        ready = false
    }

    private fun fx(block: () -> Unit) {
        if (JavaFxRuntime.isStarted) JavaFxRuntime.runOnFx(block)
    }
}
