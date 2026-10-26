package io.waves.ui

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

class EpisodeItemTextTest {
    private val now = 1_761_350_400_000L // Oct 25, 2025 00:00 UTC
    private fun ago(ms: Long) = formatAge(now - ms, now, TimeZone.UTC)

    @Test
    fun agesReadLikeSpeech() {
        assertEquals("Just now", ago(30_000))
        assertEquals("5 min ago", ago(5 * 60_000))
        assertEquals("1 hour ago", ago(61 * 60_000))
        assertEquals("21 hours ago", ago(21 * 3_600_000))
        assertEquals("Yesterday", ago(30 * 3_600_000))
        assertEquals("3 days ago", ago(3 * 86_400_000L))
        assertEquals(formatShortDate(now - 15 * 86_400_000L, TimeZone.UTC), ago(15 * 86_400_000L))
    }

    @Test
    fun summaryIsOneParagraphOfPlainText() {
        assertEquals(
            "The story of how \"Who Let The Dogs Out\" ended up stuck in all of our brains.",
            summaryText("<p>The story of how &quot;Who Let The Dogs Out&quot;</p>\n\n<p>ended up stuck in all of our  brains.</p>"),
        )
    }
}
