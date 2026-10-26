package io.waves.refresh

import io.waves.data.LibraryRepository
import io.waves.feed.Feed
import io.waves.feed.FeedRepository
import io.waves.model.Episode
import io.waves.model.EpisodeId
import io.waves.model.HistoryEntry
import io.waves.model.PlaybackProgress
import io.waves.model.Podcast
import io.waves.sync.SyncState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** Library holding subscriptions and episodes in memory. */
class RefreshFakeLibrary(podcasts: List<Podcast> = emptyList()) : LibraryRepository {
    val subs = MutableStateFlow(podcasts)
    val stored = MutableStateFlow<Map<String, List<Episode>>>(emptyMap())
    var upserts = 0
    val metadataUpdates = mutableListOf<Podcast>()

    override val subscriptions: Flow<List<Podcast>> = subs
    override fun episodes(feedUrl: String): Flow<List<Episode>> = stored.map { it[feedUrl].orEmpty() }
    override fun progress(episodeId: EpisodeId): Flow<PlaybackProgress?> = flowOf(null)
    override val allProgress: Flow<List<PlaybackProgress>> = flowOf(emptyList())
    override val history: Flow<List<HistoryEntry>> = flowOf(emptyList())

    override suspend fun subscribe(podcast: Podcast) {
        subs.value = subs.value.filter { it.feedUrl != podcast.feedUrl } + podcast
    }
    override suspend fun unsubscribe(feedUrl: String) {
        subs.value = subs.value.filter { it.feedUrl != feedUrl }
    }
    override suspend fun updatePodcastMetadata(podcast: Podcast) {
        metadataUpdates += podcast
        subs.value = subs.value.map { if (it.feedUrl == podcast.feedUrl) podcast else it }
    }
    override suspend fun upsertEpisodes(feedUrl: String, episodes: List<Episode>) {
        upserts++
        val existing = stored.value[feedUrl].orEmpty().associateBy { it.guid }.toMutableMap()
        episodes.forEach { existing[it.guid] = it }
        stored.value = stored.value + (feedUrl to existing.values.toList())
    }
    override suspend fun episode(episodeId: EpisodeId): Episode? =
        stored.value[episodeId.feedUrl]?.firstOrNull { it.guid == episodeId.guid }
    override suspend fun saveProgress(progress: PlaybackProgress) = Unit
    override suspend fun addHistory(entry: HistoryEntry) = Unit
    override suspend fun exportSyncState(): SyncState = SyncState()
    override suspend fun importSyncState(state: SyncState) = Unit
}

/** Feeds served from a mutable map; [behavior] can inject delays/failures per URL. */
class FakeFeeds : FeedRepository {
    val feeds = mutableMapOf<String, List<Episode>>()
    /** Channel metadata per URL; defaults to `Podcast(url, "Feed $url")`. */
    val channels = mutableMapOf<String, Podcast>()
    var behavior: suspend (String) -> Unit = {}
    var fetches = 0

    override suspend fun fetch(feedUrl: String): Feed {
        fetches++
        behavior(feedUrl)
        val episodes = feeds[feedUrl] ?: error("404 $feedUrl")
        return Feed(channels[feedUrl] ?: Podcast(feedUrl, "Feed $feedUrl"), episodes)
    }
}

fun podcast(url: String) = Podcast(feedUrl = url, title = "Podcast $url")

fun episode(feedUrl: String, guid: String, publishedAt: Long?) =
    Episode(guid = guid, podcastFeedUrl = feedUrl, title = "Episode $guid", audioUrl = "https://a/$guid.mp3", publishedAtEpochMs = publishedAt)
