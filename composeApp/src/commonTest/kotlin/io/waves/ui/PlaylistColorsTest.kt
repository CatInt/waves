package io.waves.ui

import io.waves.data.InMemoryArtworkColorStore
import io.waves.model.Podcast
import io.waves.youtube.YoutubeUrls
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PlaylistColorsTest {
    private val channel = "UCexampleChannel00000000"

    private fun playlist(n: Int, author: String = "Example") = Podcast(
        YoutubeUrls.playlistFeedUrl("PLexamplePlaylist00000000000000${n.toString().padStart(3, '0')}"), "List $n",
        author = author,
    )

    private val Podcast.id get() = playlistId!!

    @Test
    fun onlyFollowedPlaylistsHaveAColourAndUnfollowingGivesItBack() = runTest {
        val store = InMemoryArtworkColorStore()
        val colors = PlaylistColors(store)
        val a = playlist(1)
        val b = playlist(2)
        val grey = colors.color(a.id)
        assertEquals(grey, colors.color(b.id))

        colors.update(listOf(Podcast(YoutubeUrls.feedUrl(channel), "Example"), a))
        assertNotEquals(grey, colors.color(a.id))
        assertEquals(grey, colors.color(b.id))

        colors.update(emptyList())
        assertEquals(grey, colors.color(a.id))
        assertEquals(emptyMap(), store.all())
    }

    @Test
    fun aChannelsFollowedPlaylistsDifferAndKeepTheirColours() = runTest {
        val store = InMemoryArtworkColorStore()
        val colors = PlaylistColors(store)
        val lists = (0 until 16).map { playlist(it) }
        colors.update(lists)
        val given = lists.map { colors.color(it.id) }
        assertEquals(16, given.toSet().size)

        // Unfollowing one frees its colour; the rest keep theirs, and a newcomer takes the freed one.
        val newcomer = playlist(99)
        colors.update(lists.drop(1) + newcomer)
        assertEquals(given.drop(1), lists.drop(1).map { colors.color(it.id) })
        assertEquals(given[0], colors.color(newcomer.id))

        // Colours outlive the app: a new holder reads them back.
        assertEquals(colors.color(newcomer.id), PlaylistColors(store).color(newcomer.id))
    }

    @Test
    fun anotherChannelsPlaylistMayShareAColourWhenAllAreTaken() = runTest {
        val colors = PlaylistColors(InMemoryArtworkColorStore())
        val mine = (0 until 16).map { playlist(it) }
        val theirs = playlist(50, author = "Other")
        colors.update(mine + theirs)
        assertEquals(16, mine.map { colors.color(it.id) }.toSet().size)
        assertEquals(true, colors.color(theirs.id) in mine.map { colors.color(it.id) })
    }
}
