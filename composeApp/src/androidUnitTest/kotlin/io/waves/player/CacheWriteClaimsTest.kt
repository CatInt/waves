package io.waves.player

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CacheWriteClaimsTest {
    @Test
    fun playerWritesUntilAFillStartsThenOnlyReads() {
        val claims = CacheWriteClaims()
        assertTrue(claims.claimWrite("ep"))
        claims.beginFill("ep")
        assertTrue(claims.isFilling("ep"))
        assertFalse(claims.claimWrite("ep"))
        assertTrue(claims.claimWrite("other"))
        claims.endFill("ep")
        assertTrue(claims.claimWrite("ep"))
    }

    @Test
    fun fillWaitsForThePlayerToHandOver() = runTest {
        val claims = CacheWriteClaims()
        claims.claimWrite("ep")
        claims.beginFill("ep")
        val handedOver = async { claims.awaitNoWriters("ep", timeoutMs = 10_000) }
        delay(1_000)
        assertTrue(claims.hasWriter("ep"))
        claims.releaseWrite("ep")
        assertTrue(handedOver.await())
    }

    @Test
    fun fillGivesUpWhenThePlayerKeepsWriting() = runTest {
        val claims = CacheWriteClaims()
        claims.claimWrite("ep")
        claims.beginFill("ep")
        assertFalse(claims.awaitNoWriters("ep", timeoutMs = 5_000))
    }

    @Test
    fun everyClaimMustBeReleased() {
        val claims = CacheWriteClaims()
        claims.claimWrite("ep")
        claims.claimWrite("ep")
        claims.releaseWrite("ep")
        assertTrue(claims.hasWriter("ep"))
        claims.releaseWrite("ep")
        assertFalse(claims.hasWriter("ep"))
        claims.releaseWrite("ep")
        assertFalse(claims.hasWriter("ep"))
    }
}
