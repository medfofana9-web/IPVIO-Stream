package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The serial waits between OK and the first picture: stream count, name lookup, start buffer. */
class StartPathTest {
    @Test fun recentCountWithRoomStartsWithoutAskingTheProvider() {
        assertTrue(ConnLogic.clearlyFree(active = 1, max = 4, freedHere = 0, ageMs = 10_000))
        assertTrue(ConnLogic.clearlyFree(active = 2, max = 4, freedHere = 0, ageMs = 59_000))
        // Close to the cap the provider is asked again
        assertFalse(ConnLogic.clearlyFree(active = 3, max = 4, freedHere = 0, ageMs = 1_000))
        assertTrue(ConnLogic.clearlyFree(active = 3, max = 4, freedHere = 1, ageMs = 1_000))
        // Old or future-dated counts are not trusted
        assertFalse(ConnLogic.clearlyFree(active = 0, max = 4, freedHere = 0, ageMs = 60_000))
        assertFalse(ConnLogic.clearlyFree(active = 0, max = 4, freedHere = 0, ageMs = -5))
        assertFalse(ConnLogic.clearlyFree(active = 0, max = 0, freedHere = 0, ageMs = 1))
    }

    @Test fun knownNamesAnswerFromTheCacheAndRenewInTheBackground() {
        val now = 100L * 86_400_000L
        assertEquals(DnsLogic.Use.FRESH, DnsLogic.cacheUse(now - 60_000, now))
        assertEquals(DnsLogic.Use.STALE, DnsLogic.cacheUse(now - 11 * 60_000, now))
        assertEquals(DnsLogic.Use.STALE, DnsLogic.cacheUse(now + 5_000, now))
        assertEquals(DnsLogic.Use.EXPIRED, DnsLogic.cacheUse(now - DnsLogic.STALE_MS, now))
        assertTrue(DnsLogic.DOH_BUDGET_MS <= 1_500L)
    }

    @Test fun liveStartBufferStaysShortAfterLearningFromStutter() {
        assertEquals(1_200, BufferLogic.liveStartMs(1_200, 0))
        assertEquals(2_000, BufferLogic.liveStartMs(1_200, 2))
        assertEquals(2_000, BufferLogic.liveStartMs(1_500, 2))
        // VOD keeps the full scale
        assertEquals(5_200, BufferLogic.scale(intArrayOf(20_000, 40_000, 1_200, 6_000, -1), 2)[2])
    }

    @Test fun learnedBufferLevelIsForgottenAfterThreeDays() {
        val now = 50L * 86_400_000L
        assertEquals(2, BufferLogic.learnedNow(2, now - 86_400_000L, now))
        assertEquals(0, BufferLogic.learnedNow(2, now - BufferLogic.LEARN_TTL_MS, now))
        // Learned before the time was recorded (older builds): forgotten, the start gets fast again
        assertEquals(0, BufferLogic.learnedNow(1, 0L, now))
    }
}
