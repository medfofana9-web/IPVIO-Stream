package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SportPinsTest {
    private fun ch(id: String, title: String) = Item(id, Kind.LIVE, title, null, "http://x/$id", null, "Sports")

    @Test fun baseStripsCountryAndQuality() {
        assertEquals("espn", SportPins.base("US: ESPN FHD"))
        assertEquals("nba tv", SportPins.base("USA | NBA TV (1080p)"))
        assertEquals("espn 2", SportPins.base("[US] ESPN 2 HD"))
    }

    @Test fun resolvePicksBestFeedByName() {
        val live = listOf(ch("1", "UK: ESPN HD"), ch("2", "US: ESPN FHD"), ch("3", "US: ESPN Backup"), ch("4", "US: ESPN 2"))
        assertEquals("2", SportPins.resolve("name:ESPN", live)?.id)
        assertNull(SportPins.resolve("name:NBA TV", live))
    }

    /** A large playlist stays fast now that the patterns are compiled once (they froze the Sports page, #71/#73/#75). */
    @Test fun resolveOnLargePlaylistIsQuick() {
        val live = (0 until 60_000).map { ch("c$it", "US: Channel $it HD") } + ch("e", "US: ESPN FHD")
        val t0 = System.nanoTime()
        assertEquals("e", SportPins.resolve("name:ESPN", live)?.id)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assert(ms < 5_000) { "resolve took $ms ms" }
    }
}
