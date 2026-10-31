package io.waves.ui

import io.waves.model.Episode
import io.waves.model.Podcast
import io.waves.search.ChartEpisode
import io.waves.search.ChartShow
import io.waves.search.PodcastDirectory
import io.waves.search.parseApplePodcastsUrl
import io.waves.youtube.YoutubeUrls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class DiscoverUiState(
    val query: String = "",
    /** Apple's top shows, each with its place in its genre. */
    val topShows: List<ChartShow> = emptyList(),
    /** Apple's top episodes, listed before the shows. */
    val topEpisodes: List<ChartEpisode> = emptyList(),
    /** Top episodes found as ones that play, by their Apple id. */
    val playableEpisodes: Map<Long, Pair<Podcast, Episode>> = emptyMap(),
    /** The top episode being found to play, by Apple id. */
    val findingEpisode: Long? = null,
    /** Top episodes looked up and not found as ones that play, by Apple id: their rows stop waiting on details. */
    val missingEpisodes: Set<Long> = emptySet(),
    /** The top episodes' shows (cover and feed), by the show's Apple id, as they're looked up. */
    val episodeShows: Map<Long, Podcast> = emptyMap(),
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

    /** Top episodes being found as ones that play, or found, by Apple id; one that wasn't found is dropped to retry. */
    private val lookups = mutableMapOf<Long, Deferred<Pair<Podcast, Episode>?>>()

    /** Rows coming into view look up a few at a time. */
    private val lookupPermits = Semaphore(EpisodeLookupsAtOnce)

    /**
     * Finds a top [episode] as one that plays, in the background, as its row comes into view, so the row shows its
     * length and plays at once. Once per episode; one tapped meanwhile waits on the same lookup.
     */
    fun lookUpTopEpisode(episode: ChartEpisode) {
        if (episode.appleId != 0L) lookUp(episode)
    }

    private fun lookUp(episode: ChartEpisode): Deferred<Pair<Podcast, Episode>?> = lookups.getOrPut(episode.appleId) {
        scope.async {
            val found = lookupPermits.withPermit {
                try {
                    directory.playable(episode, country.value)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            }
            if (found == null) lookups.remove(episode.appleId)
            _state.update { s ->
                if (found == null) s.copy(missingEpisodes = s.missingEpisodes + episode.appleId)
                else s.copy(playableEpisodes = s.playableEpisodes + (episode.appleId to found), missingEpisodes = s.missingEpisodes - episode.appleId)
            }
            found
        }
    }

    /**
     * Plays a top [episode]: finds it as one that plays (once; it's kept), then hands it to [play]. When it can't be
     * found, [notFound] (Discover opens its show instead).
     */
    fun playTopEpisode(episode: ChartEpisode, play: (Episode, Podcast) -> Unit, notFound: () -> Unit) {
        _state.value.playableEpisodes[episode.appleId]?.let { (show, ep) -> return play(ep, show) }
        if (_state.value.findingEpisode == episode.appleId) return
        _state.update { it.copy(findingEpisode = episode.appleId) }
        scope.launch {
            val found = lookUp(episode).await()
            _state.update { s -> s.copy(findingEpisode = s.findingEpisode.takeIf { it != episode.appleId }) }
            if (found != null) play(found.second, found.first) else notFound()
        }
    }

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
            coroutineScope {
                // The episodes are extra: without them the shows still list.
                val episodes = async {
                    try {
                        directory.topEpisodes(country, TopEpisodeCount)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
                val shows = directory.topShows(country, TopShowCount)
                val top = episodes.await()
                _state.update { it.copy(topShows = shows, topEpisodes = top, topLoading = false) }
                lookUpEpisodeShows(top, shows)
            }
        } catch (e: CancellationException) {
            _state.update { it.copy(topLoading = false) }
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(topLoading = false, topError = e.message ?: "Couldn't load top charts") }
        }
    }

    /**
     * The shows of the top [episodes], for their own covers (the chart gives each episode's art) and so opening one
     * needs no lookup: from the top [shows] when there, else looked up (the directory keeps them for days).
     */
    private suspend fun lookUpEpisodeShows(episodes: List<ChartEpisode>, shows: List<ChartShow>) {
        val known = shows.mapNotNull { s -> s.podcast.appleId?.let { it to s.podcast } }.toMap()
        val ids = episodes.map { it.showAppleId }.distinct()
        _state.update { it.copy(episodeShows = it.episodeShows + ids.mapNotNull { id -> known[id]?.let { id to it } }) }
        coroutineScope {
            ids.filter { it !in known }.map { id ->
                async {
                    val show = try {
                        directory.lookup(id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    if (show != null) _state.update { it.copy(episodeShows = it.episodeShows + (id to show)) }
                }
            }.forEach { it.await() }
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

/** Apple's top episodes taken: plenty, since only those whose show has room in its genre are listed. */
private const val TopEpisodeCount = 50
private const val TopShowCount = 50

/** How many top episodes are looked up at once as their rows come into view. */
private const val EpisodeLookupsAtOnce = 4
