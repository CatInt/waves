package io.waves.data

import app.cash.sqldelight.Query
import app.cash.sqldelight.driver.jdbc.JdbcDriver
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties

/**
 * SQLDelight's JDBC SQLite driver for a database file, but with transactions that take the write lock as they begin
 * (`BEGIN IMMEDIATE`), so another writer waits its turn (busy_timeout) instead of failing.
 *
 * SQLDelight's own driver begins with a plain `BEGIN TRANSACTION`, a deferred one: it starts as a reader, and a
 * read-then-write transaction must upgrade to write, which SQLite refuses at once (SQLITE_BUSY / SQLITE_BUSY_SNAPSHOT)
 * when another connection wrote meanwhile. The driver keeps one connection per thread, and not every writer shares the
 * library repository's lock (the download manager, playback cache, settings and feed log write the same file).
 */
internal class ImmediateSqliteDriver(private val url: String, private val properties: Properties) : JdbcDriver() {
    private val connections = HashMap<Long, Connection>()
    private val listeners = LinkedHashMap<String, MutableSet<Query.Listener>>()

    override fun getConnection(): Connection = synchronized(connections) {
        connections.getOrPut(Thread.currentThread().id) { DriverManager.getConnection(url, properties) }
    }

    /** Kept open for the thread's next statement, as SQLDelight's own driver does for a file. */
    override fun closeConnection(connection: Connection) = Unit

    override fun close() {
        synchronized(connections) {
            connections.values.forEach { runCatching { it.close() } }
            connections.clear()
        }
    }

    override fun Connection.beginTransaction() {
        prepareStatement("BEGIN IMMEDIATE TRANSACTION").use { it.execute() }
    }

    override fun Connection.endTransaction() {
        prepareStatement("END TRANSACTION").use { it.execute() }
    }

    override fun Connection.rollbackTransaction() {
        prepareStatement("ROLLBACK TRANSACTION").use { it.execute() }
    }

    override fun addListener(vararg queryKeys: String, listener: Query.Listener) {
        synchronized(listeners) { queryKeys.forEach { listeners.getOrPut(it) { LinkedHashSet() }.add(listener) } }
    }

    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) {
        synchronized(listeners) { queryKeys.forEach { listeners[it]?.remove(listener) } }
    }

    override fun notifyListeners(vararg queryKeys: String) {
        val toNotify = LinkedHashSet<Query.Listener>()
        synchronized(listeners) { queryKeys.forEach { listeners[it]?.let(toNotify::addAll) } }
        toNotify.forEach(Query.Listener::queryResultsChanged)
    }
}
