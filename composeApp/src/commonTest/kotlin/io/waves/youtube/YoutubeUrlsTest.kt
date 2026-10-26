package io.waves.youtube

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YoutubeUrlsTest {
    private val channel = "UCexampleChannel00000000"

    @Test
    fun channelHandleAndFeedUrls() {
        assertEquals(YoutubeTarget.Vanity("https://www.youtube.com/@examplechannel"), YoutubeUrls.parse("https://www.youtube.com/@examplechannel/videos?si=abc"))
        assertEquals(YoutubeTarget.Vanity("https://www.youtube.com/@examplechannel"), YoutubeUrls.parse("youtube.com/@examplechannel"))
        assertEquals(YoutubeTarget.Vanity("https://www.youtube.com/c/SomeName"), YoutubeUrls.parse("https://m.youtube.com/c/SomeName/featured"))
        assertEquals(YoutubeTarget.Vanity("https://www.youtube.com/user/oldname"), YoutubeUrls.parse("https://www.youtube.com/user/oldname"))
        assertEquals(YoutubeTarget.Vanity("https://music.youtube.com/@name"), YoutubeUrls.parse("https://music.youtube.com/@name"))
        // oEmbed's author_url percent-encodes non-ASCII handles as UTF-8.
        assertEquals(YoutubeTarget.Vanity("https://www.youtube.com/@café"), YoutubeUrls.parse("https://www.youtube.com/@caf%C3%A9"))
        assertEquals(YoutubeTarget.Video("_videoId001"), YoutubeUrls.parse("https://youtu.be/_videoId001?si=shareToken"))
        assertEquals(YoutubeTarget.Channel(channel), YoutubeUrls.parse("https://www.youtube.com/channel/$channel"))
        assertEquals(YoutubeTarget.Channel(channel), YoutubeUrls.parse("https://www.youtube.com/feeds/videos.xml?channel_id=$channel"))
        assertEquals(channel, YoutubeUrls.channelIdOfFeed("http://www.youtube.com/feeds/videos.xml?channel_id=$channel&hl=en"))
        assertEquals(YoutubeUrls.feedUrl(channel), "https://www.youtube.com/feeds/videos.xml?channel_id=$channel")
        assertEquals(
            "https://www.youtube.com/feeds/videos.xml?playlist_id=UULF" + channel.removePrefix("UC"),
            YoutubeUrls.uploadsFeedUrl(channel),
        )
    }

    @Test
    fun videoLinks() {
        assertEquals(YoutubeTarget.Video("firstVideo1"), YoutubeUrls.parse("https://www.youtube.com/watch?v=firstVideo1&t=12"))
        assertEquals(YoutubeTarget.Video("firstVideo1"), YoutubeUrls.parse("https://youtu.be/firstVideo1"))
        assertEquals(YoutubeTarget.Video("abcdefghijk"), YoutubeUrls.parse("https://www.youtube.com/shorts/abcdefghijk"))
        assertEquals(YoutubeTarget.Video("abcdefghijk"), YoutubeUrls.parse("https://www.youtube.com/embed/abcdefghijk"))
        assertEquals(YoutubeTarget.Video("abcdefghijk"), YoutubeUrls.parse("https://www.youtube.com/live/abcdefghijk"))
        assertEquals("abcdefghijk", YoutubeUrls.videoId("https://www.youtube.com/watch?v=abcdefghijk"))
        assertEquals("https://www.youtube.com/watch?v=abcdefghijk", YoutubeUrls.watchUrl("abcdefghijk"))
    }

    @Test
    fun playlistLinks() {
        val list = "PLexamplePlaylist000000000000000000"
        val playlist = YoutubeTarget.Playlist(list)
        assertEquals(playlist, YoutubeUrls.parse("https://www.youtube.com/playlist?list=$list&si=share"))
        assertEquals(playlist, YoutubeUrls.parse("https://music.youtube.com/playlist?list=$list"))
        // A video opened from the playlist shares the playlist, but still plays as that video.
        assertEquals(playlist, YoutubeUrls.parse("https://www.youtube.com/watch?v=firstVideo1&list=$list&index=3"))
        assertEquals(playlist, YoutubeUrls.parse("https://youtu.be/firstVideo1?list=$list"))
        assertEquals("firstVideo1", YoutubeUrls.videoId("https://www.youtube.com/watch?v=firstVideo1&list=$list"))
        assertEquals("firstVideo1", YoutubeUrls.videoId("https://youtu.be/firstVideo1?list=$list"))
        assertEquals(playlist, YoutubeUrls.parse(YoutubeUrls.playlistFeedUrl(list)))
        assertEquals(list, YoutubeUrls.playlistIdOfFeed("http://www.youtube.com/feeds/videos.xml?playlist_id=$list"))
        assertNull(YoutubeUrls.playlistIdOfFeed(YoutubeUrls.feedUrl(channel)))
        assertNull(YoutubeUrls.channelIdOfFeed(YoutubeUrls.playlistFeedUrl(list)))
        // A channel's uploads list is the channel itself.
        val uploadsTail = channel.removePrefix("UC")
        assertEquals(YoutubeTarget.Channel(channel), YoutubeUrls.parse("https://www.youtube.com/playlist?list=UU$uploadsTail"))
        assertEquals(YoutubeTarget.Channel(channel), YoutubeUrls.parse(YoutubeUrls.uploadsFeedUrl(channel)))
        // Mixes are made per viewer: the link is just its video.
        assertEquals(YoutubeTarget.Video("firstVideo1"), YoutubeUrls.parse("https://www.youtube.com/watch?v=firstVideo1&list=RDfirstVideo1"))
    }

    @Test
    fun rejectsEverythingElse() {
        assertNull(YoutubeUrls.parse("https://www.youtube.com/playlist?list=PLxxxx"))
        assertNull(YoutubeUrls.parse("https://www.youtube.com/playlist?list=WL"))
        assertNull(YoutubeUrls.parse("https://podcasts.apple.com/us/podcast/some-show/id1000000001"))
        assertNull(YoutubeUrls.parse("https://cdn.example/ep.mp3"))
        assertNull(YoutubeUrls.videoId("https://cdn.example/ep.mp3"))
        assertNull(YoutubeUrls.channelIdOfFeed("https://www.youtube.com/watch?v=firstVideo1"))
        assertFalse(YoutubeUrls.looksLikeUrl("alpha"))
        assertTrue(YoutubeUrls.looksLikeUrl("https://youtu.be/firstVideo1"))
        assertFalse(YoutubeUrls.isChannelId("exampleChannel00000000"))
    }
}
