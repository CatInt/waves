package io.waves.player

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistTracker
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * [default] for everything but HLS, which goes to an HLS source that doesn't give up on a playlist that stops
 * changing. YouTube serves a live stream that has just ended (a live archive's newest) as a live HLS playlist that it
 * never updates again: ExoPlayer took that for a frozen live stream and failed after a few segments
 * (`PlaylistStuckException`), though every segment is there to play.
 */
@UnstableApi
internal class HlsTolerantMediaSourceFactory(
    private val default: MediaSource.Factory,
    dataSourceFactory: DataSource.Factory,
) : MediaSource.Factory {
    private val hls = HlsMediaSource.Factory(dataSourceFactory).setPlaylistTrackerFactory { source, policy, parser, cmcd ->
        DefaultHlsPlaylistTracker(source, policy, parser, cmcd, STILL_PLAYLIST_COEFFICIENT)
    }

    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory = apply {
        default.setDrmSessionManagerProvider(provider)
        hls.setDrmSessionManagerProvider(provider)
    }

    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory = apply {
        default.setLoadErrorHandlingPolicy(policy)
        hls.setLoadErrorHandlingPolicy(policy)
    }

    override fun getSupportedTypes(): IntArray = (default.supportedTypes + C.CONTENT_TYPE_HLS).distinct().toIntArray()

    override fun createMediaSource(mediaItem: MediaItem): MediaSource =
        if (isHls(mediaItem)) hls.createMediaSource(mediaItem) else default.createMediaSource(mediaItem)

    private fun isHls(item: MediaItem): Boolean {
        val config = item.localConfiguration ?: return false
        return config.mimeType == MimeTypes.APPLICATION_M3U8 ||
            Util.inferContentTypeForUriAndMimeType(config.uri, config.mimeType) == C.CONTENT_TYPE_HLS
    }

    private companion object {
        /**
         * How many target durations a live playlist may go unchanged before it counts as stuck: in effect never, so a
         * finished stream's playlist plays to its end (ExoPlayer's default is 3.5).
         */
        const val STILL_PLAYLIST_COEFFICIENT = 1_000_000.0
    }
}
