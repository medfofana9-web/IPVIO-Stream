package com.ipvio.stream

/**
 * Usual US TV networks per competition, used only when ESPN's data names no network for a game. Rights holders as of the
 * 2026-27 season; a wrong guess costs nothing (the channel is just not offered), a missing one hides a game that is on TV.
 */
object SportRights {
    private val USMNT = listOf("TBS", "truTV", "TNT", "Universo", "Telemundo", "Peacock")
    private val MEXICO = listOf("TUDN", "Univision", "UniMás", "FOX", "FS1")
    // European national teams (Nations League, qualifiers, friendlies): FOX holds the US rights
    private val UEFA_NATIONS = listOf("FOX", "FS1", "FS2", "FOX Deportes", "Fox Soccer Plus", "Telemundo", "Universo", "Peacock")

    /**
     * Last resort when nothing else names a channel: the networks that carry this sport in the US. The player tries them
     * in order and moves on when one does not start, so a game is never left without a channel.
     */
    private val BY_SPORT = mapOf(
        "soccer" to listOf("FOX", "FS1", "FS2", "FOX Deportes", "ESPN", "ESPN2", "ESPN Deportes", "CBS Sports Network", "TUDN", "Univision",
            "UniMás", "Telemundo", "Universo", "beIN Sports", "beIN Sports en Español", "USA Network", "Peacock"),
        "NBA" to listOf("ESPN", "ABC", "NBC", "Peacock", "NBA TV", "Prime Video", "ESPN2"),
        "WNBA" to listOf("ION", "ESPN", "ABC", "CBS", "NBA TV", "Prime Video", "ESPN2"),
        "NFL" to listOf("CBS", "FOX", "NBC", "ESPN", "ABC", "NFL Network", "Prime Video", "Peacock"),
        "College Football" to listOf("ESPN", "ABC", "FOX", "CBS", "NBC", "ESPN2", "FS1", "Big Ten Network", "SEC Network", "ACC Network", "ESPNU", "CBS Sports Network"),
        "College Basketball" to listOf("ESPN", "ESPN2", "ESPNU", "FS1", "CBS", "CBS Sports Network", "Big Ten Network", "SEC Network", "ACC Network", "FOX"),
        "Women's College Basketball" to listOf("ESPN", "ESPN2", "ESPNU", "FS1", "SEC Network", "ACC Network", "Big Ten Network", "CBS Sports Network", "ABC", "FOX")
    )

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
        "MLS" to listOf("FOX", "FS1", "Univision", "UniMás", "TUDN"),
        "Nations League" to UEFA_NATIONS,
        "International Friendlies" to listOf("FOX", "FS1", "FS2", "FOX Deportes", "TUDN", "Univision", "ESPN", "ESPN2", "Telemundo", "Universo")
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
        BY_LEAGUE[g.league]?.let { return it }
        // World Cup / Euro qualifiers of European teams
        if (g.league.contains("UEFA", true) || g.league.contains("Euro", true)) return UEFA_NATIONS
        return emptyList()
    }

    /** The sport's usual networks (see [BY_SPORT]). */
    fun fallback(g: SportData.Game): List<String> =
        BY_SPORT[g.league] ?: if (SportData.sportOf(g) == "soccer") BY_SPORT["soccer"].orEmpty() else emptyList()
}
