package io.waves.ui

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import io.waves.data.ArtworkColorStore
import io.waves.data.InMemoryArtworkColorStore
import io.waves.model.Podcast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * The corner colours of followed YouTube playlists. A playlist gets one of [PlaylistPalette]'s 16 when it's followed
 * and gives it back when it's unfollowed, from this device or another (it follows [LibraryRepository.subscriptions]);
 * one not followed has a light grey corner. A new one takes a colour none of its channel's other followed playlists has,
 * then the one fewest followed playlists have, so up to 16 they all differ.
 *
 * Kept in [store] (local to the device, not synced) under `playlist-corner:<playlist ID>`, holding the palette index
 * in place of a colour.
 */
class PlaylistColors(private val store: ArtworkColorStore) {
    /** Palette index by playlist ID; read while drawing, so a corner recolours as soon as it changes. */
    private val slots = mutableStateMapOf<String, Int>().apply {
        store.all().forEach { (key, slot) -> if (key.startsWith(KEY)) put(key.removePrefix(KEY), slot) }
    }

    fun color(playlistId: String): Color =
        slots[playlistId]?.let { PlaylistPalette.getOrNull(it) } ?: UnfollowedPlaylist

    /** Keeps colours in step with [subscriptions] for as long as [scope] lasts. */
    fun start(scope: CoroutineScope, subscriptions: Flow<List<Podcast>>) {
        scope.launch { subscriptions.collect { update(it) } }
    }

    /** Gives followed playlists in [subscriptions] a colour, and takes it back from those no longer among them. */
    suspend fun update(subscriptions: List<Podcast>) {
        val followed = subscriptions.mapNotNull { p -> p.playlistId?.let { it to p } }.toMap()
        for (id in slots.keys - followed.keys) {
            slots.remove(id)
            store.remove(KEY + id)
        }
        for ((id, podcast) in followed) {
            // An index from a larger palette than today's is as good as none.
            if (slots[id] in PlaylistPalette.indices) continue
            val slot = pick(podcast, followed)
            slots[id] = slot
            store.put(KEY + id, slot)
        }
    }

    /**
     * A colour for [podcast]: one its channel's other followed playlists don't have (a playlist names its channel as
     * its author), then the one fewest followed playlists have, then the first.
     */
    private fun pick(podcast: Podcast, followed: Map<String, Podcast>): Int {
        val taken = slots.filterKeys { it in followed }
        val sameChannel = taken.filterKeys { followed[it]?.author == podcast.author }.values.toSet()
        val uses = taken.values.groupingBy { it }.eachCount()
        return PlaylistPalette.indices.minWith(
            compareBy<Int> { it in sameChannel }.thenBy { uses[it] ?: 0 }.thenBy { it },
        )
    }

    private companion object {
        const val KEY = "playlist-corner:"
    }
}

/** The app's [PlaylistColors]; a fresh, empty one where none is provided (previews, tests). */
internal val LocalPlaylistColors = staticCompositionLocalOf { PlaylistColors(InMemoryArtworkColorStore()) }

/** A playlist nobody follows: its corner still marks it a playlist, without a colour of its own. */
private val UnfollowedPlaylist = Color(0xEBC8C8C8)

/**
 * The 16 playlist corner colours, chosen to look as different from each other as possible. Picked in OKLab (where
 * distance follows how different colours look, unlike hue) from every vivid colour of middle lightness clear of
 * [LiveRed]'s hue, the 16 that sit farthest apart, from each other and from the red: no two closer than 0.177 in
 * OKLab. All vivid, so none passes for [UnfollowedPlaylist]'s grey. The corner carries no words, so they needn't be dark. In the order a channel's
 * playlists take them: roughly each next one the farthest from those before it, so a channel's first playlists differ
 * most, with a few swapped by eye and a yellow (the farthest from the rest) in place of a blue.
 */
private val PlaylistPalette = listOf(
    0xFF3595EA, 0xFF8AB560, 0xFFDFAAFF, 0xFFFF9500, 0xFFEA6095, 0xFFFFEB00, 0xFF208A00, 0xFF8A40FF,
    0xFF55D4F4, 0xFFAA6A00, 0xFFAA2060, 0xFF0B559F, 0xFF6A0095, 0xFFFF00FF, 0xFF00EA00, 0xFF20552A,
).map { Color(it).copy(alpha = 0.92f) }
