package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsStartupLogicTest {
    @Test fun multipleUnverifiedFeedsGetFastFallback() {
        assertEquals(4_500L, SportsStartupLogic.nextFeedDelayMs(false, 3))
        assertTrue(SportsStartupLogic.shouldTryNext(false, true, 3))
    }

    @Test fun previouslyWorkingFeedGetsSlightlyLongerGrace() {
        assertEquals(7_500L, SportsStartupLogic.nextFeedDelayMs(true, 2))
    }

    @Test fun oneFeedDoesNotPointlesslyRetryAnother() {
        assertNull(SportsStartupLogic.nextFeedDelayMs(false, 1))
        assertFalse(SportsStartupLogic.shouldTryNext(false, true, 1))
    }

    @Test fun neverFailoverPlayingFeedOrNonGameContent() {
        assertFalse(SportsStartupLogic.shouldTryNext(true, true, 4))
        assertFalse(SportsStartupLogic.shouldTryNext(false, false, 4))
    }

    /** #62: a dead feed that sent a few bytes (headers, a playlist) kept its full time; only real video counts as data. */
    @Test fun aFewBytesStillCountAsNoData() {
        assertTrue(SportsStartupLogic.noData(1_000L, 1_000L))
        assertTrue(SportsStartupLogic.noData(1_000L, 1_000L + 4 * 1024))
        assertTrue(SportsStartupLogic.noData(0L, SportsStartupLogic.NO_DATA_BYTES - 1))
        assertFalse(SportsStartupLogic.noData(0L, SportsStartupLogic.NO_DATA_BYTES))
        assertFalse(SportsStartupLogic.noData(5_000L, 5_000L + 300 * 1024))
    }
}
