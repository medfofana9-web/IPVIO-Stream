package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderSectionsTest {
    private fun e(t: String) = EpgEntry(t, "", 0, 100)

    @Test fun sportsFoldersOnly() {
        listOf("USA | NBA Teams", "USA | NBA", "USA | NFL Teams", "Live | UEFA Champions League", "Live | EPL Teams", "Live | Bundesliga", "USA | MLS")
            .forEach { assertTrue(it, ProviderSectionsLogic.wanted(it)) }
        listOf("USA | Movies", "USA | News", "24/7 Shows", "USA | NBA Replays", "UK | Betting", "USA | NHL")
            .forEach { assertFalse(it, ProviderSectionsLogic.wanted(it)) }
    }

    @Test fun nbaTeamsComeFirst() {
        val order = listOf("Live | EPL", "USA | NFL Teams", "USA | NBA", "USA | NBA Teams").sortedBy { ProviderSectionsLogic.rank(it) }
        assertEquals(listOf("USA | NBA Teams", "USA | NBA", "USA | NFL Teams", "Live | EPL"), order)
    }

    @Test fun namesReadLikeTeams() {
        assertEquals("Detroit Pistons", ProviderSectionsLogic.tileName("NBA DETROIT PISTONS (FHD)"))
        assertEquals("Bundesliga 01", ProviderSectionsLogic.tileName("Bundesliga 01"))
        assertEquals("NBA Teams", ProviderSectionsLogic.sectionName("USA | NBA Teams"))
    }

    @Test fun guideTellsTheState() {
        val soon = ProviderSectionsLogic.status(e("Live in 3 hours: DAL @ DEN"))
        assertEquals(ProviderSectionsLogic.Kind.SOON, soon.kind)
        assertEquals("In 3 h · DAL @ DEN", soon.text)
        assertEquals(ProviderSectionsLogic.Kind.LATER, ProviderSectionsLogic.status(e("Live in 1 day: CLE @ NY")).kind)
        assertEquals("Tomorrow · CLE @ NY", ProviderSectionsLogic.status(e("Live in 1 day: CLE @ NY")).text)
        assertEquals(ProviderSectionsLogic.Kind.NONE, ProviderSectionsLogic.status(e("No Game Today")).kind)
        assertEquals(ProviderSectionsLogic.Kind.NONE, ProviderSectionsLogic.status(e("Data not available")).kind)
        assertEquals(ProviderSectionsLogic.Kind.LIVE, ProviderSectionsLogic.status(e("NBA: Pistons at Magic")).kind)
        assertEquals(ProviderSectionsLogic.Kind.UNKNOWN, ProviderSectionsLogic.status(null).kind)
    }

    @Test fun liveFirstThenSoonest() {
        val s = listOf(
            ProviderSectionsLogic.status(e("No Game Today")),
            ProviderSectionsLogic.status(e("Live in 5 hours: A @ B")),
            ProviderSectionsLogic.status(e("NBA: Pistons at Magic")),
            ProviderSectionsLogic.status(e("Live in 1 hours: C @ D"))
        )
        assertEquals(listOf(2, 3, 1, 0), ProviderSectionsLogic.order(s))
    }
}
