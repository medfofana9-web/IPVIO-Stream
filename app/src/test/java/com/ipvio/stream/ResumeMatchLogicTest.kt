package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ResumeMatchLogicTest {
    @Test fun episodeTitleMatchesItsShow() {
        assertEquals("The Bear", ResumeMatchLogic.baseTitle("The Bear S2E9"))
        assertEquals(ResumeMatchLogic.key("The Bear", true), ResumeMatchLogic.key("The Bear S2E9", true))
    }

    @Test fun moviesAndShowsNeverMix() {
        assertNotEquals(ResumeMatchLogic.key("Fargo", false), ResumeMatchLogic.key("Fargo", true))
        assertNotEquals(ResumeMatchLogic.key("Dune", false), ResumeMatchLogic.key("Dune: Part Two", false))
    }

    @Test fun punctuationAndCaseIgnored() {
        assertEquals(ResumeMatchLogic.key("Spider-Man: No Way Home", false), ResumeMatchLogic.key("spider man  no way home", false))
    }
}
