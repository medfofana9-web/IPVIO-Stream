package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntroSkipLogicTest {
    private val min = 60_000L

    @Test fun offeredEarlyInEpisodesWithoutKnownIntro() {
        assertTrue(IntroSkipLogic.offer(2 * min, 50 * min, episode = true, knownIntro = false, done = false))
        assertFalse(IntroSkipLogic.offer(13 * min, 50 * min, episode = true, knownIntro = false, done = false))
        assertFalse(IntroSkipLogic.offer(2 * min, 50 * min, episode = false, knownIntro = false, done = false))
        assertFalse(IntroSkipLogic.offer(2 * min, 50 * min, episode = true, knownIntro = true, done = false))
        assertFalse(IntroSkipLogic.offer(2 * min, 50 * min, episode = true, knownIntro = false, done = true))
        // Short episode: only the first half
        assertTrue(IntroSkipLogic.offer(80_000, 180_000, episode = true, knownIntro = false, done = false))
        assertFalse(IntroSkipLogic.offer(100_000, 180_000, episode = true, knownIntro = false, done = false))
    }

    @Test fun jumpUsesLearnedLengthOrSixtySeconds() {
        assertEquals(60_000L, IntroSkipLogic.jumpMs(null))
        assertEquals(43_000L, IntroSkipLogic.jumpMs(43_000L))
        assertEquals(60_000L, IntroSkipLogic.jumpMs(2_000L))
        assertEquals(60_000L, IntroSkipLogic.jumpMs(400_000L))
    }

    @Test fun targetStopsBeforeTheEnd() {
        assertEquals(90_000L, IntroSkipLogic.target(30_000L, 60_000L, 50 * min))
        assertEquals(175_000L, IntroSkipLogic.target(150_000L, 60_000L, 180_000L))
    }

    @Test fun learnsWhereTheViewerLanded() {
        assertEquals(52_000L, IntroSkipLogic.learned(100_000L, 152_000L))
        assertNull(IntroSkipLogic.learned(100_000L, 95_000L))
        assertNull(IntroSkipLogic.learned(100_000L, 500_000L))
    }

    @Test fun parsesSkipDbSegments() {
        val body = """{"segments":{"intro":{"start_ms":61000,"end_ms":112500,"confidence":0.9},"recap":null,"outro":{"start_ms":2700000,"end_ms":null}}}"""
        val r = IntroSkipLogic.parseSkipDb(body)
        assertEquals(Triple(SegLogic.INTRO, 61_000L as Long?, 112_500L as Long?), r[0])
        assertEquals(Triple(SegLogic.CREDITS, 2_700_000L as Long?, null), r[1])
        assertEquals(2, r.size)
        assertTrue(IntroSkipLogic.parseSkipDb("not json").isEmpty())
        assertTrue(IntroSkipLogic.parseSkipDb("""{"error":"not found"}""").isEmpty())
    }
}
