package io.waves.player

/**
 * Connection lifecycle for a remote controller ([C] is a MediaController in production), kept free of Android
 * types so it can be unit tested. Not thread-safe: confine all calls to one thread (the main looper).
 *
 * - Commands issued while not connected are queued and run, in order, once a connection is established.
 * - A lost connection (service crash, system kill) is never re-established eagerly; the next command
 *   triggers a new attempt ([startConnect]), so an idle app doesn't restart the playback service.
 * - [onDetached] is called whenever a controller is dropped, so the owner can unregister listeners and
 *   release it.
 */
internal class ControllerConnection<C : Any>(
    private val startConnect: () -> Unit,
    private val isConnected: (C) -> Boolean,
    private val onDetached: (C) -> Unit,
) {
    private val pending = ArrayDeque<(C) -> Unit>()
    private var connecting = false
    private var released = false

    /** The live controller, or null while disconnected/connecting. */
    var current: C? = null
        private set

    /** Starts a connection attempt unless one is live or already in progress. */
    fun connect() {
        if (released || connecting || current != null) return
        connecting = true
        startConnect()
    }

    /** Runs [block] now if connected, otherwise queues it and (re)connects. */
    fun run(block: (C) -> Unit) {
        if (released) return
        val c = current
        if (c != null && !isConnected(c)) {
            // Disconnected without (yet) a callback: drop it and reconnect.
            detach(c)
        } else if (c != null) {
            block(c)
            return
        }
        pending.addLast(block)
        connect()
    }

    /** Reports a successful attempt. Returns false if the controller isn't wanted (released): release it. */
    fun onConnected(controller: C): Boolean {
        connecting = false
        if (released) return false
        current = controller
        while (pending.isNotEmpty() && current === controller) pending.removeFirst().invoke(controller)
        return true
    }

    /** Reports a failed attempt; queued commands are dropped. Returns how many were dropped. */
    fun onFailed(): Int {
        connecting = false
        val dropped = pending.size
        pending.clear()
        return dropped
    }

    /** Reports that [controller] disconnected. Returns false if it wasn't the current one (stale callback). */
    fun onDisconnected(controller: C): Boolean {
        if (current !== controller) return false
        detach(controller)
        return true
    }

    fun release() {
        if (released) return
        released = true
        pending.clear()
        current?.let(::detach)
    }

    private fun detach(controller: C) {
        current = null
        onDetached(controller)
    }
}
