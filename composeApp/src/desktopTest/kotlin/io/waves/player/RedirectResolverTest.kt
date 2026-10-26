package io.waves.player

import io.waves.player.RedirectResolver.Hop
import kotlin.test.Test
import kotlin.test.assertEquals

class RedirectResolverTest {
    @Test
    fun followsChainIncludingRelativeAndCrossProtocol() {
        val hops = mapOf(
            "http://prefix.example.com/redirect.mp3/cdn.example.com/a.mp3" to Hop(302, "https://tracker.example.net/x?u=1"),
            "https://tracker.example.net/x?u=1" to Hop(301, "/final/a.mp3"),
            "https://tracker.example.net/final/a.mp3" to Hop(206, null),
        )
        val resolved = RedirectResolver.resolve("http://prefix.example.com/redirect.mp3/cdn.example.com/a.mp3") { hops.getValue(it) }
        assertEquals("https://tracker.example.net/final/a.mp3", resolved)
    }

    @Test
    fun stopsOnErrorOrMissingLocation() {
        assertEquals("https://a/x", RedirectResolver.resolve("https://a/x") { throw java.io.IOException("offline") })
        assertEquals("https://a/x", RedirectResolver.resolve("https://a/x") { Hop(302, null) })
        assertEquals("https://a/x", RedirectResolver.resolve("https://a/x") { Hop(403, null) })
    }

    @Test
    fun boundedHops() {
        var n = 0
        RedirectResolver.resolve("https://a/0") { Hop(302, "https://a/${++n}") }
        assertEquals(RedirectResolver.MAX_HOPS, n)
    }

    @Test
    fun nextUrlHandlesSpaces() {
        assertEquals("https://h/a%20b.mp3", RedirectResolver.nextUrl("https://h/x", "/a b.mp3"))
    }
}
