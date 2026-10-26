package io.waves.data

import app.cash.sqldelight.db.SqlDriver
import io.waves.data.db.WavesDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.coroutines.CoroutineContext

/**
 * [SettingsStore] persisting [UserSettings] as one JSON value in the `setting` table (key [KEY]).
 * Share the driver of [SqlLibraryRepository.driver]; the schema must already exist. The current value is read
 * synchronously at construction so [settings] is correct from the start. Unknown or malformed JSON falls back to
 * defaults field by field (unknown keys are ignored, so older builds can read newer blobs).
 */
class SqlSettingsStore(
    driver: SqlDriver,
    private val ioContext: CoroutineContext = Dispatchers.IO,
) : SettingsStore {
    private val queries = WavesDatabase(driver).settingQueries
    private val mutex = Mutex()
    private val _settings = MutableStateFlow(decode(queries.get(KEY).executeAsOneOrNull()))
    override val settings: StateFlow<UserSettings> = _settings.asStateFlow()

    override suspend fun update(transform: (UserSettings) -> UserSettings) {
        mutex.withLock {
            val next = transform(_settings.value)
            if (next == _settings.value) return
            withContext(ioContext) { queries.put(KEY, json.encodeToString(UserSettings.serializer(), next)) }
            _settings.value = next
        }
    }

    companion object {
        const val KEY = "userSettings"

        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

        internal fun decode(value: String?): UserSettings =
            value?.let { runCatching { json.decodeFromString(UserSettings.serializer(), it) }.getOrNull() } ?: UserSettings()
    }
}
