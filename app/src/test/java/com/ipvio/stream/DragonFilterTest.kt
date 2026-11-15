package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Test

class DragonFilterTest {
    @Test fun kinds() {
        assertEquals("mp4", StremioLogic.streamKind("DragonStreams MP4\n1080p", "https://x.example/a"))
        assertEquals("mp4", StremioLogic.streamKind("1080p", "https://x.example/movie.mp4?t=1"))
        assertEquals("mp4", StremioLogic.streamKind("CDN MP4", "https://cdn.example/a"))
        assertEquals("mkv", StremioLogic.streamKind("Movie.2024.1080p.mkv", "https://x.example/a"))
        assertEquals("cdn", StremioLogic.streamKind("DragonStreams CDN", "https://x.example/a"))
        assertEquals("cdn", StremioLogic.streamKind("1080p", "https://cdn3.example/a"))
        assertEquals(null, StremioLogic.streamKind("1080p", "https://x.example/a"))
    }

    @Test fun mp4NeverUsedAndUnmarkedOnlyWithoutBetter() {
        val l = listOf("MP4" to "https://a/1", "MKV" to "https://a/2", "x" to "https://a/3", "CDN" to "https://a/4")
        assertEquals(listOf(1, 3), StremioLogic.dragonFilter(l))
        assertEquals(listOf(1), StremioLogic.dragonFilter(listOf("MP4" to "https://a/1", "x" to "https://a/2")))
        assertEquals(emptyList<Int>(), StremioLogic.dragonFilter(listOf("MP4" to "https://a/1")))
    }
}
