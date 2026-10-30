package io.waves.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking

@OptIn(ExperimentalTestApi::class)
class SnapToTopLineUiTest {
    private fun ComposeUiTest.rows(line: Int = 0, topPadding: Dp = 0.dp): LazyListState {
        val state = LazyListState()
        setContent {
            SnapToTopLine(state, topLine = { line })
            LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(top = topPadding)) {
                items(40) { Text("Row $it", Modifier.fillMaxWidth().height(160.dp)) }
            }
        }
        waitForIdle()
        return state
    }

    private fun ComposeUiTest.rest(state: LazyListState, index: Int, offset: Int) {
        runOnIdle { runBlocking { state.scrollToItem(index, offset) } }
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
    }

    @Test
    fun aRowJustPastTheLineComesBackOntoIt() = runComposeUiTest {
        val state = rows()
        val px = density.run { 10.dp.roundToPx() }
        rest(state, index = 5, offset = px)
        assertEquals(5 to 0, state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset)
    }

    @Test
    fun aRowJustShortOfTheLineGoesUpOntoIt() = runComposeUiTest {
        val state = rows()
        // Row 5 shows all but its last 10 dp: row 6's top is 10 dp below the line.
        rest(state, index = 5, offset = density.run { 150.dp.roundToPx() })
        assertEquals(6 to 0, state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset)
    }

    @Test
    fun underADividerAlongTheTopARowsDividerLandsOnIt() = runComposeUiTest {
        // As on Home under the shelf: the list starts at the divider, and a top padding keeps the first row below it.
        val padding = 12.dp
        val state = rows(topPadding = padding)
        val px = density.run { padding.roundToPx() }
        rest(state, index = 5, offset = 0)
        val row5 = state.layoutInfo.visibleItemsInfo.first { it.index == 5 }
        assertEquals(-px, row5.offset)
        // Back at the start, the first row (which has no divider) still rests below the padding.
        rest(state, index = 0, offset = 0)
        assertEquals(0 to 0, state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset)
    }

    @Test
    fun aSnapCutShortByAnotherScrollDoesNotStopLaterSnaps() = runComposeUiTest {
        var scrolling by mutableStateOf(false)
        var snaps = 0
        setContent {
            SnapToTopLine(
                scrolling = { scrolling },
                offsets = { listOf(10) },
                // As a list does when a finger takes over its scroll: it cancels the coroutine that was scrolling.
                scrollBy = {
                    snaps++
                    currentCoroutineContext()[Job]!!.cancel()
                    awaitCancellation()
                },
            )
        }
        // It snaps on first coming to rest too.
        waitForIdle()
        assertEquals(1, snaps)
        repeat(3) {
            scrolling = true
            waitForIdle()
            scrolling = false
            mainClock.advanceTimeBy(1_000)
            waitForIdle()
        }
        assertEquals(4, snaps)
    }

    @Test
    fun theDividerDoesNotRestAcrossARowsText() = runComposeUiTest {
        val state = LazyListState()
        lateinit var guides: SnapGuides
        setContent {
            guides = rememberSnapGuides()
            SnapToTopLine(state, guides = guides)
            LazyColumn(Modifier.fillMaxSize().snapGuideList(guides), state = state) {
                items(40) {
                    Column(Modifier.fillMaxWidth().height(160.dp)) {
                        Spacer(Modifier.height(60.dp))
                        Text("Title $it", Modifier.height(40.dp).keepWhole(guides))
                    }
                }
            }
        }
        waitForIdle()
        val divider = density.run { ListDividerHeight.toPx() }
        // Row 5's title runs from 10 dp above the divider to 30 dp below it, too far from any row's top to snap there.
        rest(state, index = 5, offset = density.run { 70.dp.roundToPx() })
        // It comes the shorter way out: shown whole, just below the divider.
        val row5 = state.layoutInfo.visibleItemsInfo.first { it.index == 5 }
        assertEquals(divider, row5.offset + density.run { 60.dp.toPx() }, 1f)
    }

    @Test
    fun aRowWellAwayFromTheLineStaysWhereItIs() = runComposeUiTest {
        val state = rows()
        val px = density.run { 80.dp.roundToPx() }
        rest(state, index = 5, offset = px)
        assertEquals(5 to px, state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset)
    }
}
