package com.ipvio.stream

/**
 * Usual US TV networks per competition, used only when ESPN's data names no network for a game. Rights holders as of the
 * 2026-27 season; a wrong guess costs nothing (the channel is just not offered), a missing one hides a game that is on TV.
 */
object SportRights {
    private val USMNT = listOf("TBS", "truTV", "TNT", "Universo", "Telemundo", "Peacock")
    private val MEXICO = listOf("TUDN", "Univision", "UniMás", "FOX", "FS1")

    private val BY_LEAGUE = mapOf(
        "Premier League" to listOf("USA Network", "NBC", "Peacock", "Telemundo", "Universo"),
        "Champions League" to listOf("CBS", "CBS Sports Network", "TUDN", "UniMás", "Univision"),
        "Europa League" to listOf("CBS Sports Network", "TUDN", "UniMás"),
        "Conference League" to listOf("CBS Sports Network"),
        "LaLiga" to listOf("ESPN", "ESPN2", "ESPN Deportes"),
        "Copa del Rey" to listOf("ESPN", "ESPN Deportes"),
        "Bundesliga" to listOf("ESPN", "ESPN2", "ESPN Deportes"),
        "DFB-Pokal" to listOf("ESPN", "ESPN2"),
        "Serie A" to listOf("CBS Sports Network", "CBS"),
        "Coppa Italia" to listOf("CBS Sports Network"),
        "Ligue 1" to listOf("beIN Sports", "beIN Sports en Español"),
        "Coupe de France" to listOf("beIN Sports"),
        "FA Cup" to listOf("ESPN", "ESPN2"),
        "Carabao Cup" to listOf("CBS Sports Network"),
        "Championship" to listOf("CBS Sports Network"),
        "Scottish Premiership" to listOf("CBS Sports Network"),
        "Eredivisie" to listOf("ESPN", "ESPN2"),
        "Liga MX" to MEXICO,
        "Concacaf Champions Cup" to listOf("FS1", "FOX", "TUDN", "UniMás"),
        "NWSL" to listOf("CBS", "CBS Sports Network", "ION", "ESPN", "ABC"),
        "Copa Libertadores" to listOf("beIN Sports", "FOX", "FS1"),
        "Saudi Pro League" to listOf("FOX", "FS1", "FS2"),
        "Liga Portugal" to listOf("CBS Sports Network"),
        "MLS" to listOf("FOX", "FS1", "Univision", "UniMás", "TUDN")
    )

    private fun isUsa(t: SportData.Team) = t.abbr.equals("USA", true) || t.name.equals("United States", true) || t.short.equals("USA", true)
    private fun isMexico(t: SportData.Team) = t.abbr.equals("MEX", true) || t.name.equals("Mexico", true) || t.name.equals("México", true)

    fun usual(g: SportData.Game): List<String> {
        if (g.solo) return emptyList()
        // National teams (soccer only: "USA" is also South Alabama in college sports)
        if (SportData.sportOf(g) == "soccer") {
            if (isUsa(g.home) || isUsa(g.away)) return USMNT
            if (isMexico(g.home) || isMexico(g.away)) return MEXICO
        }
        return BY_LEAGUE[g.league].orEmpty()
    }
}
