package io.waves.data

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.waves.data.db.WavesDatabase
import java.io.File
import java.util.Properties

/** Opens the database at [defaultDatabaseFile] (or [file]), creating/migrating the schema as needed. */
fun createLibraryRepository(file: File = defaultDatabaseFile()): SqlLibraryRepository =
    SqlLibraryRepository(createDesktopDriver(file))

/** `~/Library/Application Support/Waves/waves.db` on macOS, `~/.waves/waves.db` elsewhere. */
fun defaultDatabaseFile(): File {
    val home = System.getProperty("user.home")
    val isMac = System.getProperty("os.name").orEmpty().lowercase().contains("mac")
    val dir = if (isMac) File(home, "Library/Application Support/Waves") else File(home, ".waves")
    return File(dir, "waves.db")
}

/**
 * Opens [file] and brings it to the current [WavesDatabase.Schema] version.
 *
 * Connection settings (sqlite-jdbc applies them to every connection; the driver keeps one per thread):
 * - WAL journal: readers (UI flows) don't block the writer (sync import, feed refresh) and vice versa.
 * - synchronous=NORMAL: the recommended pairing with WAL (durable across app crashes; a power loss can
 *   only lose the last commits, never corrupt the file).
 * - busy_timeout: a second connection waits for a lock instead of failing immediately with SQLITE_BUSY.
 * - foreign_keys: enforced if the schema ever declares any.
 */
internal fun createDesktopDriver(file: File): SqlDriver {
    file.parentFile?.mkdirs()
    val driver = JdbcSqliteDriver(
        url = "jdbc:sqlite:${file.absolutePath}",
        properties = Properties().apply {
            put("journal_mode", "WAL")
            put("synchronous", "NORMAL")
            put("busy_timeout", "5000")
            put("foreign_keys", "true")
        },
    )
    try {
        migrateToCurrent(driver, WavesDatabase.Schema)
    } catch (e: Throwable) {
        driver.close()
        throw e
    }
    return driver
}

/**
 * Creates (user_version 0) or migrates (user_version < schema version) the database, recording the new version
 * in `PRAGMA user_version`. Unlike JdbcSqliteDriver's own schema handling, each step runs in one transaction,
 * so a crash mid-migration leaves the previous version intact and the migration simply reruns next launch.
 * A database from a newer app version (user_version > schema version) is left untouched: migrations are
 * additive, so this older code can still use the tables it knows.
 */
internal fun migrateToCurrent(driver: SqlDriver, schema: SqlSchema<QueryResult.Value<Unit>>) {
    val current = userVersion(driver)
    if (current >= schema.version) return
    // The driver's own transaction API: it pins this thread's connection for the whole block (raw BEGIN/COMMIT
    // statements could run on different pooled connections).
    object : TransacterImpl(driver) {}.transaction {
        // Re-read inside the transaction: another process may have migrated meanwhile.
        val locked = userVersion(driver)
        if (locked < schema.version) {
            if (locked == 0L) schema.create(driver) else schema.migrate(driver, locked, schema.version)
            driver.execute(null, "PRAGMA user_version = ${schema.version}", 0)
        }
    }
}

internal fun userVersion(driver: SqlDriver): Long = driver.executeQuery(
    identifier = null,
    sql = "PRAGMA user_version",
    mapper = { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L) },
    parameters = 0,
).value
