package io.waves.ui

import io.waves.data.UserSettings
import io.waves.data.normalizeRegion
import io.waves.feed.Feed
import io.waves.model.EpisodeId
import io.waves.model.HistoryEntry
import io.waves.model.PlaybackProgress
import io.waves.model.Podcast
import io.waves.sync.AuthState
import io.waves.sync.SyncStatus
import io.waves.ui.fake.FakeGoogleAuth
import io.waves.ui.fake.FakeSyncService
import io.waves.ui.fake.InMemoryLibraryRepository
import io.waves.ui.fake.InMemorySettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Like advanceUntilIdle, but also drives work in backgroundScope (which advanceUntilIdle ignores). */
@OptIn(ExperimentalCoroutinesApi::class)
fun TestScope.settle() {
    advanceTimeBy(5_000)
    runCurrent()
}

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoverStateTest {
    @Test
    fun loadsTopCharts() = runTest {
        val s = DiscoverState(RecordingDirectory(listOf(podcastA, podcastB)), backgroundScope)
        settle()
        assertEquals(listOf(podcastA, podcastB), s.state.value.topPodcasts)
        assertFalse(s.state.value.topLoading)
    }

    @Test
    fun topChartsErrorIsReported() = runTest {
        val s = DiscoverState(RecordingDirectory(emptyList(), failTop = true), backgroundScope)
        settle()
        assertEquals("offline", s.state.value.topError)
    }

    @Test
    fun searchIsDebounced() = runTest {
        val dir = RecordingDirectory(listOf(podcastA, podcastB))
        val s = DiscoverState(dir, backgroundScope, debounceMs = 300)
        runCurrent()
        s.onQueryChange("a")
        advanceTimeBy(100)
        s.onQueryChange("al")
        advanceTimeBy(100)
        s.onQueryChange("alpha")
        advanceTimeBy(100)
        assertTrue(dir.searches.isEmpty())
        settle()
        assertEquals(listOf("alpha"), dir.searches)
        assertEquals(listOf(podcastA), s.state.value.results)
        assertTrue(s.state.value.isSearching)

        s.clearQuery()
        settle()
        assertTrue(s.state.value.results.isEmpty())
        assertFalse(s.state.value.isSearching)
    }

    @Test
    fun applePodcastsUrlResolvesViaLookup() = runTest {
        val dir = RecordingDirectory(listOf(podcastA, podcastB))
        val s = DiscoverState(dir, backgroundScope)
        s.onQueryChange("https://podcasts.apple.com/us/podcast/beta-cast/id222?i=1000")
        settle()
        assertEquals(listOf(222L), dir.lookups)
        assertTrue(dir.searches.isEmpty())
        assertEquals(listOf(podcastB), s.state.value.results)
        assertTrue(s.state.value.resolvedFromUrl)
    }

    @Test
    fun youtubeLinkResolvesAsAShowAndSkipsAppleSearch() = runTest {
        val dir = RecordingDirectory(listOf(podcastA))
        val channel = Podcast(
            feedUrl = "https://www.youtube.com/feeds/videos.xml?channel_id=UCexampleChannel00000000",
            title = "Example Channel",
            artworkUrl = "https://yt3.example/a.jpg",
        )
        val seen = mutableListOf<String>()
        val s = DiscoverState(dir, backgroundScope, resolveYoutube = { seen += it; channel })
        s.onQueryChange("https://www.youtube.com/@examplechannel/videos")
        settle()
        assertEquals(listOf("https://www.youtube.com/@examplechannel/videos"), seen)
        assertTrue(dir.searches.isEmpty())
        assertTrue(dir.lookups.isEmpty())
        assertEquals(listOf(channel), s.state.value.results)
        assertTrue(s.state.value.resolvedFromUrl)
    }

    @Test
    fun youtubeLinkWithoutAResolverReportsUnavailable() = runTest {
        val dir = RecordingDirectory(listOf(podcastA))
        val s = DiscoverState(dir, backgroundScope)
        s.onQueryChange("https://youtu.be/firstVideo1")
        settle()
        assertTrue(dir.searches.isEmpty())
        assertEquals("YouTube links aren't available", s.state.value.searchError)
        assertTrue(s.state.value.results.isEmpty())
    }

    @Test
    fun injectedUrlParserIsUsed() = runTest {
        val dir = RecordingDirectory(listOf(podcastA))
        val s = DiscoverState(dir, backgroundScope, parseAppleUrl = { 111L })
        s.onQueryChange("https://podcasts.apple.com/whatever")
        settle()
        assertEquals(listOf(111L), dir.lookups)
        assertEquals(listOf(podcastA), s.state.value.results)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PodcastDetailStateTest {
    private val episodes = listOf(episode(podcastA, 1), episode(podcastA, 2))
    private val feeds = MapFeedRepository(mapOf(podcastA.feedUrl to Feed(podcastA.copy(description = "fresh"), episodes)))
    private val library = InMemoryLibraryRepository()
    private val changes = Counter()

    @Test
    fun autoFetchUpsertsEpisodesNewestFirst() = runTest {
        val s = PodcastDetailState(podcastA, feeds, library, RecordingDirectory(emptyList()), backgroundScope, autoFetch = true, changes::inc)
        settle()
        assertEquals(listOf(podcastA.feedUrl), feeds.fetched)
        assertEquals(2, library.episodes(podcastA.feedUrl).first().size)
        assertEquals(listOf("g2", "g1"), s.state.value.episodes.map { it.episode.guid })
        assertEquals("fresh", s.state.value.podcast.description)
        assertFalse(s.state.value.refreshing)
    }

    @Test
    fun doesNotFetchWhenEpisodesStoredAndNotAutoFetch() = runTest {
        library.upsertEpisodes(podcastA.feedUrl, episodes)
        PodcastDetailState(podcastA, feeds, library, RecordingDirectory(emptyList()), backgroundScope, autoFetch = false)
        settle()
        assertTrue(feeds.fetched.isEmpty())
    }

    @Test
    fun openingWithJustATitleKeepsTheStoredCover() = runTest {
        library.rememberPodcast(podcastA.copy(artworkUrl = "https://a/cover.jpg", description = "stored"))
        library.upsertEpisodes(podcastA.feedUrl, episodes)
        val s = PodcastDetailState(
            Podcast(feedUrl = podcastA.feedUrl, title = podcastA.title), feeds, library,
            RecordingDirectory(emptyList()), backgroundScope, autoFetch = false,
        )
        settle()
        assertEquals("https://a/cover.jpg", s.state.value.podcast.artworkUrl)
        assertEquals("stored", s.state.value.podcast.description)
        assertEquals("https://a/cover.jpg", library.podcast(podcastA.feedUrl)?.artworkUrl)
    }

    @Test
    fun refreshingASubscribedShowStoresItsNewCover() = runTest {
        library.subscribe(podcastA.copy(artworkUrl = "https://a/old.jpg"))
        val fresh = MapFeedRepository(mapOf(podcastA.feedUrl to Feed(podcastA.copy(artworkUrl = "https://a/new.jpg"), episodes)))
        PodcastDetailState(podcastA, fresh, library, RecordingDirectory(emptyList()), backgroundScope, autoFetch = true)
        settle()
        assertEquals("https://a/new.jpg", library.subscriptions.first().single().artworkUrl)
    }

    @Test
    fun offlineListsOnlyPlayableEpisodes() = runTest {
        library.upsertEpisodes(podcastA.feedUrl, episodes)
        val playable = MutableStateFlow<Set<EpisodeId>?>(setOf(episodes[1].id))
        val s = PodcastDetailState(
            podcastA, feeds, library, RecordingDirectory(emptyList()), backgroundScope, autoFetch = false,
            offline = MutableStateFlow(true), playable = playable,
        )
        settle()
        assertEquals(listOf(episodes[1].id), s.state.value.episodes.map { it.episode.id })
        playable.value = null
        settle()
        assertEquals(episodes.size, s.state.value.episodes.size)
    }

    @Test
    fun subscribeToggleNotifiesLocalChange() = runTest {
        val s = PodcastDetailState(podcastA, feeds, library, RecordingDirectory(emptyList()), backgroundScope, autoFetch = true, changes::inc)
        settle()
        assertFalse(s.state.value.subscribed)
        s.toggleSubscribe()
        settle()
        assertTrue(s.state.value.subscribed)
        assertEquals(listOf(podcastA.feedUrl), library.subscriptions.first().map { it.feedUrl })
        s.toggleSubscribe()
        settle()
        assertFalse(s.state.value.subscribed)
        assertEquals(2, changes.count)
    }

    @Test
    fun fetchFailureShowsError() = runTest {
        val s = PodcastDetailState(podcastB, feeds, library, RecordingDirectory(emptyList()), backgroundScope, autoFetch = true)
        settle()
        assertNotNull(s.state.value.error)
        assertFalse(s.state.value.refreshing)
    }

    @Test
    fun blankFeedUrlIsResolvedViaLookup() = runTest {
        val chartEntry = Podcast(feedUrl = "", title = "Alpha Show", appleId = 111)
        val s = PodcastDetailState(chartEntry, feeds, library, RecordingDirectory(listOf(podcastA)), backgroundScope, autoFetch = true)
        settle()
        assertEquals(podcastA.feedUrl, s.state.value.podcast.feedUrl)
        assertEquals(2, s.state.value.episodes.size)
    }

    @Test
    fun progressAndPlayedStateAreExposed() = runTest {
        val s = PodcastDetailState(podcastA, feeds, library, RecordingDirectory(emptyList()), backgroundScope, autoFetch = true, changes::inc)
        settle()
        val row = s.state.value.episodes.first()
        library.saveProgress(PlaybackProgress(row.episode.id, 600_000, 600_000, true, 9))
        settle()
        assertTrue(s.state.value.episodes.first().played)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class HomeStateTest {
    @Test
    fun refreshAllFetchesEverySubscriptionAndReportsFailures() = runTest {
        val library = InMemoryLibraryRepository()
        library.subscribe(podcastA)
        library.subscribe(podcastB)
        val feeds = MapFeedRepository(mapOf(podcastA.feedUrl to Feed(podcastA, listOf(episode(podcastA, 1)))))
        val s = HomeState(library, feeds, backgroundScope)
        settle()
        assertEquals(listOf("Alpha Show", "Beta Cast"), s.state.value.subscriptions.map { it.title })

        s.refreshAll()
        settle()
        assertEquals(setOf(podcastA.feedUrl, podcastB.feedUrl), feeds.fetched.toSet())
        assertEquals(1, library.episodes(podcastA.feedUrl).first().size)
        assertFalse(s.state.value.refreshing)
        assertEquals("Couldn't refresh: Beta Cast", s.state.value.error)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryStateTest {
    private fun entry(at: Long, device: String = "d") =
        HistoryEntry(EpisodeId("f", "g$at"), "E$at", "P", at, device)

    @Test
    fun groupsByDayNewestFirst() {
        val day = 24 * 3600 * 1000L
        val days = HistoryState.groupByDay(listOf(entry(1_000), entry(day + 5), entry(day + 10, "other")), TimeZone.UTC)
        assertEquals(listOf(LocalDate(1970, 1, 2), LocalDate(1970, 1, 1)), days.map { it.date })
        assertEquals(listOf(day + 10, day + 5), days[0].entries.map { it.playedAtEpochMs })
    }

    @Test
    fun listsEachEpisodeOnceAtItsLatestSession() {
        val day = 24 * 3600 * 1000L
        val id = EpisodeId("f", "same")
        fun play(at: Long, device: String) = HistoryEntry(id, "E", "P", at, device)
        val days = HistoryState.groupByDay(
            listOf(play(1_000, "a"), play(day + 5, "b"), play(day + 1, "a"), entry(2_000)),
            TimeZone.UTC,
        )
        assertEquals(listOf(play(day + 5, "b")), days[0].entries)
        assertEquals(listOf(entry(2_000)), days[1].entries)
    }

    @Test
    fun exposesLibraryHistory() = runTest {
        val library = InMemoryLibraryRepository()
        library.addHistory(entry(5))
        val s = HistoryState(library, backgroundScope, TimeZone.UTC)
        settle()
        assertTrue(s.state.value.loaded)
        assertEquals(1, s.state.value.days.single().entries.size)
    }

    @Test
    fun dayLabels() {
        val today = LocalDate(2026, 10, 19)
        assertEquals("Today", dayLabel(today, today))
        assertEquals("Yesterday", dayLabel(LocalDate(2026, 10, 18), today))
        assertEquals("Thursday, October 1", dayLabel(LocalDate(2026, 10, 1), today))
        assertEquals("Wednesday, December 31, 2025", dayLabel(LocalDate(2025, 12, 31), today))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoverCountryTest {
    @Test
    fun chartsAndSearchUseTheCountryAndReloadWhenItChanges() = runTest {
        val dir = RecordingDirectory(listOf(podcastA, podcastB))
        val country = MutableStateFlow("gb")
        val s = DiscoverState(dir, backgroundScope, debounceMs = 100, country = country)
        settle()
        assertEquals(listOf("gb"), dir.topCountries)

        s.onQueryChange("alpha")
        settle()
        assertEquals(listOf("gb"), dir.searchCountries)

        country.value = "jp"
        settle()
        assertEquals(listOf("gb", "jp"), dir.topCountries)
        assertEquals(listOf("gb", "jp"), dir.searchCountries, "the current search reruns in the new store")
        assertEquals(listOf(podcastA), s.state.value.results)

        s.loadTopCharts()
        settle()
        assertEquals("jp", dir.topCountries.last())
    }

    @Test
    fun effectiveCountryFollowsTheDeviceWhenUnset() {
        assertEquals("de", UserSettings(country = "de").effectiveCountry)
        val device = UserSettings().effectiveCountry
        assertEquals(2, device.length)
        assertEquals(device.lowercase(), device)
        assertEquals("us", normalizeRegion("") ?: "us")
        assertEquals("us", normalizeRegion("419") ?: "us")
        assertEquals("nl", normalizeRegion("NL"))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsStateTest {
    @Test
    fun editsAreStoredAndSyncCanBeDisabled() = runTest {
        val auth = FakeGoogleAuth()
        val sync = FakeSyncService(auth, clock = { 42 })
        val store = InMemorySettingsStore()
        val s = SettingsState(auth, sync, backgroundScope, store)
        s.setCountry("GB")
        s.setDefaultSpeed(1.5f)
        s.setSkipBackSeconds(10)
        s.setSkipForwardSeconds(45)
        s.setAutoDeleteDownloads(true)
        s.setSyncEnabled(false)
        settle()
        val expected = UserSettings(
            country = "gb", defaultSpeed = 1.5f, skipBackSeconds = 10, skipForwardSeconds = 45,
            autoDeleteDownloads = true, syncEnabled = false,
        )
        assertEquals(expected, store.settings.value)
        assertEquals(expected, s.state.value.settings)

        s.signIn()
        settle()
        assertTrue(s.state.value.auth is AuthState.SignedIn)
        assertEquals(SyncStatus.Idle, s.state.value.sync, "no sync after sign-in while disabled")
        assertFalse(s.state.value.canSyncNow)
        s.syncNow()
        settle()
        assertEquals(SyncStatus.Idle, s.state.value.sync)

        s.setSyncEnabled(true)
        settle()
        assertTrue(s.state.value.canSyncNow)
        s.syncNow()
        settle()
        assertEquals(SyncStatus.Done(42), s.state.value.sync)

        s.setCountry(null)
        settle()
        assertNull(store.settings.value.country)
    }

    @Test
    fun signInSyncsAndSignOut() = runTest {
        val auth = FakeGoogleAuth()
        val sync = FakeSyncService(auth, clock = { 42 })
        val s = SettingsState(auth, sync, backgroundScope)
        runCurrent()
        assertEquals(AuthState.SignedOut, s.state.value.auth)

        s.signIn()
        settle()
        assertTrue(s.state.value.auth is AuthState.SignedIn)
        assertEquals(SyncStatus.Done(42), s.state.value.sync)
        assertFalse(s.state.value.busy)

        s.signOut()
        settle()
        assertEquals(AuthState.SignedOut, s.state.value.auth)
    }

    @Test
    fun beingOfflineDisablesSyncNow() = runTest {
        val offline = MutableStateFlow(false)
        val auth = FakeGoogleAuth(AuthState.SignedIn("listener@example.com"))
        val sync = FakeSyncService(auth, clock = { 7 })
        val s = SettingsState(auth, sync, backgroundScope, InMemorySettingsStore(), offline)
        runCurrent()
        assertTrue(s.state.value.canSyncNow)
        offline.value = true
        settle()
        assertTrue(s.state.value.offline)
        assertFalse(s.state.value.canSyncNow)
        s.syncNow()
        settle()
        assertEquals(SyncStatus.Idle, s.state.value.sync)
    }

    @Test
    fun errorsAreSurfaced() = runTest {
        val auth = object : io.waves.sync.GoogleAuth by FakeGoogleAuth() {
            override suspend fun signIn() = error("cancelled by user")
        }
        val s = SettingsState(auth, FakeSyncService(auth), backgroundScope)
        s.signIn()
        settle()
        assertEquals("cancelled by user", s.state.value.error)
        assertFalse(s.state.value.busy)
    }
}

class NavigatorTest {
    @Test
    fun pushPopAndTabs() {
        val nav = Navigator()
        assertEquals(Tab.Home, nav.currentTab)
        assertFalse(nav.canGoBack)
        assertFalse(nav.pop())
        nav.push(Screen.PodcastDetail(podcastA, true))
        nav.push(Screen.NowPlaying)
        nav.push(Screen.NowPlaying)
        assertEquals(Screen.PodcastDetail(podcastA, true), nav.content)
        assertEquals(3, nav.backStack.size)
        assertTrue(nav.pop())
        assertTrue(nav.current is Screen.PodcastDetail)
        nav.select(Tab.Discover)
        assertEquals(listOf<Screen>(Screen.Root(Tab.Discover)), nav.backStack)
    }

    @Test
    fun backFromAnotherTabsRootStepsOneTabTowardsHome() {
        val nav = Navigator()
        nav.select(Tab.History)
        nav.push(Screen.NowPlaying)
        assertTrue(nav.pop())
        assertEquals(Screen.Root(Tab.History), nav.current)
        assertTrue(nav.canGoBack)
        assertTrue(nav.pop())
        assertEquals(listOf<Screen>(Screen.Root(Tab.Discover)), nav.backStack)
        assertTrue(nav.pop())
        assertEquals(listOf<Screen>(Screen.Root(Tab.Home)), nav.backStack)
        assertFalse(nav.canGoBack)
        assertFalse(nav.pop())
    }
}

class FormattingTest {
    @Test
    fun durations() {
        assertEquals("1:15", formatDuration(75_000))
        assertEquals("1:02:05", formatDuration(3_725_000))
        assertEquals("0:00", formatDuration(-5))
        assertEquals("45 min", formatMinutes(45 * 60_000L))
        assertEquals("1 h 5 min", formatMinutes(65 * 60_000L))
    }

    @Test
    fun speeds() {
        assertEquals("1x", formatSpeed(1f))
        assertEquals("1.25x", formatSpeed(1.25f))
        assertEquals("0.5x", formatSpeed(0.5f))
        assertEquals(0.5f, SpeedPresets.first())
        assertEquals(3f, SpeedPresets.last())
    }

    @Test
    fun htmlStripping() {
        assertEquals("a & b\nc", plainText("<p>a &amp; b</p><b>c</b>"))
    }
}
