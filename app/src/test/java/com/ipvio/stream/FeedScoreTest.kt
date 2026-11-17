package com.ipvio.stream

import org.junit.Assert.assertTrue
import org.junit.Test

class FeedScoreTest {
    private fun s(ok: Int, fail: Int, ms: Long, st: Int, h: Int) = FeedScore.score(ok, fail, ms, st, h)

    @Test
    fun reliableBeatsSharpButBroken() {
        // A 720p feed that always starts beats a 4K feed that fails most of the time
        assertTrue(s(6, 0, 2000, 0, 720) > s(1, 5, 2000, 0, 2160))
    }

    @Test
    fun sharperWinsWhenEquallyReliable() {
        assertTrue(s(5, 0, 2000, 0, 1080) > s(5, 0, 2000, 0, 720))
        assertTrue(s(5, 0, 2000, 0, 2160) > s(5, 0, 2000, 0, 1080))
    }

    @Test
    fun freezesAndSlowStartsCost() {
        assertTrue(s(5, 0, 2000, 0, 1080) > s(5, 0, 2000, 10, 1080))
        assertTrue(s(5, 0, 1500, 0, 1080) > s(5, 0, 9000, 0, 1080))
    }

    @Test
    fun unknownSitsBetween() {
        val unknown = s(0, 0, 0, 0, 0)
        assertTrue(unknown < s(5, 0, 2000, 0, 1080))
        assertTrue(unknown > s(0, 4, 0, 0, 0))
    }
}
