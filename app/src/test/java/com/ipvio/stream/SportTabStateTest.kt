package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Test

class SportTabStateTest {
    @Test fun returnsToAllSevenSportsDestinations() {
        for (i in 0..6) assertEquals(i, SportTabState.restore(i, 7))
    }

    @Test fun unknownTabFallsBackToSportsOverview() {
        assertEquals(0, SportTabState.restore(-1, 7))
        assertEquals(0, SportTabState.restore(7, 7))
        assertEquals(0, SportTabState.restore(4, 0))
    }
}
