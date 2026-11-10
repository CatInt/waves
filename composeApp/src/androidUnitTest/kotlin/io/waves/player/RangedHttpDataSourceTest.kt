package io.waves.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RangedHttpDataSourceTest {
    @Test
    fun onlyYoutubeMediaStreamsAreFetchedInRanges() {
        assertTrue(RangedHttpDataSource.isRanged("rr3---sn-abc.googlevideo.com", "/videoplayback"))
        assertTrue(RangedHttpDataSource.isRanged("RR1---SN-X.GOOGLEVIDEO.COM", "/videoplayback/id/abc/sq/1"))
        assertFalse(RangedHttpDataSource.isRanged("manifest.googlevideo.com", "/api/manifest/hls_playlist/x"))
        assertFalse(RangedHttpDataSource.isRanged("evilgooglevideo.com", "/videoplayback"))
        assertFalse(RangedHttpDataSource.isRanged("cdn.example", "/videoplayback"))
        assertFalse(RangedHttpDataSource.isRanged(null, "/videoplayback"))
    }
}
