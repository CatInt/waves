package io.waves.ui.fake

import io.waves.data.SettingsStore
import io.waves.data.UserSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Non-persistent [SettingsStore] for previews, tests and running without the database. */
class InMemorySettingsStore(initial: UserSettings = UserSettings()) : SettingsStore {
    private val _settings = MutableStateFlow(initial)
    override val settings: StateFlow<UserSettings> = _settings.asStateFlow()

    override suspend fun update(transform: (UserSettings) -> UserSettings) = _settings.update(transform)
}
