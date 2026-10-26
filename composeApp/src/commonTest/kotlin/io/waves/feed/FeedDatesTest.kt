package io.waves.feed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FeedDatesTest {
    // 2026-10-18T10:00:00Z
    private val t = 1_792_317_600_000L

    @Test
    fun rfc822Variants() {
        listOf(
            "Sun, 18 Oct 2026 10:00:00 +0000",
            "Sun, 18 Oct 2026 10:00:00 GMT",
            "Sun, 18 Oct 2026 10:00:00 UT",
            "Sun, 18 Oct 2026 10:00:00 Z",
            "18 Oct 2026 10:00:00 +0000",
            "Sun,18 Oct 2026 10:00:00 +0000",
            "Sunday, 18 October 2026 10:00:00 GMT",
            "Sun, 18 Oct 26 10:00:00 GMT",
            "Sun, 18 Oct 2026 10:00 GMT",
            "Sun, 18 Oct 2026 12:00:00 +0200",
            "Sun, 18 Oct 2026 12:00:00 +02:00",
            "Sun, 18 Oct 2026 12:00:00 GMT+0200",
            "Sun, 18 Oct 2026 06:00:00 EDT",
            "Sun, 18 Oct 2026 03:00:00 PDT",
            "Sun, 18 Oct 2026 10:00:00 +0000 (UTC)",
            "  Sun,  18  Oct  2026  10:00:00  +0000 ",
            "Sun, 18 Oct 2026 10:00:00",
            "Sun, 18 Oct 2026 10:00:00.000 +0000",
            "Sun, 18 Oct 2026 10:00:00 XYZ", // unknown zone → assume UTC
        ).forEach { assertEquals(t, FeedDates.parseDate(it), it) }
        assertEquals(t - 10 * 3_600_000L, FeedDates.parseDate("Sun, 18 Oct 2026"))
    }

    @Test
    fun iso8601Variants() {
        assertEquals(t, FeedDates.parseDate("2026-10-18T10:00:00Z"))
        assertEquals(t, FeedDates.parseDate("2026-10-18T10:00:00.123Z"))
        assertEquals(t, FeedDates.parseDate("2026-10-18T12:00:00+02:00"))
        assertEquals(t, FeedDates.parseDate("2026-10-18 10:00:00"))
    }

    @Test
    fun epochEdges() {
        assertEquals(0L, FeedDates.parseDate("Thu, 01 Jan 1970 00:00:00 GMT"))
        assertEquals(951_782_400_000L, FeedDates.parseDate("Tue, 29 Feb 2000 00:00:00 GMT"))
    }

    @Test
    fun garbageDates() {
        listOf(null, "", "yesterday", "Sun, 18 Foo 2026 10:00:00 GMT", "Sun, 45 Oct 2026 10:00:00 GMT").forEach {
            assertNull(FeedDates.parseDate(it), it)
        }
    }

    @Test
    fun durations() {
        assertEquals(3723L, FeedDates.parseDuration("01:02:03"))
        assertEquals(3723L, FeedDates.parseDuration("1:2:3"))
        assertEquals(2730L, FeedDates.parseDuration("45:30"))
        assertEquals(5400L, FeedDates.parseDuration("90:00"))
        assertEquals(3600L, FeedDates.parseDuration("3600"))
        assertEquals(1234L, FeedDates.parseDuration(" 1234.6 "))
        assertNull(FeedDates.parseDuration(""))
        assertNull(FeedDates.parseDuration(null))
        assertNull(FeedDates.parseDuration("about an hour"))
        assertNull(FeedDates.parseDuration("-5"))
        assertNull(FeedDates.parseDuration("1:2:3:4"))
    }
}
