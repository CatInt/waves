package io.waves.ui

import io.waves.feed.Feed
import io.waves.feed.FeedRepository
import io.waves.model.Episode
import io.waves.model.Podcast
import io.waves.player.AudioPlayer
import io.waves.player.PlaybackState
import io.waves.search.PodcastDirectory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Player whose state is driven explicitly by the test. */
class TestAudioPlayer : AudioPlayer {
    val mutableState = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = mutableState
    val playCalls = mutableListOf<Pair<Episode, Long>>()
    val playTitles = mutableListOf<String?>()

    override fun play(episode: Episode, startPositionMs: Long, podcastTitle: String?) {
        playTitles += podcastTitle
        play(episode, startPositionMs)
    }

    override fun play(episode: Episode, startPositionMs: Long) {
        playCalls += episode to startPositionMs
        mutableState.value = PlaybackState(episode, true, startPositionMs, (episode.durationSeconds ?: 0) * 1000)
    }

    override fun pause() = mutableState.update { it.copy(isPlaying = false) }
    override fun resume() = mutableState.update { it.copy(isPlaying = true) }
    override fun seekTo(positionMs: Long) = mutableState.update { it.copy(positionMs = positionMs) }
    override fun setSpeed(speed: Float) = mutableState.update { it.copy(speed = speed) }
    override fun release() { mutableState.value = PlaybackState() }

    fun advanceTo(positionMs: Long) = mutableState.update { it.copy(positionMs = positionMs) }
}

class RecordingDirectory(
    private val catalogue: List<Podcast>,
    var failTop: Boolean = false,
) : PodcastDirectory {
    val searches = mutableListOf<String>()
    val lookups = mutableListOf<Long>()
    /** Store country of each search / top-charts call. */
    val searchCountries = mutableListOf<String>()
    val topCountries = mutableListOf<String>()

    override suspend fun search(term: String, limit: Int): List<Podcast> {
        searches += term
        return catalogue.filter { it.title.contains(term, ignoreCase = true) }
    }

    override suspend fun search(term: String, country: String, limit: Int): List<Podcast> {
        searchCountries += country
        return search(term, limit)
    }

    override suspend fun topPodcasts(country: String, limit: Int): List<Podcast> {
        topCountries += country
        if (failTop) error("offline")
        return catalogue
    }

    override suspend fun lookup(appleId: Long): Podcast? {
        lookups += appleId
        return catalogue.firstOrNull { it.appleId == appleId }
    }
}

class MapFeedRepository(private val feeds: Map<String, Feed>) : FeedRepository {
    val fetched = mutableListOf<String>()
    override suspend fun fetch(feedUrl: String): Feed {
        fetched += feedUrl
        return feeds[feedUrl] ?: error("404 $feedUrl")
    }
}

class Counter { var count = 0; fun inc() { count++ } }

val podcastA = Podcast("https://a.example/feed", "Alpha Show", "Ann", "About alpha", "https://a.example/art.jpg", 111)
val podcastB = Podcast("https://b.example/feed", "Beta Cast", "Ben", null, null, 222)

fun episode(podcast: Podcast, n: Int, durationSec: Long = 600) = Episode(
    guid = "g$n",
    podcastFeedUrl = podcast.feedUrl,
    title = "${podcast.title} #$n",
    audioUrl = "${podcast.feedUrl}/$n.mp3",
    publishedAtEpochMs = n * 1000L,
    durationSeconds = durationSec,
)
