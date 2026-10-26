package io.waves.search

// Made-up shows in the shape of Apple's responses: the full iTunes field set and the blank lines iTunes emits
// around its JSON.
internal object AppleFixtures {
    const val MORNING_BRIEF = 1000000001L
    const val LAUGH_TRACK = 1000000003L
    const val CASE_FILES = 1000000004L

    private val morningBrief = track(MORNING_BRIEF, "Example News", "Morning Brief", "https://feeds.example.com/morning-brief.xml", "Daily News")
    private val ledger = track(1000000002L, "Example Business Desk", "The Ledger", "https://feeds.example.com/podcasts/feed/the%20ledger", "Business News")
    private val laughTrack = track(LAUGH_TRACK, "Example Comedy", "Laugh Track", "https://feeds.example.com/laugh-track.xml", "Comedy", explicit = true)
    private val caseFiles = track(CASE_FILES, "Example Studios", "Case Files", "https://feeds.example.com/case-files.xml", "True Crime")

    /** GET https://itunes.apple.com/search?media=podcast&entity=podcast&term=morning+brief&limit=2 */
    val search = itunes(morningBrief, ledger)

    /** GET https://itunes.apple.com/lookup?id=1000000001,1000000003,1000000004&entity=podcast */
    val lookupTop3 = itunes(morningBrief, laughTrack, caseFiles)

    /** GET https://rss.marketingtools.apple.com/api/v2/us/podcasts/top/3/podcasts.json */
    val chartTop3 = """{"feed":{"title":"Top Shows","id":"https://rss.marketingtools.apple.com/api/v2/us/podcasts/top/3/podcasts.json","author":{"name":"Apple","url":"https://www.apple.com/"},"links":[{"self":"https://rss.marketingtools.apple.com/api/v2/us/podcasts/top/3/podcasts.json"}],"copyright":"Copyright © 2026 Apple Inc. All rights reserved.","country":"us","icon":"https://www.apple.com/favicon.ico","updated":"Sun, 18 Oct 2026 17:56:15 +0000","results":[""" +
        listOf(
            chartEntry(MORNING_BRIEF, "Example News", "Morning Brief", "News"),
            chartEntry(LAUGH_TRACK, "Example Comedy", "Laugh Track", "Comedy"),
            chartEntry(CASE_FILES, "Example Studios", "Case Files", "True Crime"),
        ).joinToString(",") + "]}}"

    /** GET https://itunes.apple.com/lookup?id=1&entity=podcast */
    val lookupEmpty = itunes()

    private fun itunes(vararg results: String) =
        "\n\n\n{\n \"resultCount\":${results.size},\n \"results\": [\n${results.joinToString(", \n")}]\n}\n\n\n"

    private fun slug(name: String) = name.lowercase().replace(' ', '-').filter { it.isLetterOrDigit() || it == '-' }

    private fun track(id: Long, artist: String, name: String, feedUrl: String, genre: String, explicit: Boolean = false): String {
        val artistId = id + 1_000_000_000L
        val show = "https://podcasts.apple.com/us/podcast/${slug(name)}/id$id?uo=4"
        val art = "https://art.example.com/image/thumb/${slug(name)}/mza_$id.jpg"
        val rating = if (explicit) "Explicit" else "Clean"
        return """{"wrapperType":"track", "kind":"podcast", "artistId":$artistId, "collectionId":$id, "trackId":$id, "artistName":"$artist", "collectionName":"$name", "trackName":"$name", "collectionCensoredName":"$name", "trackCensoredName":"$name", "artistViewUrl":"https://podcasts.apple.com/us/artist/${slug(artist)}/$artistId?uo=4", "collectionViewUrl":"$show", "feedUrl":"$feedUrl", "trackViewUrl":"$show", "artworkUrl30":"$art/30x30bb.jpg", "artworkUrl60":"$art/60x60bb.jpg", "artworkUrl100":"$art/100x100bb.jpg", "collectionPrice":0.00, "trackPrice":0.00, "collectionHdPrice":0, "releaseDate":"2026-10-18T10:00:00Z", "collectionExplicitness":"notExplicit", "trackExplicitness":"${if (explicit) "explicit" else "cleaned"}", "trackCount":250, "trackTimeMillis":1800, "country":"USA", "currency":"USD", "primaryGenreName":"$genre", "contentAdvisoryRating":"$rating", "artworkUrl600":"$art/600x600bb.jpg", "genreIds":["1489", "26"], "genres":["$genre", "Podcasts"]}"""
    }

    private fun chartEntry(id: Long, artist: String, name: String, genre: String) =
        """{"artistName":"$artist","id":"$id","name":"$name","kind":"podcasts","artworkUrl100":"https://art.example.com/image/thumb/${slug(name)}/mza_$id.jpg/100x100bb.png","genres":[{"genreId":"1489","name":"$genre","url":"https://itunes.apple.com/us/genre/id1489"}],"url":"https://podcasts.apple.com/us/podcast/${slug(name)}/id$id"}"""
}
