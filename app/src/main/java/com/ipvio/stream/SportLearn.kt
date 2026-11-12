package com.ipvio.stream

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * Remembers channels picked by hand beyond the single game: per team ("NBA|indiana pacers" → the Pacers channel)
 * when the picked channel names that team, otherwise per network when the game had exactly one network.
 * Next time a game of that team or on that network is found automatically. Per profile.
 */
object SportLearn {
    private val map = HashMap<String, String>()
    private var inited = false
    var version by mutableIntStateOf(0)
        private set

    private fun prefs(c: Context) = Profiles.sp(c, "fof_sportlearn")

    fun init(c: Context) {
        if (inited) return
        inited = true
        (prefs(c).getString("m", "") ?: "").split("\n").forEach { l ->
            val i = l.indexOf('\t')
            if (i > 0) map[l.substring(0, i)] = l.substring(i + 1)
        }
        version++
    }

    private fun teamKey(league: String, t: SportData.Team) = "team:" + league + "|" + SportData.norm(t.name)
    private fun netKey(n: String) = "net:" + SportNet.key(n)

    fun learn(c: Context, g: SportData.Game, ch: Item) {
        init(c)
        if (!g.solo) {
            when (SportNet.teamOf(ch.title, SportData.keysCached(g.home), SportData.keysCached(g.away))) {
                1 -> map[teamKey(g.league, g.home)] = ch.id
                2 -> map[teamKey(g.league, g.away)] = ch.id
                else -> if (g.nets.size == 1) map[netKey(g.nets[0])] = ch.id
            }
        } else if (g.nets.size == 1) map[netKey(g.nets[0])] = ch.id
        prefs(c).edit().putString("m", map.entries.joinToString("\n") { it.key + "\t" + it.value }).apply()
        version++
    }

    /** Channel ids learned for this game: favorite/home team, away team, then networks. */
    fun lookup(g: SportData.Game): List<String> {
        if (map.isEmpty()) return emptyList()
        val out = ArrayList<String>(3)
        if (!g.solo) {
            val teams = if (SportFavs.has(g.away) && !SportFavs.has(g.home)) listOf(g.away, g.home) else listOf(g.home, g.away)
            teams.forEach { t -> map[teamKey(g.league, t)]?.let(out::add) }
        }
        g.nets.forEach { n -> map[netKey(n)]?.let(out::add) }
        return out.distinct()
    }
}
