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

    @Test fun dragonMp4IsVisibleButReservedForManualChoice() {
        // DragonStreams MP4 is no longer silently removed just because a CDN or MKV exists.
        assertEquals(true, StremioLogic.dragonManualOnly("DragonStreams", "1080p MP4", "https://a/1"))
        assertEquals(true, StremioLogic.dragonManualOnly("dragon streams", "1080p", "https://a/movie.mp4"))
        assertEquals(false, StremioLogic.dragonManualOnly("DragonStreams", "MKV", "https://a/2"))
        assertEquals(false, StremioLogic.dragonManualOnly("DragonStreams", "CDN", "https://cdn.example/a"))
        assertEquals(false, StremioLogic.dragonManualOnly("Other addon", "MP4", "https://a/1"))
        assertEquals(false, StremioLogic.safeForAutomaticPlay(StremioLogic.VERIFIED,
            StremioLogic.LAST_RESORT + "DragonStreams MP4"))
    }
}
