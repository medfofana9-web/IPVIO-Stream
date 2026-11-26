package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceHealthLogicTest {
    private val now = 10_000_000L

    @Test fun failedSourceGoesLastForSixHours() {
        val m = SourceHealthLogic.Mark(SourceHealthLogic.FAILED, now - 1000)
        assertEquals(1, SourceHealthLogic.penalty(m, now, resuming = false))
        assertEquals(0, SourceHealthLogic.penalty(m, now + SourceHealthLogic.TTL_MS, resuming = false))
    }

    @Test fun noSeekOnlyMattersWhenResuming() {
        val m = SourceHealthLogic.Mark(SourceHealthLogic.NO_SEEK, now)
        assertEquals(1, SourceHealthLogic.penalty(m, now, resuming = true))
        assertEquals(0, SourceHealthLogic.penalty(m, now, resuming = false))
        assertEquals(0, SourceHealthLogic.penalty(SourceHealthLogic.Mark(SourceHealthLogic.OK, now), now, true))
        assertEquals(0, SourceHealthLogic.penalty(null, now, true))
    }

    @Test fun storeKeepsTheNewestAndSurvivesARoundTrip() {
        val m = LinkedHashMap<String, SourceHealthLogic.Mark>()
        for (i in 0 until SourceHealthLogic.MAX + 5) SourceHealthLogic.put(m, "tt1\tA|$i", SourceHealthLogic.Mark(2, i.toLong()))
        assertEquals(SourceHealthLogic.MAX, m.size)
        val back = SourceHealthLogic.decode(SourceHealthLogic.encode(m))
        assertEquals(m, back)
    }
}
