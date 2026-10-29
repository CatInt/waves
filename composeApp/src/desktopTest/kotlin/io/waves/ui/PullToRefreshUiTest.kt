package io.waves.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ScrollWheel
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.waves.sync.AuthState
import io.waves.sync.SyncService
import io.waves.sync.SyncStatus
import io.waves.ui.fake.FakeGoogleAuth
import io.waves.ui.fake.InMemoryLibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class, ExperimentalMaterial3Api::class)
class PullToRefreshUiTest {
    @Test
    fun wheelPullAtTopRefreshes() = runComposeUiTest {
        var refreshes = 0
        setContent { WavesPullToRefreshBox(isRefreshing = false, onRefresh = { refreshes++ }) { Rows() } }
        wheelPull()
        assertEquals(1, refreshes)
    }

    @Test
    fun pullThatRefreshesNothingStillHides() = runComposeUiTest {
        var refreshes = 0
        lateinit var state: PullToRefreshState
        setContent {
            state = rememberPullToRefreshState()
            WavesPullToRefreshBox(isRefreshing = false, onRefresh = { refreshes++ }, state = state) { Rows() }
        }
        wheelPull()
        assertEquals(1, refreshes)
        assertEquals(0f, state.distanceFraction)
    }

    @Test
    fun restingFingersHoldThePullUntilTheyLift() = runComposeUiTest {
        var refreshes = 0
        val gesture = MutableStateFlow(WheelGesture.Touching)
        setContent {
            CompositionLocalProvider(LocalWheelGesture provides gesture) {
                WavesPullToRefreshBox(isRefreshing = false, onRefresh = { refreshes++ }) { Rows() }
            }
        }
        wheelPull()
        assertEquals(0, refreshes)
        gesture.value = WheelGesture.Lifted
        waitForIdle()
        assertEquals(1, refreshes)
    }

    @Test
    fun momentumNeverPulls() = runComposeUiTest {
        var refreshes = 0
        val gesture = MutableStateFlow(WheelGesture.Momentum)
        setContent {
            CompositionLocalProvider(LocalWheelGesture provides gesture) {
                WavesPullToRefreshBox(isRefreshing = false, onRefresh = { refreshes++ }) { Rows() }
            }
        }
        wheelPull()
        assertEquals(0, refreshes)
    }

    @Test
    fun macPhasesMapToGestures() {
        assertEquals(WheelGesture.Unknown, MacWheelGesture.gestureOf(0, 0))
        assertEquals(WheelGesture.Touching, MacWheelGesture.gestureOf(32, 0)) // may begin
        assertEquals(WheelGesture.Touching, MacWheelGesture.gestureOf(1, 0)) // began
        assertEquals(WheelGesture.Touching, MacWheelGesture.gestureOf(4, 0)) // changed
        assertEquals(WheelGesture.Lifted, MacWheelGesture.gestureOf(8, 0)) // ended
        assertEquals(WheelGesture.Lifted, MacWheelGesture.gestureOf(16, 0)) // cancelled
        assertEquals(WheelGesture.Momentum, MacWheelGesture.gestureOf(0, 1))
        assertEquals(WheelGesture.Momentum, MacWheelGesture.gestureOf(0, 4))
        assertEquals(WheelGesture.Lifted, MacWheelGesture.gestureOf(0, 8))
    }

    @Test
    fun settleMillisHoldsTheGapOpenAfterRefreshing() = runComposeUiTest {
        var refreshing by mutableStateOf(false)
        lateinit var state: PullToRefreshState
        setContent {
            state = rememberPullToRefreshState()
            WavesPullToRefreshBox(isRefreshing = refreshing, onRefresh = { refreshing = true }, state = state, settleMillis = 400) {
                Rows()
            }
        }
        wheelPull()
        assertEquals(1f, state.distanceFraction)
        mainClock.autoAdvance = false
        refreshing = false
        mainClock.advanceTimeBy(300)
        assertEquals(1f, state.distanceFraction)
        repeat(15) { mainClock.advanceTimeBy(100) }
        assertEquals(0f, state.distanceFraction)
    }

    @Test
    fun pullOnAGridReachesRefresh() = runComposeUiTest {
        var refreshes = 0
        val gesture = MutableStateFlow(WheelGesture.Touching)
        setContent {
            CompositionLocalProvider(LocalWheelGesture provides gesture) {
                WavesPullToRefreshBox(isRefreshing = false, onRefresh = { refreshes++ }) {
                    LazyVerticalGrid(columns = GridCells.Adaptive(100.dp), modifier = Modifier.fillMaxSize()) {
                        items(40) { Text("Show $it", Modifier.fillMaxWidth().height(72.dp)) }
                    }
                }
            }
        }
        // The first tile sits at the top, so the gap opens under the pointer.
        wheelPull(overText = "Show 0", ticks = 30)
        assertEquals(0, refreshes)
        gesture.value = WheelGesture.Lifted
        waitForIdle()
        assertEquals(1, refreshes)
    }

