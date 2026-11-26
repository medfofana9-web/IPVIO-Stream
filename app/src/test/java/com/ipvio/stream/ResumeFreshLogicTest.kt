package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeFreshLogicTest {
    @Test fun savedProviderEpisodeIsRecognized() {
        val episode = Episode(51, 9, "Episode 9", "https://iptv.example/season51/episode9.mkv")
        assertTrue(ResumeFreshLogic.fromCurrentPlaylist(
            "https://iptv.example/season51/episode9.mkv", episode
        ))
    }

    @Test fun expiredAddonUrlIsNotMistakenForCurrentIptvEpisode() {
        val episode = Episode(51, 9, "Episode 9", "https://iptv.example/season51/episode9.mkv")
        assertFalse(ResumeFreshLogic.fromCurrentPlaylist(
            "https://dragonstreams.example/token-that-expired", episode
        ))
        assertFalse(ResumeFreshLogic.fromCurrentPlaylist("", episode))
    }

    @Test fun anotherEpisodeUrlMustNotBeResumed() {
        val episode = Episode(51, 9, "Episode 9", "https://iptv.example/season51/episode9.mkv")
        assertFalse(ResumeFreshLogic.fromCurrentPlaylist(
            "https://iptv.example/season51/episode8.mkv", episode
        ))
    }

    @Test fun movieResumeAsksAddonsAgainForAnExpiredAddonLink() {
        assertEquals("", ResumeFreshLogic.movieUrl("https://debrid.example/dl/abc/Inside.Man.mkv", "http://iptv.example/movie/u/p/1.mkv", true))
        assertEquals("", ResumeFreshLogic.movieUrl("https://debrid.example/dl/abc/Inside.Man.mkv", "", true))
    }

    @Test fun movieResumeUsesTheCurrentPlaylistAddress() {
        assertEquals("http://iptv.example/movie/u/p/2.mkv", ResumeFreshLogic.movieUrl("http://iptv.example/movie/u/p/1.mkv", "http://iptv.example/movie/u/p/2.mkv", true))
        assertEquals("http://iptv.example/movie/u/p/1.mkv", ResumeFreshLogic.movieUrl("https://debrid.example/x", "http://iptv.example/movie/u/p/1.mkv", false))
        assertEquals("https://debrid.example/x", ResumeFreshLogic.movieUrl("https://debrid.example/x", "", false))
    }
}
