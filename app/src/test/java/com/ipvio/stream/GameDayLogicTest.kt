package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameDayLogicTest {
    @Test fun clocks() {
        assertEquals(92, GameDayLogic.clockSec("1:32"))
        assertEquals(45, GameDayLogic.clockSec("45.2"))
        assertEquals(85, GameDayLogic.minute("85'"))
    }

    @Test fun close_games() {
        assertTrue(GameDayLogic.isClose("basketball", 4, "1:32", "101", "99"))
        assertFalse(GameDayLogic.isClose("basketball", 4, "1:32", "110", "99"))
        assertFalse(GameDayLogic.isClose("basketball", 3, "1:32", "101", "99"))
        assertTrue(GameDayLogic.isClose("basketball", 5, "3:00", "110", "108"))
        assertTrue(GameDayLogic.isClose("nfl", 4, "4:10", "24", "20"))
        assertTrue(GameDayLogic.isClose("nhl", 3, "2:00", "2", "2"))
        assertTrue(GameDayLogic.isClose("soccer", 2, "88'", "1", "1"))
        assertFalse(GameDayLogic.isClose("soccer", 2, "60'", "1", "1"))
        assertFalse(GameDayLogic.isClose("basketball", 4, "1:00", null, "99"))
    }
}
