package com.ipvio.stream

import com.ipvio.stream.StremioLogic.MISMATCH
import com.ipvio.stream.StremioLogic.UNVERIFIED
import com.ipvio.stream.StremioLogic.VERIFIED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamMatchTest {
    private fun ep(t: String, s: Int, e: Int, title: String = "Reasonable Doubt", year: Int? = 2022) =
        StremioLogic.matchCheck(t, true, title, year, s, e)

    @Test fun rightEpisodeIsVerified() {
        assertEquals(VERIFIED, ep("Reasonable.Doubt.2022.S04E01.1080p.WEB.h264.mkv", 4, 1))
        assertEquals(VERIFIED, ep("Reasonable Doubt - 4x01 - Pilot", 4, 1))
    }

    @Test fun otherEpisodeOrSeasonIsRejected() {
        assertEquals(MISMATCH, ep("Reasonable.Doubt.S04E10.1080p.mkv", 4, 1))      // S04E10 is not S04E01
        assertEquals(MISMATCH, ep("Reasonable.Doubt.S03E01.1080p.mkv", 4, 1))
        assertEquals(MISMATCH, ep("Reasonable Doubt Season 2 Complete", 4, 1))
    }

    @Test fun sameNameDifferentShowIsNotVerified() {
        // The 1991 series "Reasonable Doubts": right numbers, wrong show and year
        assertEquals(UNVERIFIED, ep("Reasonable.Doubts.1991.S01E01.DVDRip.avi", 1, 1))
        assertEquals(UNVERIFIED, ep("Other.Show.S01E01.720p.mkv", 1, 1))
    }

    @Test fun noInformationStaysUnverified() {
        assertEquals(UNVERIFIED, ep("DragonStreams 1080p (CDN)", 4, 1))
    }

    @Test fun movies() {
        val m = { t: String -> StremioLogic.matchCheck(t, false, "Reasonable Doubt", 2014, 0, 0) }
        assertEquals(VERIFIED, m("Reasonable.Doubt.2014.1080p.BluRay.x264.mkv"))
        assertEquals(MISMATCH, m("Reasonable.Doubt.S01E01.1080p.mkv"))               // an episode is not the movie
        assertEquals(MISMATCH, m("Reasonable.Doubt.1997.720p.mkv"))                  // another film of that name
        assertEquals(UNVERIFIED, m("Stream 1080p"))
    }

    @Test fun factsAreOnlyWhatTheTextSays() {
        val f = StremioLogic.facts("Movie.2014.2160p.DV.HDR.DDP5.1.Atmos 12.4 GB")
        assertTrue("Dolby Vision" in f && "Atmos" in f && "5.1" in f && "12.4 GB" in f)
        assertEquals(emptyList<String>(), StremioLogic.facts("Stream 1080p"))
    }
}
