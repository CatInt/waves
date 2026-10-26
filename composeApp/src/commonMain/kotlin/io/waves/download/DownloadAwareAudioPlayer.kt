package io.waves.download

import io.waves.model.Episode
import io.waves.player.AudioPlayer
import io.waves.player.PlaybackState
import kotlinx.coroutines.flow.StateFlow

/**
 * [AudioPlayer] decorator that plays a local copy when one exists: `play()` swaps [Episode.audioUrl] for
 * [DownloadManager.playableUri] (the download, else the playback cache). Everything else is delegated unchanged.
 * Note that `state.episode.audioUrl` is then the `file://` URI; compare episodes by [Episode.id].
 */
class DownloadAwareAudioPlayer(
    private val delegate: AudioPlayer,
    private val downloads: DownloadManager,
) : AudioPlayer by delegate {
    override val state: StateFlow<PlaybackState> get() = delegate.state

    override fun play(episode: Episode, startPositionMs: Long) =
        delegate.play(episode.preferLocal(), startPositionMs)

    override fun play(episode: Episode, startPositionMs: Long, podcastTitle: String?) =
        delegate.play(episode.preferLocal(), startPositionMs, podcastTitle)

    override fun setSpeed(speed: Float) = delegate.setSpeed(speed)

    private fun Episode.preferLocal(): Episode = downloads.playableUri(this)?.let { copy(audioUrl = it) } ?: this
}
