package io.waves.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ControllerConnectionTest {
    private class FakeController(val name: String) { var connected = true }

    private var connectCalls = 0
    private val detached = mutableListOf<FakeController>()
    private val ran = mutableListOf<String>()
    private val conn = ControllerConnection<FakeController>(
        startConnect = { connectCalls++ },
        isConnected = { it.connected },
        onDetached = { detached += it },
    )

    private fun cmd(label: String): (FakeController) -> Unit = { ran += "$label@${it.name}" }

    @Test
    fun queuesUntilConnectedThenDrainsInOrder() {
        conn.connect()
        conn.run(cmd("a"))
        conn.run(cmd("b"))
        assertEquals(1, connectCalls)
        assertTrue(ran.isEmpty())
        assertTrue(conn.onConnected(FakeController("c1")))
        assertEquals(listOf("a@c1", "b@c1"), ran)
        conn.run(cmd("c"))
        assertEquals("c@c1", ran.last())
        assertEquals(1, connectCalls)
    }

    @Test
    fun reconnectsLazilyOnNextCommandAfterDisconnect() {
        conn.connect()
        val c1 = FakeController("c1")
        conn.onConnected(c1)
        assertTrue(conn.onDisconnected(c1))
        assertEquals(listOf(c1), detached)
        assertNull(conn.current)
        assertEquals(1, connectCalls, "no eager reconnect")

        conn.run(cmd("play"))
        assertEquals(2, connectCalls)
        conn.run(cmd("seek"))
        assertEquals(2, connectCalls, "one attempt at a time")
        conn.onConnected(FakeController("c2"))
        assertEquals(listOf("play@c2", "seek@c2"), ran)
    }

    @Test
    fun silentlyDeadControllerIsDetachedAndReplaced() {
        conn.connect()
        val c1 = FakeController("c1")
        conn.onConnected(c1)
        c1.connected = false
        conn.run(cmd("pause"))
        assertEquals(listOf(c1), detached)
        assertEquals(2, connectCalls)
        assertTrue(ran.isEmpty())
        conn.onConnected(FakeController("c2"))
        assertEquals(listOf("pause@c2"), ran)
    }

    @Test
    fun staleDisconnectIsIgnored() {
        conn.connect()
        val c1 = FakeController("c1")
        conn.onConnected(c1)
        assertTrue(conn.onDisconnected(c1))
        conn.run(cmd("x"))
        val c2 = FakeController("c2")
        conn.onConnected(c2)
        assertFalse(conn.onDisconnected(c1))
        assertSame(c2, conn.current)
    }

    @Test
    fun failureDropsQueuedCommandsAndAllowsRetry() {
        conn.connect()
        conn.run(cmd("a"))
        assertEquals(1, conn.onFailed())
        conn.run(cmd("b"))
        assertEquals(2, connectCalls)
        conn.onConnected(FakeController("c2"))
        assertEquals(listOf("b@c2"), ran)
    }

    @Test
    fun releaseStopsEverything() {
        conn.connect()
        conn.run(cmd("a"))
        conn.release()
        assertFalse(conn.onConnected(FakeController("late")))
        conn.run(cmd("b"))
        assertTrue(ran.isEmpty())
        assertEquals(1, connectCalls)
    }
}
