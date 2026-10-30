package io.waves.ui

import io.waves.model.Podcast
import io.waves.search.PodcastDirectory
import io.waves.search.parseApplePodcastsUrl
import io.waves.youtube.YoutubeUrls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
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
 * YouTube channel (or video) link resolves the channel as a show via [resolveYoutube], followed by its live archive
 * when it has one and its playlists; a playlist link, the playlist.
 * Charts and search use the store [country] (e.g. `UserSettings.effectiveCountry`); both reload when it changes.
 */
@OptIn(FlowPreview::class)
class DiscoverState(
    private val directory: PodcastDirectory,
    private val scope: CoroutineScope,
    private val parseAppleUrl: (String) -> Long? = ::parseApplePodcastsUrl,
    private val debounceMs: Long = 350,
    private val country: StateFlow<String> = MutableStateFlow("us"),
    private val resolveYoutube: (suspend (String) -> List<Podcast>)? = null,
) {
    private val _state = MutableStateFlow(DiscoverUiState())
    val state: StateFlow<DiscoverUiState> = _state.asStateFlow()

    private val queries = MutableStateFlow("")
    private var refreshJob: Job? = null

    /**
     * A creator searched for from a show page, and their own shows (null while still being found), listed instead of
     * a search for as long as the query is their name.
     */
    private var creator: Pair<String, List<Podcast>?>? = null

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

    /**
     * Lists [name]'s own shows (a YouTube channel and its live archive) under their name, rather than searching for
     * it. Call with null [shows] at once, so Discover shows it's busy, then again with the shows once known; none
     * found falls back to an ordinary search for the name.
     */
    fun showCreator(name: String, shows: List<Podcast>?) {
        val term = name.trim()
        creator = if (shows?.isEmpty() == true) null else term to shows
        when {
            _state.value.query.trim() != term -> onQueryChange(name)
            creator == null -> refresh()
            else -> _state.update { creatorResults(it, shows) }
        }
    }

    private fun creatorResults(state: DiscoverUiState, shows: List<Podcast>?) =
        state.copy(results = shows.orEmpty(), searching = shows == null, searchError = null, resolvedFromUrl = false)

    fun loadTopCharts() {
        scope.launch { loadTop(country.value) }
    }

    /** Reloads what's on screen: the current search when there's a query, else the top charts. */
    fun refresh() {
        val term = _state.value.query.trim()
        refreshJob?.cancel()
        refreshJob = scope.launch { if (term.isEmpty()) loadTop(country.value) else runSearch(term, country.value) }
    }

    private suspend fun loadTop(country: String) {
        _state.update { it.copy(topLoading = true, topError = null) }
        try {
            val top = directory.topPodcasts(country)
            _state.update { it.copy(topPodcasts = top, topLoading = false) }
        } catch (e: CancellationException) {
            _state.update { it.copy(topLoading = false) }
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
        creator?.takeIf { it.first == term }?.let { (_, shows) ->
            _state.update { creatorResults(it, shows) }
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
                val shows = resolve(term)
                _state.update {
                    it.copy(results = shows, searching = false, resolvedFromUrl = true, searchError = null)
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
            _state.update { it.copy(searching = false) }
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(searching = false, searchError = e.message ?: "Search failed") }
        }
    }
}
