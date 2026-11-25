package com.ipvio.stream

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamIndexTest {
    private val titles = listOf(" nba detroit pistons ", " nba orlando magic ", " nfl detroit lions ", " espn ")

    @Test fun teamKeysFindTheirChannelsOnly() {
        val ix = TeamIndex.build(titles)
        assertArrayEquals(intArrayOf(0, 2), TeamIndex.candidates(ix, listOf(" detroit pistons ", " detroit "), titles.size))
        assertArrayEquals(intArrayOf(1), TeamIndex.candidates(ix, listOf(" magic "), titles.size))
        assertArrayEquals(intArrayOf(), TeamIndex.candidates(ix, listOf(" boston celtics "), titles.size))
    }

    @Test fun noIndexMeansEveryChannel() {
        assertArrayEquals(intArrayOf(0, 1, 2), TeamIndex.candidates(emptyMap(), listOf(" magic "), 3))
    }

    private fun team(name: String, abbr: String) = SportData.Team(name, name.substringAfterLast(' '), abbr, null, null, false)
    private val pistonsAtMagic = SportData.Game("g", "NBA", "NBA", 0, 1, "", team("Orlando Magic", "ORL"), team("Detroit Pistons", "DET"))

    @Test fun providerGuideTeamCodesNameTheGame() {
        assertTrue(SportData.matches(pistonsAtMagic, "Live in 3 hours: DET @ ORL"))
        assertTrue(SportData.matches(pistonsAtMagic, "NBA: ORL vs DET"))
        assertTrue(SportData.matches(pistonsAtMagic, "DET at ORL"))
        // Full names still work
        assertTrue(SportData.matches(pistonsAtMagic, "NBA: Pistons at Magic"))
    }

    @Test fun codesNeedBothTeamsAndTheRightLeague() {
        assertFalse(SportData.matches(pistonsAtMagic, "Live in 1 day: CLE @ NY"))
        assertFalse(SportData.matches(pistonsAtMagic, "DET @ CHI"))
        assertFalse(SportData.matches(pistonsAtMagic, "NFL: DET @ ORL"))
        // Lowercase words are not codes
        assertFalse(SportData.matches(pistonsAtMagic, "det at orl"))
    }

    @Test fun providerCodeVariantsCount() {
        val gsAtNy = SportData.Game("g", "NBA", "NBA", 0, 1, "", team("New York Knicks", "NY"), team("Golden State Warriors", "GS"))
        assertTrue(SportData.matches(gsAtNy, "GSW @ NYK"))
    }
}
