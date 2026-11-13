package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchAllLogicTest {
    @Test fun matchupWordsAreIgnored() {
        assertEquals(listOf("lakers", "celtics"), SearchAllLogic.tokens("Lakers vs. Celtics"))
    }

    @Test fun wordStartsMatch() {
        val hay = "Los Angeles Lakers Lakers LAL Boston Celtics Celtics BOS NBA"
        assertTrue(SearchAllLogic.matches(hay, SearchAllLogic.tokens("lak")))
        assertTrue(SearchAllLogic.matches(hay, SearchAllLogic.tokens("lakers vs celtics")))
        assertFalse(SearchAllLogic.matches(hay, SearchAllLogic.tokens("knicks")))
        assertFalse(SearchAllLogic.matches(hay, emptyList()))
    }

    @Test fun liveFirstThenUpcomingThenFinished() {
        val order = listOf(2 to 100L, 0 to 500L, 1 to 50L, 0 to 300L).sortedBy { SearchAllLogic.rank(it.first, it.second) }
        assertEquals(listOf(1 to 50L, 0 to 300L, 0 to 500L, 2 to 100L), order)
    }
}
