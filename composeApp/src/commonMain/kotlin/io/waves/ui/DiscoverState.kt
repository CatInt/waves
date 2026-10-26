package io.waves.ui

import io.waves.model.Podcast
import io.waves.search.PodcastDirectory
import io.waves.search.parseApplePodcastsUrl
import io.waves.youtube.YoutubeUrls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DiscoverUiState(
    val query: String = "",
    val topPodcasts: List<Podcast> = emptyList(),
    val topLoading: Boolean = false,
    val topError: String? = null,
    val results: List<Podcast> = emptyList(),
    val searching: Boolean = false,
    val searchError: String? = null,
    /** True when [results] came from a pasted show link (Apple Podcasts or YouTube). */
    val resolvedFromUrl: Boolean = false,
) {
    val isSearching: Boolean get() = query.isNotBlank()
}

/**
 * Apple top charts + debounced search. Pasting a podcasts.apple.com link resolves it via lookup; pasting a
 * YouTube channel (or video) link resolves the channel as a show via [resolveYoutube]; a playlist link, the playlist.
 * Charts and search use the store [country] (e.g. `UserSettings.effectiveCountry`); both reload when it changes.
 */
@OptIn(FlowPreview::class)
class DiscoverState(
    private val directory: PodcastDirectory,
    private val scope: CoroutineScope,
    private val parseAppleUrl: (String) -> Long? = ::parseApplePodcastsUrl,
    private val debounceMs: Long = 350,
    private val country: StateFlow<String> = MutableStateFlow("us"),
    private val resolveYoutube: (suspend (String) -> Podcast)? = null,
) {
    private val _state = MutableStateFlow(DiscoverUiState())
    val state: StateFlow<DiscoverUiState> = _state.asStateFlow()

    private val queries = MutableStateFlow("")

    init {
        scope.launch { country.collectLatest { loadTop(it) } }
        scope.launch {
            combine(queries.map { it.trim() }.debounce(debounceMs), country) { q, c -> q to c }
                .distinctUntilChanged()
                .collectLatest { (q, c) -> runSearch(q, c) }
        }
    }

    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query) }
        queries.value = query
    }

    fun clearQuery() = onQueryChange("")

    fun loadTopCharts() {
        scope.launch { loadTop(country.value) }
    }

    private suspend fun loadTop(country: String) {
        _state.update { it.copy(topLoading = true, topError = null) }
        try {
            val top = directory.topPodcasts(country)
            _state.update { it.copy(topPodcasts = top, topLoading = false) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(topLoading = false, topError = e.message ?: "Couldn't load top charts") }
        }
    }

    private suspend fun runSearch(term: String, country: String) {
        if (term.isEmpty()) {
            _state.update { it.copy(results = emptyList(), searching = false, searchError = null, resolvedFromUrl = false) }
            return
        }
        _state.update { it.copy(searching = true, searchError = null) }
        try {
            if (YoutubeUrls.looksLikeUrl(term)) {
                val resolve = resolveYoutube
                    ?: return _state.update {
                        it.copy(results = emptyList(), searching = false, resolvedFromUrl = true,
                            searchError = "YouTube links aren't available")
                    }
                val podcast = resolve(term)
                _state.update {
                    it.copy(results = listOf(podcast), searching = false, resolvedFromUrl = true, searchError = null)
                }
                return
            }
            val isUrl = looksLikeApplePodcastsUrl(term)
            val results = if (isUrl) {
                val id = parseAppleUrl(term)
                    ?: return _state.update {
                        it.copy(results = emptyList(), searching = false, resolvedFromUrl = true,
                            searchError = "That doesn't look like an Apple Podcasts show link")
                    }
                listOfNotNull(directory.lookup(id))
            } else {
                directory.search(term, country)
            }
            _state.update {
                it.copy(
                    results = results,
                    searching = false,
                    resolvedFromUrl = isUrl,
                    searchError = if (isUrl && results.isEmpty()) "Podcast not found" else null,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(searching = false, searchError = e.message ?: "Search failed") }
        }
    }
}
