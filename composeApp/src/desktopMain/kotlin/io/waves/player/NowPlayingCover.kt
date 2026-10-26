package io.waves.player

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.isSuccess
import io.waves.ui.PlaybackController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest

/** Encoded artwork of the current episode. */
class CoverArt(val url: String, val bytes: ByteArray)

/**
 * Artwork of what's playing, downloaded once per change: the episode's own cover falling back to the show's, or with
 * [preferShow] the show's cover falling back to the episode's. Null while nothing plays, while loading, or when the
 * download fails.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun currentCoverArt(
    playback: PlaybackController,
    http: HttpClient,
    scope: CoroutineScope,
    preferShow: Boolean = false,
): StateFlow<CoverArt?> =
    combine(playback.state, playback.podcast) { s, p ->
        s.episode?.let { episode ->
            val show = p?.artworkUrl?.takeIf { it.isNotBlank() }
            val own = episode.artworkUrl?.takeIf { it.isNotBlank() }
            if (preferShow) show ?: own else own ?: show
        }
    }
        .distinctUntilChanged()
        .transformLatest { url ->
            emit(null) // don't keep the previous episode's cover while the new one loads
            if (url != null) fetch(http, url)?.let { emit(CoverArt(url, it)) }
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

private suspend fun fetch(http: HttpClient, url: String): ByteArray? = runCatching {
    val response = http.get(url)
    if (response.status.isSuccess()) response.bodyAsBytes() else null
}.onFailure { if (it is CancellationException) throw it }.getOrNull()
