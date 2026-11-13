package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MarathonLogicTest {
    private val eps = listOf(2 to 1, 1 to 2, 1 to 1, 2 to 2, 1 to 2)

    @Test fun inOrderSortsBySeasonThenEpisode() {
        assertEquals(listOf(1 to 1, 1 to 2, 2 to 1, 2 to 2), MarathonLogic.order(eps, false, 1L))
    }

    @Test fun shuffleKeepsEveryEpisodeOnce() {
        val many = (1..5).flatMap { s -> (1..10).map { s to it } }
        val sh = MarathonLogic.order(many, true, 42L)
        assertEquals(many.toSet(), sh.toSet())
        assertEquals(many.size, sh.size)
        assertNotEquals(many, sh)
    }

    @Test fun startsAtLastWatchedOrAtTheBeginning() {
        val o = MarathonLogic.order(eps, false, 1L)
        assertEquals(2, MarathonLogic.startIndex(o, 2 to 1))
        assertEquals(0, MarathonLogic.startIndex(o, 9 to 9))
        assertEquals(0, MarathonLogic.startIndex(o, null))
    }
}
