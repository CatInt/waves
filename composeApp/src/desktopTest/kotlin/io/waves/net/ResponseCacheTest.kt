package io.waves.net

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

class ResponseCacheTest {
    private lateinit var dir: File
    private var calls = 0

    @BeforeTest fun setUp() = runBlocking<Unit> {
        dir = Files.createTempDirectory("responses").toFile()
        ResponseCache.install(dir.path)
    }

    @AfterTest fun tearDown() {
        ResponseCache.uninstall()
        dir.deleteRecursively()
    }

    private suspend fun fetch(key: String = "k", ttl: Duration = 1.hours, staleOnError: Boolean = true, answer: () -> String) =
        ResponseCache.text(key, ttl, staleOnError) { calls++; answer() }

    @Test fun aFreshAnswerIsServedWithoutAskingAgainAndOutlivesMemory() = runBlocking<Unit> {
        assertEquals("one", fetch { "one" })
        assertEquals("one", fetch { "two" })
        assertEquals(1, calls)
        // From disk too: a new start (memory forgotten) still has it.
        ResponseCache.uninstall()
        ResponseCache.install(dir.path)
        assertEquals("one", fetch { "three" })
        assertEquals(1, calls)
    }

    @Test fun anExpiredAnswerIsFetchedAgainButStillStandsInWhenTheFetchFails() = runBlocking<Unit> {
        fetch(ttl = Duration.ZERO) { "old" }
        assertEquals("new", fetch(ttl = Duration.ZERO) { "new" })
        assertEquals("new", fetch(ttl = Duration.ZERO) { error("offline") })
        assertFailsWith<IllegalStateException> { fetch(ttl = Duration.ZERO, staleOnError = false) { error("offline") } }
    }

    @Test fun memoryHoldsNoMoreThanItsBudgetAndDropsTheLeastRecentlyUsedFirst() = runBlocking<Unit> {
        val chunk = "x".repeat(ResponseCache.MEMORY_ENTRY_CHARS)
        val many = ResponseCache.MEMORY_CHARS / chunk.length + 4
        repeat(many) { fetch("k$it") { chunk } }
        val (entries, chars) = ResponseCache.memoryFootprint()
        assertTrue(chars <= ResponseCache.MEMORY_CHARS, "$chars characters held")
        assertEquals(ResponseCache.MEMORY_CHARS / chunk.length, entries)
        // The first ones went out of memory but not off disk: answered without asking again.
        assertEquals(chunk, fetch("k0") { error("must come from disk") })
        assertEquals(many, calls)
    }

    @Test fun anAnswerTooBigForMemoryIsServedFromDisk() = runBlocking<Unit> {
        val big = "y".repeat(ResponseCache.MEMORY_ENTRY_CHARS + 1)
        fetch("small") { "s" }
        assertEquals(big, fetch("big") { big })
        assertEquals(1 to 1L, ResponseCache.memoryFootprint(), "only the small one in memory")
        assertEquals(big, fetch("big") { error("must come from disk") })
        assertEquals(2, calls)
    }

    @Test fun keysAreKeptApart() = runBlocking<Unit> {
        assertEquals("a", fetch("one") { "a" })
        assertEquals("b", fetch("two") { "b" })
        assertEquals("a", fetch("one") { "c" })
    }

    @Test fun offUntilInstalled() = runBlocking<Unit> {
        ResponseCache.uninstall()
        fetch { "x" }
        fetch { "y" }
        assertEquals(2, calls)
    }
}
