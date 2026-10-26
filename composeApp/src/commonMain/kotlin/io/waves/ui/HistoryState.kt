package io.waves.ui

import io.waves.data.LibraryRepository
import io.waves.model.HistoryEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

data class HistoryDay(val date: LocalDate, val entries: List<HistoryEntry>)

data class HistoryUiState(
    val days: List<HistoryDay> = emptyList(),
    val loaded: Boolean = false,
)

/**
 * Listening history from all devices, newest first, grouped by local calendar day. Each episode is listed once, at
 * its latest session; the library keeps every session (sync and show affinity count them).
 */
class HistoryState(
    library: LibraryRepository,
    scope: CoroutineScope,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
) {
    val state: StateFlow<HistoryUiState> = library.history
        .map { HistoryUiState(groupByDay(it, timeZone), loaded = true) }
        .stateIn(scope, SharingStarted.Eagerly, HistoryUiState())

    companion object {
        fun groupByDay(entries: List<HistoryEntry>, timeZone: TimeZone): List<HistoryDay> =
            entries
                .sortedByDescending { it.playedAtEpochMs }
                .distinctBy { it.episodeId }
                .groupBy { localDate(it.playedAtEpochMs, timeZone) }
                .map { (date, list) -> HistoryDay(date, list) }

        fun localDate(epochMs: Long, timeZone: TimeZone): LocalDate =
            Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(timeZone).date
    }
}
