package io.waves.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FillPlanTest {
    /** A cache holding [spans] (start until end, exclusive), answering like `Cache.getCachedLength`. */
    private fun cache(vararg spans: LongRange): (Long, Long) -> Long = { position, length ->
        val end = if (Long.MAX_VALUE - position < length) Long.MAX_VALUE else position + length
        val inside = spans.firstOrNull { position >= it.first && position < it.last }
        if (inside != null) {
            minOf(inside.last, end) - position
        } else {
            val next = spans.filter { it.first > position }.minOfOrNull { it.first } ?: Long.MAX_VALUE
            -(minOf(next, end) - position)
        }
    }

    @Test
    fun theGapAtThePlayheadComesFirst() {
        // Cached 0..100 (streamed so far), playing at 100: fill from 100 on, not from a gap behind.
        assertEquals(FillGap(100, 900), nextFillGap(cache(0L..100L), playhead = 100, contentLength = 1_000))
        // Seeked to 600 in a gap: start right there, not at the gap's start.
        assertEquals(FillGap(600, 400), nextFillGap(cache(0L..100L), playhead = 600, contentLength = 1_000))
    }

    @Test
    fun cachedBytesAheadAreSkipped() {
        assertEquals(FillGap(700, 300), nextFillGap(cache(0L..100L, 500L..700L), playhead = 550, contentLength = 1_000))
        assertEquals(FillGap(100, 400), nextFillGap(cache(0L..100L, 500L..700L), playhead = 50, contentLength = 1_000))
    }

    @Test
    fun onceEverythingAheadIsCachedTheGapsBehindFollow() {
        assertEquals(FillGap(100, 400), nextFillGap(cache(0L..100L, 500L..1_000L), playhead = 800, contentLength = 1_000))
        assertNull(nextFillGap(cache(0L..1_000L), playhead = 300, contentLength = 1_000))
    }

    @Test
    fun anUnknownSizeRunsToAnEndNotKnownYet() {
        assertEquals(FillGap(100, FillGap.UNKNOWN), nextFillGap(cache(0L..100L), playhead = 100, contentLength = -1))
        assertEquals(FillGap(100, 400), nextFillGap(cache(0L..100L, 500L..600L), playhead = 0, contentLength = -1))
    }
}