    @Test
    fun liftThenCoastingClosesTheGap() = runComposeUiTest {
        var refreshing by mutableStateOf(false)
        var refreshes = 0
        lateinit var state: PullToRefreshState
        val gesture = MutableStateFlow(WheelGesture.Touching)
        setContent {
            state = rememberPullToRefreshState()
            val scope = rememberCoroutineScope()
            CompositionLocalProvider(LocalWheelGesture provides gesture) {
                WavesPullToRefreshBox(
                    isRefreshing = refreshing,
                    onRefresh = {
                        refreshes++
                        // Discover turns the flag on after this returns, then off when the load finishes.
                        scope.launch {
                            refreshing = true
                            delay(30)
                            refreshing = false
                        }
                    },
                    state = state,
                ) { Rows() }
            }
        }
        val resting = onNodeWithText("Row 0").getBoundsInRoot().top
        wheelPull(ticks = 30)
        assertEquals(0, refreshes)
        gesture.value = WheelGesture.Lifted
        waitForIdle()
        assertEquals(1, refreshes)
        // The coast after a lift keeps delivering wheel events. It must not start the refresh over.
        gesture.value = WheelGesture.Momentum
        wheelPull(ticks = 30)
        waitUntil(timeoutMillis = 5_000) { state.distanceFraction == 0f }
        assertEquals(resting.value, onNodeWithText("Row 0").getBoundsInRoot().top.value, 1f)
        assertEquals(1, refreshes)
    }

    @Test
    fun pullLandingJustPastTheThresholdRefreshesAndCloses() = runComposeUiTest {
        var refreshing by mutableStateOf(false)
        var refreshes = 0
        lateinit var state: PullToRefreshState
        val gesture = MutableStateFlow(WheelGesture.Touching)
        setContent {
            state = rememberPullToRefreshState()
            val scope = rememberCoroutineScope()
            CompositionLocalProvider(LocalWheelGesture provides gesture) {
                WavesPullToRefreshBox(
                    isRefreshing = refreshing,
                    onRefresh = {
                        refreshes++
                        scope.launch {
                            refreshing = true
                            delay(300)
                            refreshing = false
                        }
                    },
                    state = state,
                ) { Rows() }
            }
        }
        // Trackpad-sized steps stop the pull a hair past the threshold, where letting go nudges it on first. That nudge
        // landing mid-release used to cancel Material's refresh and leave the spinner hanging.
        wheelPull(ticks = 40, delta = 0.5f)
        gesture.value = WheelGesture.Lifted
        waitForIdle()
        assertEquals(1, refreshes)
        waitUntil(timeoutMillis = 5_000) { !refreshing && state.distanceFraction == 0f }
    }

    @Test
    fun touchPullRefreshesOnlyOnceTheFingerLifts() = runComposeUiTest {
        var refreshes = 0
        setContent { WavesPullToRefreshBox(isRefreshing = false, onRefresh = { refreshes++ }) { Rows() } }
        waitForIdle()
        mainClock.autoAdvance = false
        onNodeWithTag("list").performTouchInput {
            down(topCenter + Offset(0f, 10f))
            repeat(30) {
                moveBy(Offset(0f, 10f))
                advanceEventTime(16)
            }
        }
        // The finger rests past the threshold: no refresh yet.
        repeat(10) { mainClock.advanceTimeBy(100) }
        assertEquals(0, refreshes)
        onNodeWithTag("list").performTouchInput { up() }
        // Material eases the pull back onto the threshold before refreshing.
        repeat(10) { mainClock.advanceTimeBy(100) }
        assertEquals(1, refreshes)
        mainClock.autoAdvance = true
    }

    @Test
    fun pullStopsAtTheRefreshThreshold() = runComposeUiTest {
        lateinit var state: PullToRefreshState
        setContent {
            state = rememberPullToRefreshState()
            CompositionLocalProvider(LocalWheelGesture provides MutableStateFlow(WheelGesture.Touching)) {
                WavesPullToRefreshBox(isRefreshing = false, onRefresh = {}, state = state) { Rows() }
            }
        }
        val resting = onNodeWithText("Row 0").getBoundsInRoot().top
        wheelPull(ticks = 60)
        val pulled = onNodeWithText("Row 0").getBoundsInRoot().top - resting
        assertTrue(state.distanceFraction > 1f, "pulled to ${state.distanceFraction}")
        // The gap stops at the distance that triggers a refresh, whatever the pull does past it.
        assertEquals(PullToRefreshDefaults.PositionalThreshold.value, pulled.value, 1f)
    }

