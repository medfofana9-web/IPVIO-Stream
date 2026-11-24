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

    @Test fun fieldEventRequiresSpecificEventName() {
        val race = match(team(""), team("")).copy(
            solo = true, title = "Las Vegas Grand Prix", league = "Formula 1"
        )
        assertTrue(SportData.matches(race, "Formula 1: Las Vegas Grand Prix"))
        assertFalse(SportData.matches(race, "Formula 1: Qatar Grand Prix"))
        assertFalse(SportData.matches(race.copy(title = "Formula 1"), "Formula 1"))
    }

    // 24 Nov 2026, 20:00 UTC (Champions League night)
    private val ucl = 1_795_550_400L

    private fun cl(home: SportData.Team, away: SportData.Team) =
        SportData.Game("cl", "Champions League", "Soccer", ucl, 1, "", home, away, nets = listOf("Paramount+"))

    @Test fun otherChampionsLeagueMatchesAreNotThisGame() {
        val g = cl(team("Manchester City", "Man City"), team("Napoli"))
        assertFalse(SportData.matches(g, "UEFA Champions League 04: RB Leipzig - Manchester"))
        assertFalse(SportData.matches(g, "TV2 NO 19: UEFA Champions League Porto - Napoli I dag"))
        assertTrue(SportData.matches(g, "Paramount+ 07: Napoli vs Manchester City"))
        val p = cl(team("PSV Eindhoven", "PSV"), team("Real Madrid"))
        assertFalse(SportData.matches(p, "TV2 NO 03: UEFA Champions League AEK - Real Madrid I"))
        assertFalse(SportData.matches(p, "UEFA Champions League 07: PSV Eindhoven - Club Brugge"))
    }

    @Test fun datedEventChannelOnlyCountsOnItsDay() {
        val g = cl(team("FC Porto", "Porto"), team("Napoli"))
        assertFalse(SportData.dateFits(g, "UEFA Champions League 10: Porto - Napoli @ 4 Nov"))
        assertTrue(SportData.dateFits(g, "UEFA Champions League 10: Porto - Napoli @ 24 Nov"))
        assertTrue(SportData.dateFits(g, "Porto vs Napoli"))
    }

    @Test fun soccerTeamKeysSkipCityWords() {
        val keys = SportData.soccerTeamKeys(team("Manchester City", "Man City"))
        assertFalse("manchester" in keys)
        assertTrue("manchester city" in keys)
    }
}
