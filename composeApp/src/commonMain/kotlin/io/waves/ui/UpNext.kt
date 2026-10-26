package io.waves.ui

import io.waves.model.Episode
import io.waves.model.EpisodeId
import io.waves.model.HistoryEntry
import io.waves.model.PlaybackProgress
import io.waves.model.Podcast
import kotlin.math.ln
import kotlin.math.pow

/** Why an episode is in "Up next"; shown as a short label on the row. */
enum class UpNextReason { ContinueListening, New, FrequentShow, Downloaded, Latest }

data class UpNextItem(val podcast: Podcast, val row: EpisodeRow, val reason: UpNextReason)

/**
 * Personalised ordering for the Library: the shelf of subscribed shows and the "Up next" episode list.
 * Uses only local data: subscriptions, stored episodes, playback progress and listening history.
 *
 * - Show affinity: listening sessions (and, at half weight, completed episodes) per show, each decaying with a
 *   30-day half-life, then log-damped so a binge doesn't drown everything else.
 * - Episode score: freshness (14-day half-life on the publish date) times (1 + affinity).
 * - Unfinished episodes get a boost that fades with a 7-day half-life since they were last played.
 * - Completed episodes are left out. Each show contributes its unfinished episodes plus at most
 *   `perShowCandidates` newest unplayed ones, and every further pick from the same show is halved, so one
 *   prolific show can't fill the list.
 * - Downloaded episodes (any download state) are always listed, after the ranked ones if `limit` cut them off:
 *   Home is where downloads are found.
 */
object UpNextRanking {
    private const val DAY = 24 * 60 * 60 * 1000L
    private const val AFFINITY_HALF_LIFE_DAYS = 30.0
    private const val FRESHNESS_HALF_LIFE_DAYS = 14.0
    private const val RESUME_HALF_LIFE_DAYS = 7.0
    private const val NEW_WITHIN_MS = 3 * DAY
    private const val SAME_SHOW_PENALTY = 0.5
    private const val UNDATED_FRESHNESS = 0.1
    private const val COMPLETION_WEIGHT = 0.5
    private const val RESUME_BASE = 1.5
    private const val RESUME_RECENT = 2.0
    /** About three recent sessions or more. */
    private val FREQUENT_AFFINITY = ln(4.0)

    fun rank(
        podcasts: List<Podcast>,
        episodesByFeed: Map<String, List<Episode>>,
        progress: Map<EpisodeId, PlaybackProgress>,
        history: List<HistoryEntry>,
        now: Long,
        limit: Int = 50,
        perShowCandidates: Int = 10,
        downloaded: Set<EpisodeId> = emptySet(),
    ): List<UpNextItem> {
        val affinity = showAffinity(history, progress.values, now)
        val remaining = podcasts.sortedBy { it.title.lowercase() }.flatMap { podcast ->
            val a = affinity[podcast.feedUrl] ?: 0.0
            val unplayed = episodesByFeed[podcast.feedUrl].orEmpty()
                .filter { progress[it.id]?.completed != true }
                .sortedByDescending { it.publishedAtEpochMs ?: Long.MIN_VALUE }
            val (started, fresh) = unplayed.partition { isStarted(progress[it.id]) }
            val kept = fresh.filterIndexed { i, e -> i < perShowCandidates || e.id in downloaded }
            (started + kept).map { episode ->
                val row = EpisodeRow(episode, progress[episode.id])
                Candidate(UpNextItem(podcast, row, reason(row, a, now, episode.id in downloaded)), score(row, a, now))
            }
        }.toMutableList()

        val picked = mutableListOf<UpNextItem>()
        val pickedPerShow = mutableMapOf<String, Int>()
        while (picked.size < limit && remaining.isNotEmpty()) {
            var bestIndex = 0
            var bestScore = Double.NEGATIVE_INFINITY
            remaining.forEachIndexed { i, c ->
                val s = c.score * SAME_SHOW_PENALTY.pow(pickedPerShow[c.item.podcast.feedUrl] ?: 0)
                if (s > bestScore) { bestScore = s; bestIndex = i }
            }
            val best = remaining.removeAt(bestIndex).item
            picked += best
            pickedPerShow[best.podcast.feedUrl] = (pickedPerShow[best.podcast.feedUrl] ?: 0) + 1
        }
        picked += remaining.filter { it.item.row.episode.id in downloaded }.sortedByDescending { it.score }.map { it.item }
        return picked
    }

