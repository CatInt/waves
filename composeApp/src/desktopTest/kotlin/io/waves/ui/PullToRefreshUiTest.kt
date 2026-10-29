package io.waves.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ScrollWheel
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
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

    @Composable
    private fun Rows() {
        LazyColumn(Modifier.fillMaxSize().testTag("list")) {
            items(30) { Text("Row $it", Modifier.fillMaxWidth().height(48.dp)) }
        }
    }

    private fun ComposeUiTest.wheelPull(ticks: Int = 20) {
        waitForIdle()
        mainClock.autoAdvance = false
        repeat(ticks) {
            onNodeWithTag("list").performMouseInput { scroll(-1f, ScrollWheel.Vertical) }
            mainClock.advanceTimeBy(16)
        }
        mainClock.advanceTimeBy(1000)
        mainClock.autoAdvance = true
        waitForIdle()
    }
}
