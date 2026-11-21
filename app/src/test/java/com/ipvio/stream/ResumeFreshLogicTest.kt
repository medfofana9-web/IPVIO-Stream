package com.ipvio.stream

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
}
