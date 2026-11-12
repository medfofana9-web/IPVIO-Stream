package com.ipvio.stream

import java.text.Normalizer
import java.util.Locale

/**
 * Finds the channel for a game without the TV guide (pure logic, testable):
 *  - network: ESPN says which network carries the game ("ESPN", "TNT", "FanDuel SN IN"); that name is
 *    compared with the channel names of the playlist after removing country and quality tags.
 *  - team channel: providers often have one channel per team ("NBA: Indiana Pacers" in a group "USA | NBA TEAMS").
 *    A channel that names one of the two teams and carries the league's tag counts as that team's feed.
 */
object SportNet {
    private val MARKS = Regex("\\p{M}+")
    private val NONALNUM = Regex("[^a-z0-9 ]")
    private val SPACES = Regex("\\s+")

    fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(MARKS, "")
            .replace('&', ' ').replace(NONALNUM, " ").replace(SPACES, " ").trim()

    /** Words that say nothing about which network a channel is (country, quality, feed). */
    private val NOISE = setOf(
        "us", "usa", "uk", "ca", "canada", "en", "eng", "hd", "fhd", "uhd", "sd", "4k", "hevc", "h265", "raw", "50fps", "60fps",
        "backup", "alt", "vip", "premium", "channel", "live", "hq", "lq", "the", "ppv", "am", "na", "ny"
    )

    private val STATES = mapOf(
        "al" to "alabama", "az" to "arizona", "ca" to "california", "co" to "colorado", "fl" to "florida", "ga" to "georgia",
        "il" to "illinois", "in" to "indiana", "ia" to "iowa", "ks" to "kansas", "ky" to "kentucky", "la" to "louisiana",
        "md" to "maryland", "ma" to "massachusetts", "mi" to "michigan", "mn" to "minnesota", "mo" to "missouri",
        "nc" to "north carolina", "nj" to "new jersey", "nm" to "new mexico", "oh" to "ohio", "ok" to "oklahoma",
        "or" to "oregon", "pa" to "pennsylvania", "sc" to "south carolina", "tn" to "tennessee", "tx" to "texas",
        "ut" to "utah", "wa" to "washington", "wi" to "wisconsin", "socal" to "socal", "sun" to "sun", "se" to "southeast",
        "sw" to "southwest", "nw" to "northwest", "det" to "detroit", "cin" to "cincinnati", "cle" to "cleveland"
    )

    /**
     * Network or channel name in a comparable form: "FanDuel SN IN" → "fanduelsportsindiana", "US| ESPN 2 HD" → "espn2",
     * "Bally Sports Indiana" → "fanduelsportsindiana" (renamed in 2024).
     */
    fun key(name: String): String {
        var s = " " + norm(name) + " "
        s = s.replace(" bally sports ", " fanduel sports ").replace(" bally ", " fanduel sports ")
            .replace(" fanduel sn ", " fanduel sports ").replace(" fdsn ", " fanduel sports ").replace(" fds ", " fanduel sports ")
            .replace(" sports network ", " sports ").replace(" network ", " ")
            .replace(" nbcsn ", " nbc sports ").replace(" nbcs ", " nbc sports ")
            .replace(" tnt sports ", " tnt ").replace(" truetv ", " trutv ").replace(" tru tv ", " trutv ")
            .replace(" amazon prime video ", " prime video ").replace(" amazon prime ", " prime video ")
            .replace(" espn deportes ", " espndeportes ")
            .replace(" fs1 ", " fox sports 1 ").replace(" fs2 ", " fox sports 2 ").replace(" fox sports one ", " fox sports 1 ")
            .replace(" espn news ", " espnews ").replace(" nbatv ", " nba tv ").replace(" mlbn ", " mlb ").replace(" mlb network ", " mlb ")
            .replace(" nfln ", " nfl ").replace(" nfl network ", " nfl ").replace(" nhln ", " nhl ").replace(" nhl network ", " nhl ")
            .replace(" btn ", " big ten ").replace(" big ten network ", " big ten ").replace(" b1g ", " big ten ")
            .replace(" accn ", " acc ").replace(" secn ", " sec ").replace(" acc network ", " acc ").replace(" sec network ", " sec ")
            .replace(" mnmt ", " monumental ").replace(" sny ", " snysports ").replace(" snysports sports ", " snysports ")
            .replace(" peacock live tv ", " peacock ")
        val words = s.trim().split(' ').filter { it.isNotEmpty() && it !in NOISE }.toMutableList()
        // A trailing state code after a regional network ("fanduel sports in") is spelled out
        if (words.size >= 2 && words.contains("sports")) {
            val last = words.last()
            STATES[last]?.let { words[words.lastIndex] = it }
        }
        return words.joinToString("")
    }

    /** Looser form for a second try: without "sports", "sportsnet", "network" ("Altitude Sports" = "Altitude"). */
    fun looseKey(name: String): String {
        val k = key(name)
        return k.replace("sportsnet", "").replace("sports", "").replace("network", "").ifEmpty { k }
    }

    /** All network keys of a game (national and local broadcasts). Streaming-only services stay in, event channels use them. */
    fun keys(networks: List<String>): List<String> = networks.map { key(it) }.filter { it.length >= 2 }.distinct()

    /** Does this channel carry this network? Exact after cleaning, so "ESPN" never matches "ESPN2" or "ESPNews". */
    fun channelIs(channelTitle: String, netKey: String): Boolean {
        if (netKey.isEmpty()) return false
        val ck = key(stripPrefix(channelTitle))
        return ck == netKey
    }

    /** Provider prefixes such as "US:", "USA |", "[US]", "US - " before the channel name. */
    private val PREFIX = Regex("^\\s*(\\[[^]]{1,8}]|\\|[^|]{1,8}\\||[A-Za-z]{2,4}\\s*[:|]|[A-Za-z]{2,3}\\s+-\\s+)\\s*")
    fun stripPrefix(t: String): String {
        var s = t
        repeat(2) { s = s.replace(PREFIX, "") }
        return s
    }

    /** League tags that mark a channel or group as belonging to a league. */
    fun leagueTags(league: String): List<String> = when (league) {
        "NBA" -> listOf("nba")
        "WNBA" -> listOf("wnba")
        "NFL" -> listOf("nfl")
        "NHL" -> listOf("nhl")
        "MLB" -> listOf("mlb")
        "MLS" -> listOf("mls")
        "College Football" -> listOf("ncaaf", "ncaa", "college football", "cfb")
        "College Basketball", "Women's College Basketball" -> listOf("ncaab", "ncaa", "college basketball", "cbb", "march madness")
        else -> listOf(norm(league)).filter { it.length >= 3 }
    }

    fun hasTag(text: String, tags: List<String>): Boolean {
        val n = " " + norm(text) + " "
        return tags.any { n.contains(" $it ") }
    }

    /**
     * Which team a channel belongs to: 0 = none, 1 = home, 2 = away (only one of them named).
     * [homeKeys]/[awayKeys] are normalized search words of each team (full name, short name …).
     */
    fun teamOf(channelTitle: String, homeKeys: List<String>, awayKeys: List<String>): Int {
        val n = " " + norm(stripPrefix(channelTitle)) + " "
        val h = homeKeys.any { it.length >= 3 && n.contains(" $it ") }
        val a = awayKeys.any { it.length >= 3 && n.contains(" $it ") }
        return when {
            h && !a -> 1
            a && !h -> 2
            else -> 0
        }
    }
}
