package io.waves.ui

import androidx.compose.ui.test.ExperimentalTestApi
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
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
