package com.ipvio.stream

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SportMatchupTest {
    private fun team(name: String, short: String = name) =
        SportData.Team(name, short, "", null, null, false)

    private fun match(home: SportData.Team, away: SportData.Team) =
        SportData.Game("match", "soccer", "Soccer", 0, 1, "", home, away)

    @Test fun acceptsBothTeamsInEitherOrder() {
        val game = match(team("Real Madrid"), team("Real Sociedad"))
        assertTrue(SportData.matches(game, "Real Madrid vs Real Sociedad"))
        assertTrue(SportData.matches(game, "Real Sociedad at Real Madrid"))
    }

    @Test fun rejectsAnotherGameInTheSameCity() {
        val game = match(team("Real Madrid"), team("Barcelona"))
        assertFalse(SportData.matches(game, "Atlético Madrid vs Barcelona"))
    }

    @Test fun doesNotCountOneTeamNameTwice() {
        val game = match(team("Paris FC", "Paris"), team("Paris Saint-Germain", "PSG"))
        assertFalse(SportData.matches(game, "Paris Saint-Germain vs Monaco"))
        assertTrue(SportData.matches(game, "Paris FC vs PSG"))
    }
}