    @Test
    fun contentHoldsStillWhileAPullRefreshes() = runComposeUiTest {
        var refreshing by mutableStateOf(false)
        lateinit var state: PullToRefreshState
        val list = LazyListState()
        setContent {
            state = rememberPullToRefreshState()
            WavesPullToRefreshBox(isRefreshing = refreshing, onRefresh = { refreshing = true }, state = state) { Rows(list) }
        }
        wheelPull()
        assertTrue(refreshing)
        wheelPull(ticks = 10, down = false)
        assertEquals(0, list.firstVisibleItemIndex)
        assertEquals(0, list.firstVisibleItemScrollOffset)
        assertEquals(1f, state.distanceFraction)

        refreshing = false
        repeat(20) { mainClock.advanceTimeBy(100) }
        wheelPull(ticks = 10, down = false)
        assertTrue(list.firstVisibleItemIndex > 0)
    }

    @Test
    fun refreshNobodyPulledForLeavesTheContentBe() = runComposeUiTest {
        lateinit var state: PullToRefreshState
        setContent {
            state = rememberPullToRefreshState()
            WavesPullToRefreshBox(isRefreshing = true, onRefresh = {}, state = state) { Rows() }
        }
        mainClock.advanceTimeBy(1000)
        assertEquals(0f, state.distanceFraction)
    }

    @Test
    fun plainMaterialBoxIgnoresWheelPull() = runComposeUiTest {
        var refreshes = 0
        setContent { PullToRefreshBox(isRefreshing = false, onRefresh = { refreshes++ }) { Rows() } }
        wheelPull()
        assertEquals(0, refreshes)
    }

    @Test
    fun shortWheelPullDoesNotRefresh() = runComposeUiTest {
        var refreshes = 0
        setContent { WavesPullToRefreshBox(isRefreshing = false, onRefresh = { refreshes++ }) { Rows() } }
        wheelPull(ticks = 1)
        assertEquals(0, refreshes)
    }

    @Test
    fun wheelPullOnHistorySyncsWhenSignedIn() = runComposeUiTest {
        val sync = CountingSync()
        setHistory(AuthState.SignedIn("listener@example.com"), sync)
        wheelPull("Nothing played yet")
        assertEquals(1, sync.calls.get())
    }

    @Test
    fun wheelPullOnHistoryDoesNothingWhenSignedOut() = runComposeUiTest {
        val sync = CountingSync()
        setHistory(AuthState.SignedOut, sync)
        wheelPull("Nothing played yet")
        mainClock.advanceTimeBy(500)
        assertEquals(0, sync.calls.get())
    }

    private fun ComposeUiTest.setHistory(auth: AuthState, sync: SyncService) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val history = HistoryState(InMemoryLibraryRepository(), scope)
        val settings = SettingsState(FakeGoogleAuth(auth), sync, scope)
        setContent { HistoryScreen(history, deviceId = "this-device", onPlay = {}, sync = settings) }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Nothing played yet").fetchSemanticsNodes().isNotEmpty() }
    }

    private class CountingSync : SyncService {
        val calls = AtomicInteger()
        override val status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
        override suspend fun syncNow() {
            calls.incrementAndGet()
        }
    }

    @Composable
    private fun Rows(state: LazyListState = rememberLazyListState()) {
        LazyColumn(Modifier.fillMaxSize().testTag("list"), state = state) {
            items(30) { Text("Row $it", Modifier.fillMaxWidth().height(48.dp)) }
        }
    }

    /** Wheel [ticks] of [delta] each pulling down, or with [down] false scrolling the content on. */
    private fun ComposeUiTest.wheelPull(overText: String? = null, ticks: Int = 20, down: Boolean = true, delta: Float = 1f) {
        waitForIdle()
        mainClock.autoAdvance = false
        val target = if (overText != null) onNodeWithText(overText) else onNodeWithTag("list")
        target.performMouseInput { moveTo(center) }
        repeat(ticks) {
            target.performMouseInput { scroll(if (down) -delta else delta, ScrollWheel.Vertical) }
            mainClock.advanceTimeBy(16)
        }
        mainClock.advanceTimeBy(1000)
        mainClock.autoAdvance = true
        waitForIdle()
    }
}
