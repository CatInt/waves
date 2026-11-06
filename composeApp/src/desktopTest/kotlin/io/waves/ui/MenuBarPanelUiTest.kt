package io.waves.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ScrollWheel
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import io.waves.ui.fake.SampleData
import io.waves.ui.fake.fakeDependencies
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalTestApi::class)
class MenuBarPanelUiTest {
    @Test
    fun playsFromUpNextAndShowsTransport() = runComposeUiTest {
        val deps = fakeDependencies(playerTickMs = 60 * 60 * 1000L)
        val show = SampleData.podcasts.first { it.title == "Signal & Waves" }
        runBlocking { deps.library.subscribe(show) }
        val app = AppState(deps, deps.appScope)
        var opened = 0
        var quit = 0
        setContent { WavesTheme { MenuBarPanel(app, onOpenApp = { opened++ }, onQuit = { quit++ }) } }

        onNodeWithText("Nothing playing").assertExists()
        waitFor("an episode") {
            onAllNodesWithText("Signal & Waves: Episode", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        onAllNodesWithContentDescription("Play").onFirst().performClick()
        waitFor("Pause") { onAllNodesWithContentDescription("Pause").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithContentDescription("Back 15 seconds").assertExists()
        onNodeWithContentDescription("Forward 30 seconds").assertExists()

        onNodeWithContentDescription("Open Waves").performClick()
        assertEquals(1, opened)

        // The title chip opens Now Playing in the app, a show name opens the show.
        onNodeWithText("Pick an episode below").assertDoesNotExist()
        onAllNodesWithText("Signal & Waves: Episode 1").onFirst().performClick()
        assertEquals(Screen.NowPlaying, app.navigator.current)
        assertEquals(2, opened)
        onAllNodesWithText("Signal & Waves").onLast().performClick()
        assertEquals(Screen.PodcastDetail(show, autoFetch = false), app.navigator.current)
        assertEquals(3, opened)

        assertEquals(0, quit)
        onNodeWithContentDescription("Quit Waves").performClick()
        assertEquals(1, quit)
    }

    @Test
    fun twoFingersPullUpNextDownToRefreshIt() = runComposeUiTest {
        val deps = fakeDependencies(playerTickMs = 60 * 60 * 1000L)
        val show = SampleData.podcasts.first { it.title == "Signal & Waves" }
        runBlocking { deps.library.subscribe(show) }
        val app = AppState(deps, deps.appScope)
        val gesture = MutableStateFlow(WheelGesture.Touching)
        setContent {
            CompositionLocalProvider(LocalWheelGesture provides gesture) {
                WavesTheme { MenuBarPanel(app, onOpenApp = {}, onQuit = {}) }
            }
        }
        waitFor("an episode") {
            onAllNodesWithText("Signal & Waves: Episode", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        // Two fingers pull the list down past its top, then rest: held, as under a finger, not let go.
        mainClock.autoAdvance = false
        val row = onAllNodesWithText("Signal & Waves: Episode", substring = true).onFirst()
        row.performMouseInput { moveTo(center) }
        repeat(30) {
            row.performMouseInput { scroll(-1f, ScrollWheel.Vertical) }
            mainClock.advanceTimeBy(16)
        }
        mainClock.advanceTimeBy(1_000)
        assertFalse(app.home.state.value.refreshing, "held while the fingers rest")

        // Lifted, it refreshes.
        gesture.value = WheelGesture.Lifted
        mainClock.autoAdvance = true
        waitFor("refreshing") { app.home.state.value.refreshing }
    }
}
