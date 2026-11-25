package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SportGuardTest {
    private val pacersKnicks: (String) -> Boolean = { t -> t.contains("Pacers", true) && t.contains("Knicks", true) }

    @Test fun placeholdersAreInactive() {
        listOf(
            "USA | Indiana Pacers: No Game Today", "EPL 04 - No Event", "Signing Off", "OFF AIR",
            "Data Not Available", "Live in 3 Hours", "live in 45 min", "No Live Event", "NFL Teams Backup 02 (No Games Scheduled)"
        ).forEach { assertTrue(it, SportGuard.inactive(it)) }
    }

    @Test fun realProgramsAreNotInactive() {
        listOf("NBA Basketball", "Pacers vs Knicks", "SportsCenter", "Premier League: Arsenal v Spurs", "Live: Inter Miami at LA Galaxy", null, "")
            .forEach { assertFalse(it.toString(), SportGuard.inactive(it)) }
    }

    @Test fun matchupDetection() {
        assertTrue(SportGuard.namesMatchup("NBA: Lakers vs Celtics"))
        assertTrue(SportGuard.namesMatchup("Arsenal v Tottenham"))
        assertTrue(SportGuard.namesMatchup("Lakers @ Celtics"))
        assertFalse(SportGuard.namesMatchup("NBA Basketball - Regular Season"))
        assertFalse(SportGuard.namesMatchup("SportsCenter at Night"))
        assertFalse(SportGuard.namesMatchup("NBA Basketball"))
    }

    @Test fun guideShowingAnotherGameIsNotThisGame() {
        val e = EpgEntry("NBA: Lakers vs Celtics", "", 100, 200)
        assertEquals(SportGuard.Verdict.OTHER_EVENT, SportGuard.check("ESPN HD", e, pacersKnicks))
    }

    @Test fun guideNamingThisGameWins() {
        val e = EpgEntry("NBA: Knicks vs Pacers", "", 100, 200)
        assertEquals(SportGuard.Verdict.OK, SportGuard.check("ESPN HD", e, pacersKnicks))
        // Description names the game while the title is generic
        assertEquals(SportGuard.Verdict.OK, SportGuard.check("ESPN HD", EpgEntry("NBA Basketball", "Knicks at Pacers", 100, 200), pacersKnicks))
    }

    @Test fun genericGuideDoesNotBlockTheBroadcaster() {
        assertEquals(SportGuard.Verdict.OK, SportGuard.check("ESPN HD", EpgEntry("NBA Basketball", "", 100, 200), pacersKnicks))
        assertEquals(SportGuard.Verdict.OK, SportGuard.check("ESPN HD", null, pacersKnicks))
    }

    @Test fun placeholderChannelOrGuideIsInactive() {
        assertEquals(SportGuard.Verdict.INACTIVE, SportGuard.check("Pacers: No Game Today", null, pacersKnicks))
        assertEquals(SportGuard.Verdict.GUIDE_INACTIVE, SportGuard.check("Pacers TV", EpgEntry("Signing Off", "", 100, 200), pacersKnicks))
        assertEquals(SportGuard.Verdict.GUIDE_INACTIVE, SportGuard.check("EPL 04", EpgEntry("No EVENT Today", "", 100, 200), pacersKnicks))
    }

    @Test fun staleGuideEntriesAreIgnored() {
        val list = listOf(EpgEntry("Old show", "", 0, 50), EpgEntry("Now", "", 50, 150))
        assertNull(SportGuard.entryAt(list, 200))
        assertEquals("Now", SportGuard.entryAt(list, 100)?.title)
    }
}
