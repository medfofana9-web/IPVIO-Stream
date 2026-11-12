package com.ipvio.stream

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.delay

/** When is a running game "close"? Pure logic, testable. [sport] is the league's sport key from [SportData.League]. */
object GameDayLogic {
    /** Seconds left in the period from an ESPN clock ("1:32", "0.4", "45"); null if unknown. */
    fun clockSec(clock: String): Int? {
        val c = clock.trim()
        if (c.isEmpty()) return null
        val p = c.split(':')
        return if (p.size == 2) (p[0].toIntOrNull() ?: return null) * 60 + (p[1].substringBefore('.').toIntOrNull() ?: 0)
        else c.substringBefore('.').toIntOrNull()
    }

    /** Minute of a soccer match ("85'", "90'+3'"). */
    fun minute(clock: String): Int? = Regex("(\\d{1,3})").find(clock)?.value?.toIntOrNull()

    fun isClose(sport: String, period: Int, clock: String, home: String?, away: String?): Boolean {
        val h = home?.toIntOrNull() ?: return false
        val a = away?.toIntOrNull() ?: return false
        val margin = kotlin.math.abs(h - a)
        val left = clockSec(clock)
        return when (sport) {
            "basketball" -> period > 4 && margin <= 6 || period == 4 && left != null && left <= 180 && margin <= 5
            "nfl" -> period > 4 || period == 4 && left != null && left <= 300 && margin <= 8
            "nhl" -> period > 3 || period == 3 && left != null && left <= 300 && margin <= 1
            "mlb" -> period >= 8 && margin <= 2
            "soccer" -> (minute(clock) ?: 0) >= 80 && margin <= 1
            else -> false
        }
    }

    fun isClose(g: SportData.Game): Boolean =
        g.state == 1 && !g.solo && isClose(SportData.sportOf(g), g.period, g.clock, g.home.score, g.away.score)
}

/**
 * Game Day: reminders that switch the TV to the game when it starts (auto-tune), and a popup when any game
 * on today's schedule gets close in the final minutes. Runs while the app is open.
 */
object GameDay {
    /** Games with a reminder (key "league|id"). */
    val reminders = mutableStateListOf<String>()
    @Volatile var closeAlerts = true
        private set
    private var inited = false
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("fof_gameday", Context.MODE_PRIVATE)

    fun init(c: Context) {
        if (inited) return
        inited = true
        val p = prefs(c)
        closeAlerts = p.getBoolean("close", true)
        reminders.addAll((p.getString("rem", "") ?: "").split("\n").filter { it.isNotBlank() })
    }

    fun has(g: SportData.Game) = g.key in reminders
    fun toggle(c: Context, g: SportData.Game) {
        init(c)
        if (!reminders.remove(g.key)) reminders.add(g.key)
        save(c)
    }
    private fun save(c: Context) = prefs(c).edit().putString("rem", reminders.joinToString("\n")).apply()
    fun setCloseAlerts(c: Context, on: Boolean) { closeAlerts = on; prefs(c).edit().putBoolean("close", on).apply() }

    private val LEAGUES = listOf("NBA", "NFL", "College Football", "College Basketball", "NHL", "MLB", "WNBA", "MLS",
        "Premier League", "Champions League", "LaLiga")

    private fun line(g: SportData.Game): String {
        val sc = if (SportPrefs.hideScores) "" else " " + g.away.score.orEmpty() + " – " + g.home.score.orEmpty() + " "
        return g.away.short + sc + (if (sc.isEmpty()) " at " else "") + g.home.short + "  ·  " + g.detail
    }

    private suspend fun show(a: SportAlerts.Alert) {
        SportAlerts.current = a
        delay(12_000L)
        if (SportAlerts.current === a) SportAlerts.current = null
    }

    suspend fun run(c: Context) {
        init(c); SportLeagues.init(c); SportFix.init(c); SportLearn.init(c); SportPrefs.init(c)
        val alerted = HashSet<String>()
        while (true) {
            if (reminders.isNotEmpty() || closeAlerts) {
                val ymd = SportData.ymd(0, General.zone())
                val wanted = reminders.map { it.substringBefore('|') }.toSet()
                val leagues = SportData.LEAGUES.filter { it.kind == SportData.KIND_TEAM && SportLeagues.on(it) &&
                    (it.name in wanted || (closeAlerts && it.name in LEAGUES)) }
                val games = leagues.flatMap { l -> runCatching { SportData.fetch(l, ymd, 40_000) }.getOrDefault(emptyList()) }
                SportFeed.put(games)
                val now = System.currentTimeMillis() / 1000
                val due = games.any { it.key in reminders && (it.state == 1 || it.start - now <= 60) }
                val live = if (!due) emptyList() else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    PlaylistStore.channels.filter { it.kind == Kind.LIVE }.also { SportWatch.prewarm(it) }
                }
                // Reminders: at the start, switch to the game's channel (as if picked on the phone)
                for (g in games) if (g.key in reminders && (g.state == 1 || g.start - now <= 60)) {
                    reminders.remove(g.key); save(c)
                    val ch = SportWatch.channels(g, live).firstOrNull()
                    if (ch != null) {
                        ZapContext.list = SportWatch.channels(g, live); ZapContext.key = null
                        RemoteHub.playReq = RemoteHub.Play(ch.id, 0, 0)
                        show(SportAlerts.Alert("Game time", g.away.short + " at " + g.home.short + "  ·  " + ch.title))
                    } else show(SportAlerts.Alert("Game time", g.away.short + " at " + g.home.short + " is starting. No channel found: open Live sports to pick one."))
                }
                // Close games in the last minutes (once per game)
                if (closeAlerts) for (g in games) if (g.key !in alerted && GameDayLogic.isClose(g)) {
                    alerted.add(g.key)
                    show(SportAlerts.Alert("Close game", line(g)))
                }
            }
            delay(if (reminders.isNotEmpty() || closeAlerts) 45_000L else 120_000L)
        }
    }
}

