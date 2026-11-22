package com.ipvio.stream

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Daily game plan: while the app is on (any screen, not only Sports), today's and tomorrow's games of the switched-on
 * leagues are matched to channels ahead of time, so Watch Live is instant and the schedule shows where each game is on.
 *
 * Every round (soon after start, then every 15 minutes): reload the TV guide when it is older than 12 hours, rebuild
 * the guide slots for the next 30 hours, match every upcoming or live game, ask the addons for games within 3 hours,
 * and report games starting within 2 hours that still have no channel (so they can be fixed before kick-off).
 */
object SportPlan {
    /** How far ahead guide shows are matched to games. */
    const val HORIZON_SEC = 30 * 3600L
    private const val ROUND_MS = 15 * 60_000L
    private const val GUIDE_MAX_AGE_MS = 12 * 3600_000L

    /** The live channel list the home screen shows (the same list object the Sports hub gets, so match caches are shared). */
    @Volatile var live: List<Item> = emptyList()
        set(v) { if (v !== field) { field = v; if (v.isNotEmpty()) kick() } }

    /** Asks for a round now (channels loaded, app back in front); several requests in a row become one round. */
    private val kicks = Channel<Unit>(Channel.CONFLATED)
    private const val MIN_GAP_MS = 60_000L
    @Volatile private var lastRound = 0L
    fun kick() { kicks.trySend(Unit) }

    /** Today's plan: games still to come or live, and how many of them have a channel ready. */
    var total by mutableIntStateOf(0)
        private set
    var ready by mutableIntStateOf(0)
        private set

    suspend fun run(c: Context) = withContext(Dispatchers.Default) {
        SportLeagues.init(c); SportFix.init(c); SportLearn.init(c); SportFavs.init(c)
        // First round as soon as the channels are there, then every 15 minutes or right away when asked (app back in front)
        while (true) {
            withTimeoutOrNull(ROUND_MS) { kicks.receive() }
            val now = System.currentTimeMillis()
            if (live.isEmpty() || now - lastRound < MIN_GAP_MS) continue
            lastRound = now
            runCatching { round(c) }
        }
    }

    private suspend fun round(c: Context) {
        val chans = live
        if (chans.isEmpty() || !General.sportLive) return
        val nowMs = System.currentTimeMillis()
        // TV guide: reload once it is half a day old (a TV left on for days otherwise keeps yesterday's guide)
        if (Xmltv.loadedAt > 0 && nowMs - Xmltv.loadedAt > GUIDE_MAX_AGE_MS) {
            Xmltv.start(c, PlaylistStore.epgUrls, PlaylistStore.channels)
        }
        val zone = General.zone()
        val leagues = SportData.LEAGUES.filter { it.kind == SportData.KIND_TEAM && SportLeagues.on(it) }
        val days = listOf(SportData.ymd(0, zone), SportData.ymd(1, zone))
        val games = leagues.flatMap { l -> days.flatMap { d -> runCatching { SportData.fetch(l, d, 10 * 60_000L) }.getOrDefault(emptyList()) } }
            .distinctBy { it.key }
        withContext(Dispatchers.Main) { SportFeed.put(games) }
        SportWatch.prewarm(chans)
        val now = nowMs / 1000
        SportWatch.setSlots(SportLogic.matchSlots(chans, { Xmltv.forChannel(it.tvgId) }, now, HORIZON_SEC))
        val upcoming = games.filter { it.state == 1 || (it.state == 0 && it.start - now in -3600..HORIZON_SEC) }
            .sortedBy { it.start }
        val missing = ArrayList<SportData.Game>()
        for (g in upcoming) if (SportWatch.channels(g, chans).isEmpty()) missing.add(g)
        // Addons for the games that are close and still have no channel (a few per round, each with a time limit)
        for (g in missing.filter { it.state == 1 || it.start - now <= 3 * 3600 }.take(12)) {
            withTimeoutOrNull(5_000L) { SportAddons.find(c, g) }
        }
        val todayEnd = SportData.dayRange(days[0], zone).second
        val today = upcoming.filter { it.start <= todayEnd }
        val readyNow = today.count { SportWatch.channels(it, chans).isNotEmpty() }
        // Reported only where a channel is expected: your teams' games and the big US leagues (not every European match
        // the playlist does not carry – those filled the reports with noise)
        val major = setOf("NBA", "NFL", "NHL", "MLB", "WNBA", "NCAAF", "NCAAM", "College Football", "College Basketball")
        for (g in missing) if (g.state == 0 && g.start - now in 0..2 * 3600 && SportWatch.channels(g, chans).isEmpty() &&
            (SportFavs.isFavGame(g) || g.league in major)) {
            AutoReport.issue(c, "sports", "no channel ahead: " + g.league + " – " + (if (g.solo) g.title else g.away.name + " at " + g.home.name))
        }
        withContext(Dispatchers.Main) { total = today.size; ready = readyNow }
    }
}
