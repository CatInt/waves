package io.waves.ui

import io.waves.currentTimeMillis
import io.waves.data.LibraryRepository
import io.waves.model.HistoryEntry
import io.waves.model.PlaybackProgress
import io.waves.sync.ListeningRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime

data class HistoryDay(val date: LocalDate, val entries: List<HistoryEntry>)

data class HistoryUiState(
    val days: List<HistoryDay> = emptyList(),
    val loaded: Boolean = false,
    /** Listening time this week (since Monday), on every device. */
    val listenedThisWeekMs: Long = 0,
)

/**
 * Listening history from all devices, newest first, grouped by local calendar day. Each episode is listed once, at
 * its latest session; the library keeps every session (sync and show affinity count them). Also this week's listening
 * time, counted afresh every minute so it starts over on Monday: measured where a device has measured it
 * ([LibraryRepository.listening]), else estimated from the history ([estimatedListening]).
 */
class HistoryState(
    library: LibraryRepository,
    scope: CoroutineScope,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
    private val clock: () -> Long = ::currentTimeMillis,
) {
    private val minutes = flow {
        while (true) {
            emit(Unit)
            delay(60_000)
        }
    }

    val state: StateFlow<HistoryUiState> =
        combine(library.history, library.listening, library.allProgress, minutes) { history, listening, progress, _ ->
            val now = clock()
            val all = withEstimates(listening, estimatedListening(history, progress, now, timeZone))
            HistoryUiState(groupByDay(history, timeZone), loaded = true, listenedThisWeekMs = thisWeek(all, now, timeZone))
        }.stateIn(scope, SharingStarted.Eagerly, HistoryUiState())

    companion object {
        fun groupByDay(entries: List<HistoryEntry>, timeZone: TimeZone): List<HistoryDay> =
            entries
                .sortedByDescending { it.playedAtEpochMs }
                .distinctBy { it.episodeId }
                .groupBy { localDate(it.playedAtEpochMs, timeZone) }
                .map { (date, list) -> HistoryDay(date, list) }

        /**
         * Listening estimated from [history], per device and day, for the time before listening was measured. Each session
         * counts until the device's next session started (or [now]), but no more than the episode had left to give: how
         * far it has got ([PlaybackProgress.positionMs], all of it once completed), shared out over its sessions in
         * order. An episode that never got going (a download, say, which is in the history too) counts nothing.
         */
        fun estimatedListening(
            history: List<HistoryEntry>,
            progress: List<PlaybackProgress>,
            now: Long,
            timeZone: TimeZone,
        ): List<ListeningRecord> {
            val left = progress.associate { p -> p.episodeId to if (p.completed) p.durationMs else p.positionMs }.toMutableMap()
            val sessions = history.distinctBy { Triple(it.episodeId, it.playedAtEpochMs, it.deviceId) }.sortedBy { it.playedAtEpochMs }
            val next = sessions.groupBy { it.deviceId }.flatMap { (_, own) ->
                own.zipWithNext { a, b -> a to b.playedAtEpochMs } + (own.last() to now)
            }.toMap()
            val totals = mutableMapOf<Pair<String, String>, Long>()
            for (s in sessions) {
                val remaining = left[s.episodeId] ?: 0L
                val spent = minOf(remaining, (next[s] ?: now) - s.playedAtEpochMs).coerceAtLeast(0L)
                if (spent == 0L) continue
                left[s.episodeId] = remaining - spent
                val key = s.deviceId to localDate(s.playedAtEpochMs, timeZone).toString()
                totals[key] = (totals[key] ?: 0L) + spent
            }
            return totals.map { (key, ms) -> ListeningRecord(key.first, key.second, ms) }
        }

        /** [measured] listening, with [estimated] for the days before each device began measuring. */
        fun withEstimates(measured: List<ListeningRecord>, estimated: List<ListeningRecord>): List<ListeningRecord> {
            val firstMeasured = measured.groupBy { it.deviceId }.mapValues { (_, days) -> days.minOf { it.day } }
            return measured + estimated.filter { e -> firstMeasured[e.deviceId]?.let { e.day < it } ?: true }
        }

        /** Listening on every device since this week's Monday (local), as of [now]. */
        fun thisWeek(listening: List<ListeningRecord>, now: Long, timeZone: TimeZone): Long {
            val today = localDate(now, timeZone)
            val monday = today.minus(DatePeriod(days = today.dayOfWeek.isoDayNumber - 1)).toString()
            return listening.filter { it.day >= monday }.sumOf { it.listenedMs }
        }

        fun localDate(epochMs: Long, timeZone: TimeZone): LocalDate =
            Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(timeZone).date
    }
}
