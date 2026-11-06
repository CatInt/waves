package io.waves.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ScrollWheel
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasAnyDescendant
import io.waves.App
import io.waves.ui.fake.fakeDependencies
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Smoke test: drives the real UI with in-memory fakes through every screen. */
@OptIn(ExperimentalTestApi::class)
class WavesAppUiTest {
    /**
     * A tab in the top bar (screens may show the same word, e.g. Settings' "Discover" section). Unselected tabs may
     * be icon-only, labelled by content description.
     */
    private fun navTab(label: String) =
        (hasText(label) or hasContentDescription(label)) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    private fun ComposeUiTest.waitForText(text: String) =
        waitFor("text \"$text\"") { onAllNodes(named(text)).fetchSemanticsNodes().isNotEmpty() }

    /** Shown as [text], or named so for screen readers (Discover's grid shows covers alone). */
    private fun named(text: String) = hasText(text, substring = true) or hasContentDescription(text, substring = true)

    /** Discover's top charts, which list the whole fake catalogue; searches and show pages don't show this show. */
    private fun ComposeUiTest.waitForTopCharts() = waitForText(TOP_CHARTS_ONLY)

    private fun ComposeUiTest.waitForDescription(description: String, present: Boolean = true) =
        waitFor("\"$description\" ${if (present) "shown" else "gone"}") {
            onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty() == present
        }