    /** Shows the user engages with, and those with fresh unplayed episodes, first; ties alphabetical. */
    fun orderShelf(
        podcasts: List<Podcast>,
        episodesByFeed: Map<String, List<Episode>>,
        progress: Map<EpisodeId, PlaybackProgress>,
        history: List<HistoryEntry>,
        now: Long,
    ): List<Podcast> {
        val affinity = showAffinity(history, progress.values, now)
        val key = podcasts.associate { p ->
            val newest = episodesByFeed[p.feedUrl].orEmpty()
                .filter { progress[it.id]?.completed != true }
                .maxOfOrNull { it.publishedAtEpochMs ?: Long.MIN_VALUE }
            val freshness = newest?.takeIf { it != Long.MIN_VALUE }?.let { decay(now - it, FRESHNESS_HALF_LIFE_DAYS) } ?: 0.0
            p.feedUrl to (affinity[p.feedUrl] ?: 0.0) + freshness
        }
        return podcasts.sortedWith(
            compareByDescending<Podcast> { key[it.feedUrl] ?: 0.0 }.thenBy { it.title.lowercase() },
        )
    }

    /** Log-damped, time-decayed engagement per feed URL. */
    fun showAffinity(history: List<HistoryEntry>, progress: Collection<PlaybackProgress>, now: Long): Map<String, Double> {
        val raw = mutableMapOf<String, Double>()
        fun add(feedUrl: String, weight: Double) { raw[feedUrl] = (raw[feedUrl] ?: 0.0) + weight }
        history.forEach { add(it.episodeId.feedUrl, decay(now - it.playedAtEpochMs, AFFINITY_HALF_LIFE_DAYS)) }
        progress.filter { it.completed }.forEach {
            add(it.episodeId.feedUrl, COMPLETION_WEIGHT * decay(now - it.updatedAtEpochMs, AFFINITY_HALF_LIFE_DAYS))
        }
        return raw.mapValues { ln(1 + it.value) }
    }

    private class Candidate(val item: UpNextItem, val score: Double)

    private fun score(row: EpisodeRow, affinity: Double, now: Long): Double {
        val published = row.episode.publishedAtEpochMs
        val freshness = if (published != null) decay(now - published, FRESHNESS_HALF_LIFE_DAYS) else UNDATED_FRESHNESS
        val base = freshness * (1 + affinity)
        val p = row.progress
        return if (p != null && isStarted(p)) {
            base + RESUME_BASE + RESUME_RECENT * decay(now - p.updatedAtEpochMs, RESUME_HALF_LIFE_DAYS)
        } else {
            base
        }
    }

    private fun reason(row: EpisodeRow, affinity: Double, now: Long, downloaded: Boolean): UpNextReason {
        val published = row.episode.publishedAtEpochMs
        return when {
            isStarted(row.progress) -> UpNextReason.ContinueListening
            published != null && now - published <= NEW_WITHIN_MS -> UpNextReason.New
            affinity >= FREQUENT_AFFINITY -> UpNextReason.FrequentShow
            downloaded -> UpNextReason.Downloaded
            else -> UpNextReason.Latest
        }
    }

    private fun isStarted(p: PlaybackProgress?) = p != null && !p.completed && p.positionMs > 0

    private fun decay(ageMs: Long, halfLifeDays: Double): Double =
        0.5.pow(ageMs.coerceAtLeast(0) / (halfLifeDays * DAY))
}
