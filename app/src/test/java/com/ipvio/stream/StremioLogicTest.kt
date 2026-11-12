package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StremioLogicTest {
    @Test fun base_from_pasted_links() {
        assertEquals("https://addon.example.com/abc", StremioLogic.base("stremio://addon.example.com/abc/manifest.json"))
        assertEquals("https://addon.example.com", StremioLogic.base(" https://addon.example.com/manifest.json "))
        assertEquals("https://addon.example.com/x", StremioLogic.base("addon.example.com/x/"))
        assertNull(StremioLogic.base(""))
        assertNull(StremioLogic.base("hello"))
    }

    @Test fun stream_addresses() {
        assertEquals("https://a.io/stream/movie/tt0111161.json", StremioLogic.streamUrl("https://a.io", Kind.MOVIE, "tt0111161", 0, 0))
        assertEquals("https://a.io/stream/series/tt0903747:2:5.json", StremioLogic.streamUrl("https://a.io", Kind.SERIES, "tt0903747", 2, 5))
    }

    @Test fun titles_labels_quality() {
        assertEquals("Breaking Bad", StremioLogic.cleanTitle("EN - Breaking Bad [4K] (2008)"))
        assertEquals(2008, StremioLogic.year("2008–2013"))
        assertTrue(StremioLogic.isImdb("tt0111161"))
        assertEquals("Torrentio 4K · Movie.2160p.mkv", StremioLogic.label("Torrentio\n4K", "Movie.2160p.mkv\n👤 12"))
        assertEquals(3, StremioLogic.quality("Movie 2160p"))
        assertEquals(2, StremioLogic.quality("1080p WEB"))
    }
}
