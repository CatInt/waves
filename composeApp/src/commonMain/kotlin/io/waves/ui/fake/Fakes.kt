package io.waves.ui.fake

import io.waves.currentTimeMillis
import io.waves.download.DownloadAwareAudioPlayer
import io.waves.feed.Feed
import io.waves.feed.FeedRepository
import io.waves.offline.InMemoryPlaybackCache
import io.waves.model.Episode
import io.waves.model.Podcast
import io.waves.player.AudioPlayer
import io.waves.player.PlaybackState
import io.waves.search.PodcastDirectory
import io.waves.sync.AuthState
import io.waves.sync.GoogleAuth
import io.waves.sync.SyncService
import io.waves.sync.SyncStatus
import io.waves.ui.AppDependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Sample catalogue used by the fakes. */
object SampleData {
    private const val DAY = 24 * 60 * 60 * 1000L

    val podcasts: List<Podcast> = listOf(
        Podcast("https://example.com/feeds/signal.xml", "Signal & Waves", "Ada Byte",
            "Weekly conversations about the hidden systems that run our lives.", null, 1000000001),
        Podcast("https://example.com/feeds/slow-radio.xml", "Slow Radio", "The Quiet Studio",
            "Long-form ambient soundscapes recorded around the world.", null, 1000000002),
        Podcast("https://example.com/feeds/kotlin-weekly.xml", "Kotlin Weekly Audio", "JB Fans",
            "News and deep dives from the Kotlin ecosystem.", null, 1000000003),
        Podcast("https://example.com/feeds/history-hour.xml", "The History Hour", "Clio Media",
            "One forgotten story from history, every week.", null, 1000000004),
        Podcast("https://example.com/feeds/morning-brief.xml", "Morning Brief", "Daily Desk",
            "Ten minutes of news to start your day.", null, 1000000005),
        Podcast("https://example.com/feeds/science-sideways.xml", "Science Sideways", "Lab Notes",
            "Curious questions, surprising answers.", null, 1000000006),
    )

    fun episodes(podcast: Podcast, now: Long = currentTimeMillis()): List<Episode> =
        (1..8).map { n ->
            Episode(
                guid = "${podcast.appleId}-$n",
                podcastFeedUrl = podcast.feedUrl,
                title = "${podcast.title}: Episode $n",
                audioUrl = "https://example.com/audio/${podcast.appleId}-$n.mp3",
                publishedAtEpochMs = now - (n - 1) * 3 * DAY,
                durationSeconds = (12 + n * 7L) * 60,
                description = "Episode $n of ${podcast.title}. ${podcast.description}",
            )
        }.reversed()
}

class FakePodcastDirectory(
    private val catalogue: List<Podcast> = SampleData.podcasts,
    private val latencyMs: Long = 300,
) : PodcastDirectory {
    override suspend fun search(term: String, limit: Int): List<Podcast> {
        delay(latencyMs)
        return catalogue.filter {
            it.title.contains(term, ignoreCase = true) || it.author?.contains(term, ignoreCase = true) == true
        }.take(limit)
    }

    override suspend fun topPodcasts(country: String, limit: Int): List<Podcast> {
        delay(latencyMs)
        return catalogue.take(limit)
    }

    override suspend fun lookup(appleId: Long): Podcast? {
        delay(latencyMs)
        return catalogue.firstOrNull { it.appleId == appleId }
    }
}

class FakeFeedRepository(
    private val catalogue: List<Podcast> = SampleData.podcasts,
    private val latencyMs: Long = 500,
) : FeedRepository {
    override suspend fun fetch(feedUrl: String): Feed {
        delay(latencyMs)
        val podcast = catalogue.firstOrNull { it.feedUrl == feedUrl }
            ?: throw IllegalArgumentException("Unknown feed: $feedUrl")
        return Feed(podcast, SampleData.episodes(podcast))
    }
}

/** Pretends to play audio by advancing the position in real time. */
class FakeAudioPlayer(
    private val scope: CoroutineScope,
    private val tickMs: Long = 250,
) : AudioPlayer {
    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()
    private var ticker: Job? = null

    override fun play(episode: Episode, startPositionMs: Long) {
        val duration = (episode.durationSeconds ?: 1800L) * 1000
        _state.value = PlaybackState(
            episode, isPlaying = true, positionMs = startPositionMs.coerceIn(0, duration), durationMs = duration,
            speed = _state.value.speed,
        )
        startTicker()
    }

    override fun pause() {
        ticker?.cancel()
        _state.update { it.copy(isPlaying = false) }
    }

    override fun resume() {
        if (_state.value.episode == null) return
        _state.update { if (it.isEnded) it.copy(isPlaying = true, isEnded = false, positionMs = 0) else it.copy(isPlaying = true) }
        startTicker()
    }

    override fun seekTo(positionMs: Long) {
        _state.update { it.copy(positionMs = positionMs.coerceIn(0, it.durationMs), isEnded = false) }
    }

    override fun setSpeed(speed: Float) {
        _state.update { it.copy(speed = speed.coerceIn(AudioPlayer.MIN_SPEED, AudioPlayer.MAX_SPEED)) }
    }

    override fun release() {
        ticker?.cancel()
        _state.value = PlaybackState()
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(tickMs)
                _state.update {
                    val next = it.positionMs + (tickMs * it.speed).toLong()
                    if (next >= it.durationMs) it.copy(positionMs = it.durationMs, isPlaying = false, isEnded = true)
                    else it.copy(positionMs = next)
                }
                if (!_state.value.isPlaying) break
            }
        }
    }
}

class FakeGoogleAuth(initial: AuthState = AuthState.SignedOut) : GoogleAuth {
    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<AuthState> = _state.asStateFlow()

    override suspend fun signIn() {
        delay(400)
        _state.value = AuthState.SignedIn("listener@example.com")
    }

    override suspend fun signOut() {
        _state.value = AuthState.SignedOut
    }

    override suspend fun accessToken(): String? =
        if (_state.value is AuthState.SignedIn) "fake-token" else null
}

class FakeSyncService(
    private val auth: GoogleAuth,
    private val clock: () -> Long = ::currentTimeMillis,
) : SyncService {
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    override val status: StateFlow<SyncStatus> = _status.asStateFlow()

    override suspend fun syncNow() {
        if (auth.state.value !is AuthState.SignedIn) {
            _status.value = SyncStatus.Failed("Not signed in")
            return
        }
        _status.value = SyncStatus.Syncing
        delay(800)
        _status.value = SyncStatus.Done(clock())
    }
}

/**
 * Fully in-memory dependencies with sample data so the app runs without any backend.
 * [playerTickMs] is how often the fake player advances; UI tests pass a huge value so the screen can go idle.
 * [downloadStepMs] paces the simulated downloads (a download takes 11 steps).
 */
fun fakeDependencies(
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    playerTickMs: Long = 250,
    downloadStepMs: Long = 300,
): AppDependencies {
    val auth = FakeGoogleAuth()
    // Episode 3 of every show fails on the first attempt, to demo the retry state.
    val downloads = InMemoryDownloadManager(scope, stepMs = downloadStepMs, failOnce = { it.guid.endsWith("-3") })
    return AppDependencies(
        directory = FakePodcastDirectory(),
        feeds = FakeFeedRepository(),
        library = InMemoryLibraryRepository(),
        player = DownloadAwareAudioPlayer(FakeAudioPlayer(scope, playerTickMs), downloads),
        auth = auth,
        sync = FakeSyncService(auth),
        deviceId = "fake-device",
        downloads = downloads,
        playbackCache = InMemoryPlaybackCache(),
    )
}
