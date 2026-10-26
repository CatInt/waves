package io.waves.offline

import io.waves.model.EpisodeId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** [PlaybackCache] held in memory. [onResourceRemoved] stands in for deleting the platform cache bytes. */
class InMemoryPlaybackCache(
    private val onResourceRemoved: (EpisodeId) -> Unit = {},
) : PlaybackCache {
    private val state = MutableStateFlow<List<PlaybackCacheEntry>>(emptyList())
    override val entries: Flow<List<PlaybackCacheEntry>> = state.asStateFlow()

    override suspend fun upsert(entry: PlaybackCacheEntry) {
        state.update { list -> list.filterNot { it.episodeId == entry.episodeId } + entry }
    }

    override suspend fun remove(episodeId: EpisodeId) {
        state.update { list -> list.filterNot { it.episodeId == episodeId } }
        onResourceRemoved(episodeId)
    }
}
