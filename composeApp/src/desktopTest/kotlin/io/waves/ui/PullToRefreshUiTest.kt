package io.waves.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ScrollWheel
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
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
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

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
    private fun Rows() {
        LazyColumn(Modifier.fillMaxSize().testTag("list")) {
            items(30) { Text("Row $it", Modifier.fillMaxWidth().height(48.dp)) }
        }
    }

    private fun ComposeUiTest.wheelPull(overText: String? = null, ticks: Int = 20) {
        waitForIdle()
        mainClock.autoAdvance = false
        val target = if (overText != null) onNodeWithText(overText) else onNodeWithTag("list")
        target.performMouseInput { moveTo(center) }
        repeat(ticks) {
            target.performMouseInput { scroll(-1f, ScrollWheel.Vertical) }
            mainClock.advanceTimeBy(16)
        }
        mainClock.advanceTimeBy(1000)
        mainClock.autoAdvance = true
        waitForIdle()
    }
}
