package com.ipvio.stream

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntroMarksTest {
    @Test fun acceptsConfirmedEpisodeWindow() {
        assertTrue(IntroMarks.valid(45_000, 95_000, 2_400_000))
        assertTrue(IntroMarks.Window(45_000, 95_000).contains(60_000))
    }

    @Test fun doesNotSkipOutsideConfirmedWindow() {
        val window = IntroMarks.Window(45_000, 95_000)
        assertFalse(window.contains(44_999))
        assertFalse(window.contains(95_000))
    }

    @Test fun rejectsMissingBackwardsOrExcessivelyLongMarkers() {
        assertFalse(IntroMarks.valid(-1, 20_000, 2_400_000))
        assertFalse(IntroMarks.valid(30_000, 20_000, 2_400_000))
        assertFalse(IntroMarks.valid(10_000, 11_000, 2_400_000))
        assertFalse(IntroMarks.valid(0, 16 * 60_000L, 2_400_000))
    }

    @Test fun rejectsMarkersPastEpisodeDuration() {
        assertFalse(IntroMarks.valid(30_000, 150_000, 100_000))
    }
}
