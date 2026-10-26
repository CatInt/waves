package io.waves.player

import com.sun.jna.Callback
import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import io.waves.model.Episode
import io.waves.ui.PlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * macOS system media integration: publishes the current episode to Now Playing (Control Center, the menu bar's
 * Now Playing module, the lock screen) and handles the media keys, AirPods / headphone buttons and those
 * controls through MPRemoteCommandCenter. Native calls run on [scope]'s thread inside an autorelease pool;
 * remote commands arrive on AppKit's main thread and are forwarded to [scope].
 */
class MacNowPlaying private constructor(
    private val playback: PlaybackController,
    private val cover: StateFlow<CoverArt?>,
    private val scope: CoroutineScope,
) {
    private val mp = NativeLibrary.getInstance(MEDIA_PLAYER)
    private val infoCenter = ObjC.send(ObjC.cls("MPNowPlayingInfoCenter"), "defaultCenter")
    private val commands = ObjC.send(ObjC.cls("MPRemoteCommandCenter"), "sharedCommandCenter")
    private val target = ObjC.new(TARGET_CLASS)
    private var job: Job? = null
    // Guards publishing against stop(), which may run on another thread while an update is in flight.
    private val lock = Any()
    private var stopped = false
    /** Whether Waves has announced itself as playing once, see [publishLocked]. */
    private var claimed = false
    private var latest: NowPlayingInfo? = null

    /** The artwork object for [artworkUrl], kept (+1) until the cover changes. */
    private var artwork: Pointer? = null
    private var artworkUrl: String? = null

    private fun constant(name: String): Pointer? = mp.getGlobalVariableAddress(name).getPointer(0)

    private fun start() {
        activeController = this
        ObjC.autoreleasePool {
            listOf(
                "playCommand" to "wavesPlay:",
                "pauseCommand" to "wavesPause:",
                "togglePlayPauseCommand" to "wavesToggle:",
                "skipForwardCommand" to "wavesSkipForward:",
                "skipBackwardCommand" to "wavesSkipBackward:",
                "changePlaybackPositionCommand" to "wavesSeek:",
            ).forEach { (command, selector) ->
                val c = ObjC.send(commands, command)
                ObjC.send(c, "addTarget:action:", target, ObjC.sel(selector))
                ObjC.send(c, "setEnabled:", 1)
            }
            // A podcast has no meaningful next/previous track; the media keys then map to the skip commands.
            listOf("nextTrackCommand", "previousTrackCommand").forEach { ObjC.send(ObjC.send(commands, it), "setEnabled:", 0) }
        }
        job = scope.launch {
            launch {
                playback.skipIntervals.collect { skip ->
                    ObjC.autoreleasePool {
                        setInterval("skipForwardCommand", skip.forwardSeconds)
                        setInterval("skipBackwardCommand", skip.backSeconds)
                    }
                }
            }
            combine(playback.state, playback.podcast, cover) { s, podcast, art ->
                s.episode?.let { episode ->
                    NowPlayingInfo(
                        episode = episode,
                        show = podcast?.title,
                        playing = s.isPlaying,
                        // Whole seconds: the system extrapolates the position itself between updates.
                        positionSeconds = s.positionMs / 1000,
                        durationSeconds = (s.durationMs.takeIf { it > 0 } ?: (episode.durationSeconds ?: 0) * 1000) / 1000,
                        speed = s.speed,
                        cover = art,
                    )
                }
            }
                .distinctUntilChanged()
                .collect { publish(it) }
        }
    }

    private fun setInterval(command: String, seconds: Int) {
        val interval = ObjC.send(ObjC.cls("NSArray"), "arrayWithObject:", ObjC.number(seconds.toDouble()))
        ObjC.send(ObjC.send(commands, command), "setPreferredIntervals:", interval)
    }

    private fun publish(info: NowPlayingInfo?): Unit = synchronized(lock) { if (!stopped) publishLocked(info) }

    private fun publishLocked(info: NowPlayingInfo?): Unit = ObjC.autoreleasePool {
        latest = info
        if (info == null) {
            ObjC.send(infoCenter, "setNowPlayingInfo:", null)
            ObjC.send(infoCenter, "setPlaybackState:", STATE_STOPPED)
            return@autoreleasePool
        }
        val dict = ObjC.send(ObjC.cls("NSMutableDictionary"), "dictionary")
        fun put(key: String, value: Pointer?) {
            if (value != null) ObjC.send(dict, "setObject:forKey:", value, constant(key))
        }
        put("MPMediaItemPropertyTitle", ObjC.string(info.episode.title))
        info.show?.takeIf { it.isNotBlank() }?.let {
            put("MPMediaItemPropertyArtist", ObjC.string(it))
            put("MPMediaItemPropertyAlbumTitle", ObjC.string(it))
        }
        put("MPNowPlayingInfoPropertyMediaType", ObjC.number(MEDIA_TYPE_AUDIO))
        if (info.durationSeconds > 0) put("MPMediaItemPropertyPlaybackDuration", ObjC.number(info.durationSeconds.toDouble()))
        put("MPNowPlayingInfoPropertyElapsedPlaybackTime", ObjC.number(info.positionSeconds.toDouble()))
        put("MPNowPlayingInfoPropertyPlaybackRate", ObjC.number(if (info.playing) info.speed.toDouble() else 0.0))
        put("MPNowPlayingInfoPropertyDefaultPlaybackRate", ObjC.number(1.0))
        put("MPMediaItemPropertyArtwork", artworkFor(info.cover))
        ObjC.send(infoCenter, "setNowPlayingInfo:", dict)
        // macOS routes media keys to the app that most recently reported "playing"; one that has only ever been
        // paused (the episode restored at launch) never receives them. So the first episode is briefly reported
        // as playing, which makes Waves the Now Playing app, and then as its real (paused) state.
        val claim = !claimed && !info.playing
        claimed = true
        ObjC.send(infoCenter, "setPlaybackState:", if (info.playing || claim) STATE_PLAYING else STATE_PAUSED)
        if (claim) {
            scope.launch {
                delay(CLAIM_MS)
                synchronized(lock) { if (!stopped) latest?.let { publishLocked(it) } }
            }
        }
    }

    private fun artworkFor(cover: CoverArt?): Pointer? {
        if (cover?.url == artworkUrl) return artwork
        ObjC.release(artwork)
        artwork = null
        artworkUrl = cover?.url
        val image = cover?.let { ObjC.new("NSImage", "initWithData:", ObjC.data(it.bytes)) } ?: run {
            Artwork.setImage(null)
            return null
        }
        val width = ObjC.sendDouble(image, "size") // NSSize.width is returned first; a square cover is assumed.
        Artwork.setImage(image)
        ObjC.release(image) // Artwork holds its own reference
        val side = if (width > 0) width else 600.0
        artwork = ObjC.new("MPMediaItemArtwork", "initWithBoundsSize:requestHandler:", side, side, Artwork.block)
        return artwork
    }

    fun stop() = synchronized(lock) {
        stopped = true
        job?.cancel()
        if (activeController === this) activeController = null
        ObjC.autoreleasePool {
            listOf(
                "playCommand", "pauseCommand", "togglePlayPauseCommand",
                "skipForwardCommand", "skipBackwardCommand", "changePlaybackPositionCommand",
            ).forEach { ObjC.send(ObjC.send(commands, it), "removeTarget:", target) }
            ObjC.send(infoCenter, "setNowPlayingInfo:", null)
            ObjC.send(infoCenter, "setPlaybackState:", STATE_STOPPED)
        }
    }

    private fun handle(selector: Pointer?, event: Pointer?): Long {
        val action: (() -> Unit) = when (selector) {
            ObjC.sel("wavesPlay:") -> { { if (!playback.state.value.isPlaying) playback.togglePlayPause() } }
            ObjC.sel("wavesPause:") -> { { if (playback.state.value.isPlaying) playback.pause() } }
            ObjC.sel("wavesToggle:") -> playback::togglePlayPause
            ObjC.sel("wavesSkipForward:") -> playback::skipForward
            ObjC.sel("wavesSkipBackward:") -> playback::skipBack
            ObjC.sel("wavesSeek:") -> {
                val seconds = ObjC.sendDouble(event, "positionTime");
                { playback.seekTo((seconds * 1000).toLong()) }
            }
            else -> return STATUS_FAILED
        }
        scope.launch { action() }
        return STATUS_SUCCESS
    }

    private data class NowPlayingInfo(
        val episode: Episode,
        val show: String?,
        val playing: Boolean,
        val positionSeconds: Long,
        val durationSeconds: Long,
        val speed: Float,
        val cover: CoverArt?,
    ) {
        // Compare covers by identity of their URL, not their (large) byte arrays.
        override fun equals(other: Any?) = other is NowPlayingInfo && episode == other.episode && show == other.show &&
            playing == other.playing && positionSeconds == other.positionSeconds &&
            durationSeconds == other.durationSeconds && speed == other.speed && cover?.url == other.cover?.url

        override fun hashCode() = episode.id.hashCode()
    }

    /** `- (MPRemoteCommandHandlerStatus)handler:(MPRemoteCommandEvent *)event` */
    private fun interface CommandImp : Callback {
        fun invoke(self: Pointer?, selector: Pointer?, event: Pointer?): Long
    }

    /** `NSImage *(^)(CGSize)`; the CGSize arrives as two doubles (it travels in two FP registers on arm64 and x86-64). */
    private fun interface ArtworkImp : Callback {
        fun invoke(block: Pointer?, width: Double, height: Double): Pointer?
    }

    /**
     * The artwork request handler as a hand-built global block (no captures, never copied to the heap), returning
     * whatever cover is current. Process-wide, so its memory and callback are never collected.
     */
    private object Artwork {
        @Volatile private var image: Pointer? = null
        /** The image before [image]; released one change later in case the system is still handing it out. */
        private var previous: Pointer? = null

        fun setImage(next: Pointer?) {
            ObjC.release(previous)
            previous = image
            image = next?.also { ObjC.send(it, "retain") }
        }

        private val imp = ArtworkImp { _, _, _ -> image }
        private val descriptor = Memory(16).apply {
            setLong(0, 0) // reserved
            setLong(8, 32) // sizeof(block literal)
        }
        val block: Memory = Memory(32).apply {
            setPointer(0, NativeLibrary.getInstance("System").getGlobalVariableAddress("_NSConcreteGlobalBlock"))
            setInt(8, BLOCK_IS_GLOBAL)
            setInt(12, 0)
            setPointer(16, com.sun.jna.CallbackReference.getFunctionPointer(imp))
            setPointer(24, descriptor)
        }
    }

    companion object {
        private const val MEDIA_PLAYER = "/System/Library/Frameworks/MediaPlayer.framework/MediaPlayer"
        private const val TARGET_CLASS = "WavesRemoteCommandTarget"
        private const val BLOCK_IS_GLOBAL = 1 shl 28
        private const val STATUS_SUCCESS = 0L
        private const val STATUS_FAILED = 200L
        private const val STATE_PLAYING = 1L
        private const val STATE_PAUSED = 2L
        private const val STATE_STOPPED = 3L
        private const val MEDIA_TYPE_AUDIO = 1L
        /** How long the launch-time "playing" announcement lasts before the real paused state replaces it. */
        private const val CLAIM_MS = 500L

        @Volatile private var activeController: MacNowPlaying? = null
        private val commandImp = CommandImp { _, selector, event -> activeController?.handle(selector, event) ?: STATUS_FAILED }
        private val selectors = listOf("wavesPlay:", "wavesPause:", "wavesToggle:", "wavesSkipForward:", "wavesSkipBackward:", "wavesSeek:")

        /** Null off macOS or if the MediaPlayer framework can't be reached. */
        fun start(playback: PlaybackController, cover: StateFlow<CoverArt?>, scope: CoroutineScope): MacNowPlaying? {
            if (!System.getProperty("os.name").orEmpty().startsWith("Mac")) return null
            return runCatching {
                NativeLibrary.getInstance(MEDIA_PLAYER)
                ObjC.defineClass(TARGET_CLASS, selectors, commandImp, "q@:@") ?: return null
                MacNowPlaying(playback, cover, scope).also { it.start() }
            }.onFailure { System.err.println("Now Playing integration unavailable: $it") }.getOrNull()
        }

        /** The published MPNowPlayingPlaybackState: 1 playing, 2 paused, 3 stopped (for tests). */
        internal fun publishedState(): Long = ObjC.autoreleasePool {
            ObjC.sendLong(ObjC.send(ObjC.cls("MPNowPlayingInfoCenter"), "defaultCenter"), "playbackState")
        }

        /** The title currently published to Now Playing (for tests). */
        internal fun publishedTitle(): String? = ObjC.autoreleasePool {
            val info = ObjC.send(ObjC.send(ObjC.cls("MPNowPlayingInfoCenter"), "defaultCenter"), "nowPlayingInfo")
            val key = NativeLibrary.getInstance(MEDIA_PLAYER).getGlobalVariableAddress("MPMediaItemPropertyTitle").getPointer(0)
            info?.let { ObjC.stringValue(ObjC.send(it, "objectForKey:", key)) }
        }
    }
}
