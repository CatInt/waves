package io.waves.sync

import io.waves.model.Episode
import io.waves.model.HistoryEntry
import io.waves.model.PlaybackProgress
import io.waves.model.Podcast

/**
 * Conflict-free merge of two [SyncState] snapshots.
 *
 * Properties (for any inputs a, b, c):
 *  - commutative: merge(a, b) == merge(b, a)
 *  - associative: merge(merge(a, b), c) == merge(a, merge(b, c))
 *  - idempotent:  merge(m, m) == m for any merged m, and merge(merge(a, b), b) == merge(a, b)
 *
 * Every per-key choice uses a total order over all record fields, so ties never depend on argument order.
 * Output lists are sorted canonically so equal content means equal [SyncState].
 */
object SyncMerge {
    const val HISTORY_CAP = 2000

    /** Archived episodes kept, newest first: years of a daily stream, at well under a megabyte gzipped. */
    const val ARCHIVE_CAP = 2000

    fun merge(a: SyncState, b: SyncState): SyncState = SyncState(
        version = maxOf(a.version, b.version),
        subscriptions = mergeSubscriptions(a.subscriptions + b.subscriptions),
        progress = mergeProgress(a.progress + b.progress),
        history = mergeHistory(a.history + b.history),
        archive = mergeArchive(a.archive + b.archive),
    )

    /** Canonical form of a single state (dedups and sorts); merge(s, s) == normalize(s). */
    fun normalize(s: SyncState): SyncState = merge(s, SyncState(version = s.version))

    /**
     * True if [incoming] should replace [stored] for the same feedUrl, i.e. it wins (or ties) under the same
     * order [merge] uses. Lets persistent stores merge record-by-record consistently with [merge].
     */
    fun supersedes(incoming: SubscriptionRecord, stored: SubscriptionRecord): Boolean =
        subscriptionOrder.compare(incoming, stored) >= 0

    /** True if [incoming] should replace [stored] for the same episode; consistent with [merge]. */
    fun supersedes(incoming: PlaybackProgress, stored: PlaybackProgress): Boolean =
        progressOrder.compare(incoming, stored) >= 0

    /**
     * Metadata a store should keep when it applies an incoming subscription record for a podcast it already
     * has: the [local] values (each device refreshes feeds itself, so sync must not fight over them), with
     * only null/blank local fields filled from [incoming].
     */
    fun keepLocalMetadata(local: Podcast, incoming: Podcast): Podcast = local.copy(
        title = local.title.ifBlank { incoming.title },
        author = local.author.orIfBlank(incoming.author),
        description = local.description.orIfBlank(incoming.description),
        artworkUrl = local.artworkUrl.orIfBlank(incoming.artworkUrl),
        appleId = local.appleId ?: incoming.appleId,
    )

    private fun String?.orIfBlank(other: String?): String? = if (isNullOrBlank()) other ?: this else this

    /**
     * LWW per feedUrl. Ties on timestamp: subscribed beats unsubscribed (avoid losing a subscription).
     * Podcast metadata is compared only as the final deterministic tie-break so merge stays commutative;
     * stores don't adopt it for podcasts they already know (see [keepLocalMetadata]).
     */
    private val subscriptionOrder: Comparator<SubscriptionRecord> =
        compareBy<SubscriptionRecord> { it.updatedAtEpochMs }
            .thenBy { it.subscribed }
            .thenBy { it.podcast.toString() }

    /** LWW per episode. Ties on timestamp: completed wins, then the larger position, then larger duration. */
    private val progressOrder: Comparator<PlaybackProgress> =
        compareBy<PlaybackProgress> { it.updatedAtEpochMs }
            .thenBy { it.completed }
            .thenBy { it.positionMs }
            .thenBy { it.durationMs }

    /** Total order over history entries; "greater" = more recent. */
    private val historyOrder: Comparator<HistoryEntry> =
        compareBy<HistoryEntry> { it.playedAtEpochMs }
            .thenBy { it.deviceId }
            .thenBy { it.episodeId.toString() }
            .thenBy { it.episodeTitle }
            .thenBy { it.podcastTitle }

    /**
     * Copies of one archived episode differ only by when each device fetched it. Prefer one with a length (YouTube
     * gives it separately, and a fetch can miss it); the rest is a deterministic tie-break.
     */
    private val archiveOrder: Comparator<Episode> =
        compareBy<Episode> { it.durationSeconds != null }
            .thenBy { it.publishedAtEpochMs ?: Long.MIN_VALUE }
            .thenBy { it.toString() }

    private fun mergeArchive(all: List<Episode>): List<Episode> =
        all.groupBy { it.id }
            .map { (_, copies) -> copies.maxWith(archiveOrder) }
            .sortedWith(
                compareByDescending<Episode> { it.publishedAtEpochMs ?: Long.MIN_VALUE }
                    .thenBy { it.podcastFeedUrl }
                    .thenBy { it.guid },
            )
            .take(ARCHIVE_CAP)

    private fun mergeSubscriptions(all: List<SubscriptionRecord>): List<SubscriptionRecord> =
        all.groupBy { it.podcast.feedUrl }
            .map { (_, records) -> records.maxWith(subscriptionOrder) }
            .sortedBy { it.podcast.feedUrl }

    private fun mergeProgress(all: List<PlaybackProgress>): List<PlaybackProgress> =
        all.groupBy { it.episodeId }
            .map { (_, records) -> records.maxWith(progressOrder) }
            .sortedWith(compareBy({ it.episodeId.feedUrl }, { it.episodeId.guid }))

    private fun mergeHistory(all: List<HistoryEntry>): List<HistoryEntry> =
        all.groupBy { Triple(it.episodeId, it.playedAtEpochMs, it.deviceId) }
            .map { (_, entries) -> entries.maxWith(historyOrder) }
            .sortedWith(historyOrder.reversed())
            .take(HISTORY_CAP)
}
