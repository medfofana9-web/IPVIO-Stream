package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchPartyLogicTest {
    @Test fun codesAreSixDigits() {
        repeat(200) { assertTrue(Regex("^[1-9][0-9]{5}$").matches(WatchPartyLogic.newCode())) }
    }

    @Test fun guestFollowsHostWithoutJitter() {
        assertEquals(60_400L, WatchPartyLogic.target(60_000, true, 400))
        assertEquals(60_000L, WatchPartyLogic.target(60_000, false, 400))
        assertFalse(WatchPartyLogic.needsSeek(61_000, 60_400))     // small drift: left alone
        assertTrue(WatchPartyLogic.needsSeek(50_000, 60_400))      // far apart: seek
    }
}