    @Test
    fun walksThroughScreens() = runComposeUiTest {
        // A ticking position re-renders the screen every few hundred ms, so the UI would never go idle.
        val deps = fakeDependencies(playerTickMs = 60 * 60 * 1000L, downloadStepMs = 10)
        setContent { App(deps) }

        // The app opens on Home; with no subscriptions it points to Discover.
        waitForText("No subscriptions yet")
        onNodeWithText("Discover podcasts").performClick()
        waitForText("Signal & Waves")
        onAllNodes(named("Signal & Waves")).onFirst().performClick()

        waitForText("Signal & Waves: Episode 1")
        onNodeWithContentDescription("Subscribe").performClick()
        waitForDescription("Unsubscribe")

        onAllNodesWithContentDescription("Play").onFirst().performClick()
        waitForDescription("Pause")

        // Download the newest episode; the fake finishes in ~11 steps of 10 ms.
        onAllNodesWithContentDescription("Download").onFirst().performClick()
        waitForDescription("Downloaded")

        // Newest episode is listed (and played) first. Mini player -> Now Playing
        onNodeWithTag(MINI_PLAYER_TAG).performClick()
        waitForDescription("Collapse player")
        waitForDescription("Speed 1x")
        onAllNodesWithContentDescription("Forward 30 seconds").onFirst().performClick()
        onAllNodesWithContentDescription("Pause").onFirst().performClick()

        // Now Playing is full-screen. Collapse to the show, then go back to Discover.
        onNodeWithContentDescription("Collapse player").performClick()
        waitForText("Signal & Waves: Episode 1")
        onNodeWithContentDescription("Back").performClick()
        waitForTopCharts()
        onNode(navTab("Home")).performClick()
        // The episode paused part-way through is offered first in Up next, to continue.
        waitForText("Continue")
        waitForText("Signal & Waves: Episode")
        // The downloaded episode is in Up next, with its download control.
        waitForDescription("Downloaded")

        onNode(navTab("History")).performClick()
        waitForText("This device")

        onNode(navTab("Settings")).performClick()
        onNodeWithText("Sign in with Google").performClick()
        waitForText("listener@example.com")
        waitForText("Last synced")
        // Mouse clicks land on the floating bar once this row sits at the bottom of the scroll viewport.
        onNodeWithText("Delete downloads after playing").performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        val autoDelete = isToggleable() and hasText("Delete downloads", substring = true)
        waitFor("auto-delete on") { onAllNodes(autoDelete and isOn()).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(deps.settings.settings.value.autoDeleteDownloads)
        // Turning sync off says so. (History's header says it too, briefly; the test clock runs past that at once.)
        onNodeWithText("Sync with Google Drive").performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        waitForText("This device neither uploads nor downloads changes.")
        assertFalse(deps.settings.settings.value.syncEnabled)

        onNode(navTab("Discover")).performClick()
        onNode(hasSetTextAction()).performTextInput("https://podcasts.apple.com/us/podcast/slow-radio/id1000000002")
        waitForText("Slow Radio")
    }

    @Test
    fun trackpadSwipeTurnsOneTab() = runComposeUiTest {
        setContent { App(fakeDependencies(playerTickMs = 60 * 60 * 1000L)) }
        waitForText("No subscriptions yet")
        onNode(navTab("Home") and isSelected()).assertExists()

        // The page follows the scroll; a short nudge springs back.
        onNodeWithText("No subscriptions yet").performMouseInput {
            moveTo(center)
            repeat(3) { scroll(1f, ScrollWheel.Horizontal) }
        }
        mainClock.advanceTimeBy(1_000)
        onNode(navTab("Home") and isSelected()).assertExists()
        onNodeWithText("No subscriptions yet").assertIsDisplayed()

        // A trackpad swipe is a long burst of small horizontal scroll events (momentum included): one tab only.
        onNodeWithText("No subscriptions yet").performMouseInput {
            advanceEventTime(1_000)
            moveTo(center)
            repeat(60) { scroll(0.5f, ScrollWheel.Horizontal) }
        }
        waitForTopCharts()
        mainClock.advanceTimeBy(1_000)
        onNode(navTab("Discover") and isSelected()).assertExists()

        // Tapping a tab scrolls the pager there too; swiping back turns one tab.
        onNode(navTab("History")).performClick()
        waitForText("Nothing played yet")
        mainClock.advanceTimeBy(1_000)
        onNodeWithText("Nothing played yet").performMouseInput {
            advanceEventTime(1_000) // injected events run on their own clock; a pause separates the gestures
            moveTo(center)
            repeat(60) { scroll(-0.5f, ScrollWheel.Horizontal) }
        }
        mainClock.advanceTimeBy(1_000)
        waitFor("Discover selected") { onAllNodes(navTab("Discover") and isSelected()).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun restingFingersHoldATabSwipeUntilTheyLift() = runComposeUiTest {
        val gesture = MutableStateFlow(WheelGesture.Touching)
        setContent { CompositionLocalProvider(LocalWheelGesture provides gesture) { App(fakeDependencies(playerTickMs = 60 * 60 * 1000L)) } }
        waitForText("No subscriptions yet")

        // Past the turning point, then the fingers rest: the page stays where they left it.
        onNodeWithText("No subscriptions yet").performMouseInput {
            moveTo(center)
            repeat(60) { scroll(0.5f, ScrollWheel.Horizontal) }
        }
        mainClock.advanceTimeBy(1_000)
        onNode(navTab("Home") and isSelected()).assertExists()

        // Lifted, it turns.
        gesture.value = WheelGesture.Lifted
        mainClock.advanceTimeBy(1_000)
        waitFor("Discover selected") { onAllNodes(navTab("Discover") and isSelected()).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun restingFingersHoldThePlayerSheetUntilTheyLift() = runComposeUiTest {
        val gesture = MutableStateFlow(WheelGesture.Touching)
        val deps = fakeDependencies(playerTickMs = 60 * 60 * 1000L)
        val app = AppState(deps, deps.appScope)
        setContent { CompositionLocalProvider(LocalWheelGesture provides gesture) { App(deps, app) } }

        onNode(navTab("Discover")).performClick()
        waitForText("Slow Radio")
        onAllNodes(named("Slow Radio")).onFirst().performClick()
        waitForText("Slow Radio: Episode 1")
        onAllNodesWithContentDescription("Play").onFirst().performClick()
        waitFor("mini player") { onAllNodesWithTag(MINI_PLAYER_TAG).fetchSemanticsNodes().isNotEmpty() }

        onNodeWithTag(MINI_PLAYER_TAG).performMouseInput {
            moveTo(center)
            repeat(50) { scroll(1f) }
        }
        // Fingers resting: the sheet is held part way, not opened.
        mainClock.advanceTimeBy(1_000)
        Thread.sleep(300) // the sheet's own pause rule counts real time; it must not let go either
        mainClock.advanceTimeBy(1_000)
        assertTrue(app.navigator.current != Screen.NowPlaying, "still held")

        gesture.value = WheelGesture.Lifted
        waitFor("opened") { app.navigator.current == Screen.NowPlaying }
    }

    @Test
    fun aTwoFingerDragKeepsTheSheetAfterItSlidesFromUnderThePointer() = runComposeUiTest {
        val gesture = MutableStateFlow(WheelGesture.Touching)
        val deps = fakeDependencies(playerTickMs = 60 * 60 * 1000L)
        val app = AppState(deps, deps.appScope)
        setContent { CompositionLocalProvider(LocalWheelGesture provides gesture) { App(deps, app) } }

        onNode(navTab("Discover")).performClick()
        waitForText("Slow Radio")
        onAllNodes(named("Slow Radio")).onFirst().performClick()
        waitForText("Slow Radio: Episode 1")
        onAllNodesWithContentDescription("Play").onFirst().performClick()
        waitFor("mini player") { onAllNodesWithTag(MINI_PLAYER_TAG).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(MINI_PLAYER_TAG).performClick()
        waitForDescription("Collapse player")

        // Down from the header until the sheet has slid well below the pointer, then back up, fingers down all along.
        // The pointer stirs a pixel between events, so each lands on whatever is under it by then, as on a Mac.
        onNodeWithContentDescription("Collapse player").performMouseInput {
            moveTo(center)
            repeat(30) { i ->
                moveBy(Offset(if (i % 2 == 0) 1f else -1f, 0f))
                scroll(-1f)
            }
            repeat(30) { i ->
                moveBy(Offset(if (i % 2 == 0) 1f else -1f, 0f))
                scroll(1f)
            }
        }
        mainClock.advanceTimeBy(500)
        gesture.value = WheelGesture.Lifted
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        // Back up, so it stays open: the upward half reached the sheet, not the page behind it.
        assertEquals(Screen.NowPlaying, app.navigator.current)
    }

    @Test
    fun verticalScrollWithSidewaysDriftScrollsTheList() = runComposeUiTest {
        setContent { Box(Modifier.size(DpSize(420.dp, 360.dp))) { App(fakeDependencies(playerTickMs = 60 * 60 * 1000L)) } }
        onNode(navTab("Discover")).performClick()
        waitForTopCharts()
        mainClock.advanceTimeBy(1_000)
        val listId = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .filterToOne(hasAnyDescendant(named(TOP_CHARTS_ONLY))).fetchSemanticsNode().id
        val list = onNode(SemanticsMatcher("list") { it.id == listId })
        val range = { list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange] }
        assertTrue(range().maxValue() > 0f, "list can scroll")
        assertEquals(0f, range().value())

        // A trackpad's vertical scroll often begins with a little sideways movement.
        list.performMouseInput {
            moveTo(center)
            scroll(0.4f, ScrollWheel.Horizontal)
            repeat(10) { scroll(1f) }
        }
        mainClock.advanceTimeBy(1_000)
        assertTrue(range().value() > 0f, "list scrolled")
        onNode(navTab("Discover") and isSelected()).assertExists()
    }

    @Test
    fun dragsPlayerOpenAndClosed() = runComposeUiTest {
        val deps = fakeDependencies(playerTickMs = 60 * 60 * 1000L)
        setContent { App(deps) }

        onNode(navTab("Discover")).performClick()
        waitForText("Slow Radio")
        onAllNodes(named("Slow Radio")).onFirst().performClick()
        waitForText("Slow Radio: Episode 1")
        onAllNodesWithContentDescription("Play").onFirst().performClick()
        waitFor("mini player") { onAllNodesWithTag(MINI_PLAYER_TAG).fetchSemanticsNodes().isNotEmpty() }

        onNodeWithTag(MINI_PLAYER_TAG).performTouchInput { swipeUp(startY = bottom, endY = top - 600f) }
        waitForDescription("Collapse player")
        assertTrue(onAllNodesWithTag(MINI_PLAYER_TAG).fetchSemanticsNodes().isEmpty())

        onNodeWithContentDescription("Collapse player").performTouchInput { swipeDown(startY = top, endY = top + 900f) }
        waitForDescription("Collapse player", present = false)
        onNodeWithTag(MINI_PLAYER_TAG).assertExists()
        waitForText("Slow Radio: Episode 1")

        // The title chip (cover, episode, show) leads to the show's page.
        onNodeWithContentDescription("Back").performClick()
        waitForTopCharts()
        onNodeWithTag(MINI_PLAYER_TAG).performClick()
        waitForDescription("Collapse player")
        onNodeWithText("Slow Radio", substring = false).performClick()
        waitForDescription("Collapse player", present = false)
        waitForText("Slow Radio: Episode 1")
    }

    @Test
    fun trackpadOpensAndClosesThePlayerAsADragWould() = runComposeUiTest {
        val deps = fakeDependencies(playerTickMs = 60 * 60 * 1000L)
        setContent { App(deps) }

        onNode(navTab("Discover")).performClick()
        waitForText("Slow Radio")
        onAllNodes(named("Slow Radio")).onFirst().performClick()
        waitForText("Slow Radio: Episode 1")
        onAllNodesWithContentDescription("Play").onFirst().performClick()
        waitFor("mini player") { onAllNodesWithTag(MINI_PLAYER_TAG).fetchSemanticsNodes().isNotEmpty() }

        // Two fingers moving up over the mini player open it; it settles once they stop.
        onNodeWithTag(MINI_PLAYER_TAG).performMouseInput {
            moveTo(center)
            repeat(50) { scroll(1f) }
        }
        waitForDescription("Collapse player")
        assertTrue(onAllNodesWithTag(MINI_PLAYER_TAG).fetchSemanticsNodes().isEmpty())

        // In Now Playing, moving down over its header closes it.
        onNodeWithContentDescription("Collapse player").performMouseInput {
            advanceEventTime(1_000)
            moveTo(center)
            repeat(60) { scroll(-1f) }
        }
        waitForDescription("Collapse player", present = false)
        onNodeWithTag(MINI_PLAYER_TAG).assertExists()

        // And over its scrolling text, already at the top.
        onNodeWithTag(MINI_PLAYER_TAG).performMouseInput {
            advanceEventTime(1_000)
            moveTo(center)
            repeat(50) { scroll(1f) }
        }
        waitForDescription("Collapse player")
        onAllNodesWithText("Slow Radio: Episode 1").onFirst().performMouseInput {
            advanceEventTime(1_000)
            moveTo(center)
            repeat(60) { scroll(-1f) }
        }
        waitForDescription("Collapse player", present = false)
    }

    @Test
    fun marksAnEpisodeCompleteAndHidesItsButton() = runComposeUiTest {
        val deps = fakeDependencies(playerTickMs = 60 * 60 * 1000L)
        setContent { App(deps) }

        onNode(navTab("Discover")).performClick()
        waitForText("Slow Radio")
        onAllNodes(named("Slow Radio")).onFirst().performClick()
        waitForText("Slow Radio: Episode 1")
        assertTrue(onAllNodesWithText("Played").fetchSemanticsNodes().isEmpty())
        val buttons = onAllNodesWithContentDescription("Mark complete").fetchSemanticsNodes().size

        onAllNodesWithContentDescription("Mark complete").onFirst().performClick()
        waitForText("Played")
        assertEquals(buttons - 1, onAllNodesWithContentDescription("Mark complete").fetchSemanticsNodes().size)
        assertTrue(onAllNodesWithTag(MINI_PLAYER_TAG).fetchSemanticsNodes().isEmpty(), "nothing played")
    }

    @Test
    fun creatorOpensSearchForTheirPodcasts() = runComposeUiTest {
        val deps = fakeDependencies(playerTickMs = 60 * 60 * 1000L)
        setContent { App(deps) }

        onNode(navTab("Discover")).performClick()
        waitForText("Slow Radio")
        onAllNodes(named("Slow Radio")).onFirst().performClick()
        waitForText("Slow Radio: Episode 1")

        onNodeWithText("The Quiet Studio").performClick()
        waitFor("search for the creator") {
            onAllNodes(hasSetTextAction() and hasText("The Quiet Studio")).fetchSemanticsNodes().isNotEmpty()
        }
        waitFor("search results") {
            onAllNodes(named(TOP_CHARTS_ONLY)).fetchSemanticsNodes().isEmpty() &&
                onAllNodes(named("Slow Radio")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun showPageLeftForTheCreatorsSearchGoesAtOnce() = runComposeUiTest {
        val deps = fakeDependencies(playerTickMs = 60 * 60 * 1000L)
        setContent { App(deps) }
        onNode(navTab("Discover")).performClick()
        waitForText("Slow Radio")
        onAllNodes(named("Slow Radio")).onFirst().performClick()
        waitForText("Slow Radio: Episode 1")

        mainClock.autoAdvance = false
        onNodeWithText("The Quiet Studio").performClick()
        // A couple of frames, not the cover's flight home.
        mainClock.advanceTimeBy(50)
        assertTrue(onAllNodesWithText("Slow Radio: Episode 1").fetchSemanticsNodes().isEmpty())
        mainClock.autoAdvance = true
    }
}

private const val TOP_CHARTS_ONLY = "Kotlin Weekly Audio"
