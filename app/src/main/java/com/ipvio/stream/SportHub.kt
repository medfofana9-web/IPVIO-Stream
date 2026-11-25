package com.ipvio.stream

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shared data service of the hub: one refresh loop per league with a sport-specific interval, the newest version of every
 * game by key (pages read it from here instead of fetching again).
 */
object SportFeed {
    val latest = mutableStateMapOf<String, SportData.Game>()

    class State(val games: List<SportData.Game>, val done: Int, val total: Int, val failed: Boolean) {
        val loading: Boolean get() = done < total && games.isEmpty()
    }

    fun put(list: List<SportData.Game>) {
        if (latest.size > 4000) latest.clear()
        list.forEach { latest[it.key] = it }
    }

    /** Refresh interval per league: fast while something runs (depending on the sport), slow when nothing happens. */
    fun interval(l: SportData.League, games: List<SportData.Game>): Long {
        val now = System.currentTimeMillis() / 1000
        return when {
            // Live: every 12 s, so scores and the clock stay close to the broadcast
            games.any { it.state == 1 } -> when (l.kind) {
                SportData.KIND_GOLF, SportData.KIND_CRICKET -> 60_000L
                SportData.KIND_FIGHT -> 30_000L
                else -> 8_000L
            }
            // About to start, or past its start time but not reported live yet (ESPN switches a little late): every 20 s
            games.any { it.state == 0 && it.start - now in -1800..900 } -> 20_000L
            else -> 180_000L
        }
    }
}

/** What the tabs and pages of the hub need: page stack, channels, play, change tab. */
class HubCtx(val nav: HubNav, val live: List<Item>, private val onPlay: (Item, List<Item>) -> Unit, val goTab: (Int, String?) -> Unit) {
    /** For the addon lookup of a game (set where the hub is built). */
    var ctxApp: android.content.Context? = null
    var scopeApp: kotlinx.coroutines.CoroutineScope? = null
    private var watchJob: kotlinx.coroutines.Job? = null
    private var pendingGame: String? = null
    fun chans(g: SportData.Game): List<Item> = SportWatch.channels(g, live)
    /** Remember the open pages (Match Center, Watch Live …): Back from the player returns to exactly there. */
    fun play(ch: Item, list: List<Item>) { HubMemory.keep = nav.stack.toList(); nav.clear(); onPlay(ch, list) }
    /** Watch Live: start the best channel directly, otherwise show the Watch Live page (assign by hand). */
    fun watch(g: SportData.Game) {
        // On the first visit the playlist's channel index may still be warming.
        // Never interpret that unfinished lookup as "no stream" for a game.
        // Keep D-pad handling on the main thread and run source matching off it.
        if (watchJob?.isActive == true && pendingGame == g.key) return
        val scope = scopeApp ?: return
        watchJob?.cancel()
        pendingGame = g.key
        watchJob = scope.launch {
            val pagesAtStart = nav.stack.toList()
            try {
                val candidates = SportWatch.startupChannels(ctxApp, g, live)
                if (nav.stack.toList() != pagesAtStart) return@launch
                if (candidates.isNotEmpty()) {
                    SportSession.start(g, candidates, live)
                    play(SportSession.firstChannel() ?: candidates.first(), SportSession.zapList().ifEmpty { candidates })
                } else nav.open(HubPage.Watch(g))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ctxApp?.let { AutoReport.issue(it, "sports", "source startup failed: " + e.javaClass.simpleName) }
                if (nav.stack.toList() == pagesAtStart) nav.open(HubPage.Watch(g))
            }
        }
    }
    /** A channel picked on the Watch Live page: same session, starting on that channel. */
    fun watchOn(g: SportData.Game, ch: Item, c: List<Item>) {
        SportSession.start(g, listOf(ch) + c.filter { it.id != ch.id }, live)
        play(ch, SportSession.zapList().ifEmpty { c })
    }
    fun open(g: SportData.Game) = nav.open(HubPage.Match(g))
}

/** Opens [p] (a game, team or league page) the next time the sports area is shown, e.g. from the search. */
internal fun openInHub(p: HubPage) { HubMemory.keep = listOf(p) }

/** Tab and filters survive leaving the hub (e.g. after Watch Live and back from the player). */
private object HubMemory {
    /** Pages that were open when a live stream started (restored when the hub comes back). */
    var keep: List<HubPage> = emptyList()
    var tab = 0
    var sport: String? = null
    var day = 0
}

private val HUB_TABS = listOf("Übersicht", "Live", "Spielplan", "Ligen", "Teams", "Favoriten", "Sender")

/**
 * Sports Hub: Overview, Live, Schedule, Leagues, Teams, Favorites and the channel area (previous sports page).
 * Match Center, League Center, Team Center, players and Watch Live open as pages above the tabs; Back closes them.
 */
@Composable
fun SportHub(live: List<Item>, onPlay: (Item, List<Item>) -> Unit) {
    SideEffect { ContentFocus.tab = MenuLogic.SPORT }
    LaunchedEffect(Unit) { MainSampler.mark("sports: opened") }
    val ctx = LocalContext.current
    SportFavs.init(ctx)
    SportLeagues.init(ctx)
    SportHubFavs.init(ctx)
    SportFix.init(ctx)
    SportLearn.init(ctx)
    var tab by remember { mutableIntStateOf(SportTabState.restore(HubMemory.tab, HUB_TABS.size)) }
    var sport by remember { mutableStateOf(HubMemory.sport) }
    var retry by remember { mutableIntStateOf(0) }
    var scoreDlg by remember { mutableStateOf(false) }
    if (scoreDlg) ScoreOverlayDialog { scoreDlg = false }
    val nav = remember { HubNav().also { n -> HubMemory.keep.forEach { n.open(it) }; HubMemory.keep = emptyList() } }
    val hubScope = rememberCoroutineScope()
    val hub = remember(live, nav) {
        HubCtx(nav, live, onPlay) { t, s -> tab = t; HubMemory.tab = t; if (s != null || t == 1) { sport = s; HubMemory.sport = s } }
            .also { it.ctxApp = ctx.applicationContext; it.scopeApp = hubScope }
    }

    // Today's games of all active leagues, each league with its own refresh interval
    val leaguesKey = SportLeagues.off.joinToString("|")
    val feed by produceState(SportFeed.State(emptyList(), 0, 0, false), leaguesKey, General.sportLive, retry) {
        if (!General.sportLive) { value = SportFeed.State(emptyList(), 0, 0, false); return@produceState }
        val ls = SportLeagues.enabled()
        val m = HashMap<String, List<SportData.Game>>()
        // Opening the hub again: the last loaded games appear at once (no "Loading games" wait), fresh ones replace them as they arrive
        val today = SportData.ymd(0, General.zone())
        // Memory first, else the answer saved on the device (after a restart or an update)
        withContext(Dispatchers.IO) { for (l in ls) (SportData.peek(l, today) ?: SportData.peekDisk(l, today))?.let { m[l.path] = it } }
        val cached = m.values.flatten()
        // Only answers from this visit count as "done": an old empty cache must not show "No games today" while live games load
        val fresh = HashSet<String>()
        value = SportFeed.State(cached, 0, ls.size, false)
        coroutineScope {
            // With dozens of leagues, publishing after every single answer rebuilt the page dozens of times in the first
            // seconds (the stutter on opening Sports). Answers are collected and published together, at most every 400 ms.
            val pending = ArrayList<SportData.Game>()
            var dirty = false
            fun publish() {
                if (pending.isNotEmpty()) { SportFeed.put(pending.toList()); pending.clear() }
                val all = m.values.flatten()
                value = SportFeed.State(all, fresh.size, ls.size, fresh.size == ls.size && all.isEmpty() && !SportData.lastOk)
                dirty = false
            }
            // Score updates redraw the whole page: wait for a pause in remote presses (at most 3 s late)
            launch { var held = 0; while (true) { delay(400); if (dirty) { if (Busy.active() && held < 7) held++ else { held = 0; publish() } } } }
            val sigs = java.util.concurrent.ConcurrentHashMap<String, Int>()
            // Leagues that can have a game on right now (by the saved schedule, or never loaded) and the top leagues are asked
            // first; the rest waits until they answered (at most 4 s), so live games are not queued behind 60 quiet leagues
            val nowS = System.currentTimeMillis() / 1000
            val first = ls.filter { l ->
                val c = m[l.path]
                c == null || l.name in SportData.POPULAR.take(10) ||
                    c.any { it.state == 1 || (it.state == 0 && it.start in (nowS - 5 * 3600)..(nowS + 20 * 60)) }
            }.map { it.path }.toSet()
            val firstLeft = java.util.concurrent.atomic.AtomicInteger(first.size)
            val firstDone = kotlinx.coroutines.CompletableDeferred<Unit>()
            if (first.isEmpty()) firstDone.complete(Unit)
            ls.forEach { l ->
                launch {
                    if (l.path !in first) withTimeoutOrNull(4_000) { firstDone.await() }
                    var once = l.path in first
                    while (true) {
                        val r = SportData.fetch(l, SportData.ymd(0, General.zone()), 8_000)
                        // Nothing changed in this league (same games, states, scores, clocks): no redraw of the page
                        val sig = r.fold(r.size) { h, x -> 31 * h + (x.key + x.state + x.away.score + x.home.score + x.detail).hashCode() }
                        val changed = sigs.put(l.path, sig) != sig
                        m[l.path] = r
                        fresh.add(l.path)
                        if (changed) { pending.addAll(r); dirty = true }
                        if (once) { once = false; if (firstLeft.decrementAndGet() == 0) { firstDone.complete(Unit); publish(); MainSampler.mark("sports: likely-live leagues answered") } }
                        // The last league answered: show the full picture at once
                        if (fresh.size == ls.size && value.done < ls.size) publish()
                        delay(SportFeed.interval(l, r))
                    }
                }
            }
        }
    }
    val now = System.currentTimeMillis() / 1000
    val warmGames = feed.games.asSequence()
        .filter { it.state == 1 || (it.state == 0 && it.start in now..(now + 10 * 60)) }
        .distinctBy { it.key }
        .sortedWith(compareByDescending<SportData.Game> { SportFavs.isFavGame(it) }.thenByDescending { it.state }.thenBy { it.start })
        .take(4).toList()
    LaunchedEffect(warmGames.map { it.key to it.state }) {
        if (warmGames.isEmpty()) return@LaunchedEffect
        while (true) {
            for (game in warmGames) {
                Busy.quiet()
                withTimeoutOrNull(4_000L) { SportAddons.find(ctx, game) }
            }
            delay(30_000L)
        }
    }
    // Today's channel names from the provider while the sports area is open (every 10 minutes)
    LaunchedEffect(Unit) {
        while (true) { runCatching { SportFresh.refresh() }; delay(10 * 60_000L) }
    }
    // Sports shows of the TV guide for Watch Live (once a minute, off the main thread)
    LaunchedEffect(live, Xmltv.channelCount) {
        while (true) {
            Busy.quiet()
            val s = withContext(Dispatchers.Default) {
                SportLogic.matchSlots(live, { Xmltv.forChannel(it.tvgId) }, System.currentTimeMillis() / 1000, SportPlan.HORIZON_SEC)
            }
            SportWatch.setSlots(s)
            delay(60_000)
        }
    }

    val sportReq = remember { FocusRequester() }
    Column(Modifier.fillMaxSize()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = DS.PageX, vertical = dpx(10)),
            horizontalArrangement = Arrangement.spacedBy(dpx(10))
        ) {
            // Four tabs: Overview, Live, Schedule, Leagues (Teams, Favorites and Channels are still reached from inside)
            itemsIndexed(HUB_TABS.take(4)) { i, t ->
                // The chosen tab is where the focus lands when coming from the sidebar (the channel tab has its own target)
                val base = if (i == tab && tab != 6) Modifier.focusRequester(ContentFocus.req) else Modifier.trackFocus()
                // Live tab: Down goes to the sport filter first (not to the competition row below it)
                FilterPill(t, tab == i, if (tab == 1) base.focusProperties { down = sportReq } else base) {
                    tab = i; HubMemory.tab = i
                }
            }
            // Score box setting at the end of the bar (not first)
            item(key = "scoreov") { FilterPill("Score box", SportAlerts.miniOn, aicon = AIcon.Live, raw = true) { scoreDlg = true } }
            // Daily game plan: how many of today's games already have a channel
            if (SportPlan.total > 0) item(key = "plan") {
                Text(raw = true, text = "Today: " + SportPlan.ready + " of " + SportPlan.total + " games ready to watch", color = Muted, fontSize = spx(14),
                    modifier = Modifier.padding(start = dpx(8), top = dpx(10)))
            }
        }
        // Content ends at the tab bar: rows and enlarged tiles never slide over the tabs
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
        if (!General.sportLive && tab != 6) {
            HubEmpty(AIcon.Live, "Live-Ergebnisse sind aus", "Ergebnisse, Tabellen und Spielpläne kommen von ESPN (inoffizielle Quelle). Schalte sie ein, um den Sports Hub zu nutzen.",
                "Einschalten") { General.setSportLive(ctx, true) }
        } else when (tab) {
            0 -> HubOverview(feed, hub) { retry++ }
            1 -> HubLive(feed, hub, sport, sportReq) { sport = it; HubMemory.sport = it }
            2 -> HubSchedule(hub, sport) { sport = it; HubMemory.sport = it }
            3 -> HubLeagues(hub)
            4 -> HubTeams(hub)
            5 -> HubFavorites(feed, hub)
            else -> SportTab(live, onPlay)
        }
        }
    }
    HubFocusRestore(nav)
    // Back on Live, Schedule, Leagues … (e.g. after picking Basketball) returns to the sports overview first
    androidx.activity.compose.BackHandler(enabled = nav.stack.isEmpty() && tab != 0) {
        tab = 0; HubMemory.tab = 0; sport = null; HubMemory.sport = null
        runCatching { ContentFocus.req.requestFocus() }
    }
    nav.stack.forEachIndexed { i, p ->
        key(i, p) {
            when (p) {
                is HubPage.Match -> MatchCenterPage(p.g, hub)
                is HubPage.League -> LeagueCenterPage(p.l, hub)
                is HubPage.Team -> TeamCenterPage(p.l, p.t, hub)
                is HubPage.Player -> SportPlayerPage(p.l, p.id, p.name, hub)
                is HubPage.Watch -> WatchPage(p.g, hub)
            }
        }
    }
}

private fun current(list: List<SportData.Game>): List<SportData.Game> = list.map { SportFeed.latest[it.key] ?: it }

private fun popularity(g: SportData.Game): Int = SportData.POPULAR.indexOf(g.league).let { if (it < 0) 99 else it }

/**
 * Featured game: prefer one the user can actually watch (own channel found): live favorite, live top competition,
 * then the next favorite or top game; without any channel match the same order over all games, at last the latest result.
 */
private fun featured(games: List<SportData.Game>, hasCh: (SportData.Game) -> Boolean): SportData.Game? {
    fun pick(live: List<SportData.Game>, up: List<SportData.Game>): SportData.Game? =
        live.firstOrNull { SportHubFavs.isFavGame(it) } ?: live.minByOrNull { popularity(it) }
            ?: up.firstOrNull { SportHubFavs.isFavGame(it) } ?: up.minByOrNull { popularity(it) * 100_000L + (it.start / 60) % 100_000L }
    val live = games.filter { it.state == 1 && !it.solo }
    val up = games.filter { it.state == 0 && statusKind(it) == 0 && !it.solo }.sortedBy { it.start }
    return pick(live.filter(hasCh), up.filter(hasCh))
        ?: pick(live, up)
        ?: games.filter { it.state == 1 }.firstOrNull()
        ?: games.filter { it.state == 2 && !it.solo }.maxByOrNull { it.start }
}

/** Everything the overview shows, computed off the main thread (filters, sorting and the channel match per game are too slow in composition). */
private class HubOverviewModel(
    val games: List<SportData.Game>,
    val liveMine: List<SportData.Game>,
    val upcoming: List<SportData.Game>,
    val finals: List<SportData.Game>,
    val top: SportData.Game?,
    val withCh: Set<String>,
    val popular: List<SportData.League>,
    val newsLeagues: List<SportData.League>,
    val counts: Map<String, Int>
)

private fun buildOverview(raw: List<SportData.Game>, hub: HubCtx, matchChannels: Boolean = true): HubOverviewModel {
    val games = current(raw)
    val hasCh = HashMap<String, Boolean>()
    // Matching games to channels needs the playlist index (seconds on a big playlist the first time): the first pass skips it
    fun ch(g: SportData.Game): Boolean = matchChannels && hasCh.getOrPut(g.key) { hub.chans(g).isNotEmpty() }
    val liveG = games.filter { it.state == 1 }.sortedWith(compareBy({ if (SportHubFavs.isFavGame(it)) 0 else 1 }, { popularity(it) }))
    val liveMine = liveG.filter { ch(it) }
    val upcoming = games.filter { it.state == 0 && statusKind(it) == 0 }.sortedBy { it.start }
    upcoming.take(30).forEach { ch(it) }
    val finals = games.filter { it.state == 2 }.sortedByDescending { it.start }
    val top = featured(games) { ch(it) }
    val popular = SportData.POPULAR.mapNotNull { SportData.league(it) }.filter { SportLeagues.on(it) }
    val newsLeagues = (popular.filter { p -> games.any { it.league == p.name } } + games.mapNotNull { SportData.leagueOf(it) }).distinctBy { it.path }
    val counts = games.groupingBy { SportData.sportOf(it) }.eachCount()
    return HubOverviewModel(games, liveMine, upcoming, finals, top, hasCh.filterValues { it }.keys, popular, newsLeagues, counts)
}

@Composable
private fun HubOverview(feed: SportFeed.State, hub: HubCtx, onRetry: () -> Unit) {
    val ctx = LocalContext.current
    val model by produceState<HubOverviewModel?>(null, feed.games) {
        // Games at once, then the same page with the channel matches (which channel shows which game)
        value = withContext(Dispatchers.Default) { buildOverview(feed.games, hub, matchChannels = false) }
        // The channel match (slow on a big playlist) waits until the leagues stop answering for a moment: while answers
        // arrive every 400 ms it was started and thrown away again and again, taking the CPU the page needs to show up
        delay(1_200)
        value = withContext(Dispatchers.Default) { buildOverview(feed.games, hub) }
    }
    val m = model
    if (m == null) { HubLoading("Lade Spiele …"); return }
    val games = m.games
    val liveMine = m.liveMine
    val upcoming = m.upcoming
    val finals = m.finals
    val top = m.top
    val popular = m.popular
    val newsLeagues = m.newsLeagues
    // Fof TV (Nuvio style): wide game tiles in rows – My teams, Live now, then one row per sport
    // The coming week for the soccer, basketball and football sections; asked a moment later, so today's leagues (live
    // games) get the network first
    val ahead by produceState(SportAhead.cached(), SportLeagues.off.joinToString("|")) {
        if (value.soccer.isEmpty()) delay(1_500)
        value = SportAhead.load()
    }
    fun fresh(l: List<SportData.Game>) = l.map { SportFeed.latest[it.key] ?: it }.filter { it.state != 2 && statusKind(it) != 6 }
        .sortedWith(compareBy({ if (it.state == 1) 0 else 1 }, { if (SportHubFavs.isFavGame(it)) 0 else 1 }, { it.start }))
    // Live games arrive after the cached rows: the list keeps its place when rows are added above, so the new Live now row
    // ended up above the screen. When the page has not been scrolled yet, it goes back to the top to show them.
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // The page always opens at the top (Live now): rows arriving above the visible one (live games, the week ahead) made
    // the list keep its place further down. Until you move into the list yourself, every update returns it to the top.
    var inList by remember { mutableStateOf(false) }
    val liveKeys = games.filter { it.state == 1 }.size
    LaunchedEffect(liveKeys, games.size, ahead.soccer.size, ahead.nba.size, ahead.football.size) {
        if (!inList && (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0)) runCatching { listState.scrollToItem(0) }
    }
    LazyColumn(state = listState, modifier = Modifier.onFocusChanged { inList = it.hasFocus }, contentPadding = PaddingValues(horizontal = DS.PageX, vertical = dpx(8)), verticalArrangement = Arrangement.spacedBy(dpx(14))) {
        when {
            // feed.games too: the page model can lag a moment behind the feed, and must never show "no games" meanwhile
            games.isNotEmpty() || feed.games.isNotEmpty() || ahead.soccer.isNotEmpty() || ahead.nba.isNotEmpty() || ahead.football.isNotEmpty() -> {}
            // Still waiting for leagues: never say "no games" before every league has answered
            feed.loading || feed.done < feed.total -> item(key = "load") { HubLoading("Lade Spiele …") }
            feed.failed -> item(key = "err") {
                HubEmpty(AIcon.Warning, "Daten konnten nicht geladen werden", "ESPN antwortet gerade nicht. Prüfe die Internetverbindung und versuche es erneut.", "Erneut versuchen") { onRetry() }
            }
            else -> item(key = "empty") { HubEmpty(AIcon.Info, "Heute keine Spiele gefunden.", "In deinen Ligen ist heute nichts angesetzt. Im Spielplan siehst du die nächsten Tage.", "Spielplan öffnen") { hub.goTab(2, null) } }
        }
        // Same as the Mac app: a plain "Sports" title, then Live Now, Coming Up and Final as grids of game cards
        item(key = "gameday-masthead") {
            Text("Sports", raw = true, color = Color.White, fontSize = spx(40), fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = dpx(14)))
        }
        val nowS = System.currentTimeMillis() / 1000
        val liveAll = games.filter { it.state == 1 }.sortedWith(compareBy({ if (it.solo) 1 else 0 }, { if (it.key in m.withCh) 0 else 1 }, { if (SportHubFavs.isFavGame(it)) 0 else 1 }, { popularity(it) }))
        val comingUp = upcoming.filter { it.start >= nowS - 3 * 3600 }.sortedWith(compareBy({ it.start }, { if (SportHubFavs.isFavGame(it)) 0 else 1 })).take(40)
        val mine = (games + ahead.soccer + ahead.nba + ahead.football).distinctBy { it.key }
            .filter { SportFavs.isFavGame(it) && it.state != 2 }.sortedBy { if (it.state == 1) 0L else it.start }
        fun grid(key: String, title: String, l: List<SportData.Game>) {
            if (l.isEmpty()) return
            item(key = key + "_h") {
                if (key == "live") LaunchedEffect(Unit) { MainSampler.mark("sports: live row shown") }
                Box(Modifier.padding(top = dpx(16))) { HubSectionTitle(title, raw = true) }
            }
            // One row of cards per list item, so long lists stay lazy
            l.chunked(4).forEachIndexed { r, row ->
                item(key = key + "_" + r) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(dpx(14))) {
                        row.forEach { g -> MacGameCard(g, hub, Modifier.weight(1f)) }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        // The day's biggest games in team colors (live first, then by how popular the league is)
        val top10 = games.filter { it.state != 2 && !it.solo && statusKind(it) != 6 }
            .sortedWith(compareBy({ if (it.state == 1) 0 else 1 }, { if (SportHubFavs.isFavGame(it)) 0 else 1 }, { popularity(it) })).take(10)
        if (top10.size >= 3) item(key = "top10") { Box(Modifier.padding(top = dpx(8))) { GameTileRow("Top 10 Games Today", top10, hub, null) } }
        grid("live", "Live Now", liveAll)
        if (mine.isNotEmpty()) item(key = "myteams") { GameTileRow("My teams", mine.take(12), hub, null) }
        grid("soon", "Coming Up", comingUp)
        grid("final", "Final", finals.take(24))
        if (!SportData.lastOk && games.isNotEmpty()) item(key = "stale") {
            Text("Hinweis: Die letzte Aktualisierung hat nicht geklappt. Angezeigt wird der letzte Stand.", color = Muted, fontSize = spx(14))
        }
    }
}

/** Big panel of the featured game: competition, teams with logos, score or start time, Watch Live and Match Center. */
@Composable
private fun FeaturedPanel(g: SportData.Game, hub: HubCtx) {
    val shape = RoundedCornerShape(DS.RadiusL)
    // Channel matching can be expensive on giant lists; never block remote navigation on the UI thread.
    val chans by produceState(emptyList<Item>(), g.key, hub.live, SportWatch.prepVersion, SportWatch.version, SportAddons.version, SportFresh.version) {
        value = withContext(Dispatchers.Default) { SportWatch.prewarm(hub.live); hub.chans(g) }
    }
    val l = SportData.leagueOf(g)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(featuredBrush(g))
            .border(1.dp, FofVisual.GlassBorder, shape).padding(dpx(26)),
        verticalArrangement = Arrangement.spacedBy(dpx(15))
    ) {
        FofEyebrow(if (g.state == 1) "LIVE FEATURE" else "FEATURED MATCH", compact = true)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(10))) {
            if (l != null) TeamLogo(SportData.leagueLogos[l.path], "", dpx(30))
            Text(raw = true, text = listOf(g.groupName, g.note).filter { it.isNotBlank() }.distinct().joinToString(" · "), color = Tok.TextHi, fontSize = spx(18),
                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (SportHubFavs.isFavGame(g)) AppIcon(AIcon.Star, DS.Gold, size = dpx(22))
        }
        MatchHeader(g, 110)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(dpx(14), Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            WatchLiveButton(chans, live = g.state == 1) { hub.watch(g) }
            HeroButton("Match Center", AIcon.Info, false, Modifier, 46) { hub.open(g) }
        }
    }
}

private fun sportMatch(g: SportData.Game, sport: String?) = sport == null || SportData.sportOf(g) == sport

/** "Live now", "Starts in 2h 14m", "Tomorrow" for the My Teams row. */
private fun countdown(g: SportData.Game): String {
    if (g.state == 1) return "Live now  ·  OK to watch"
    val s = g.start - System.currentTimeMillis() / 1000
    return when {
        s <= 0 -> "Starting"
        s < 3600 -> "Starts in " + (s / 60) + " min"
        s < 86400 -> "Starts in " + (s / 3600) + "h " + (s % 3600 / 60) + "m"
        else -> "In " + (s / 86400) + (if (s / 86400 == 1L) " day" else " days")
    }
}

/** Games grouped by competition: header with logo, LIVE marker and count, then one row per game. */
private fun LazyListScope.groupedGames(games: List<SportData.Game>, hub: HubCtx, timeFirst: Boolean) {
    games.groupBy { it.groupName }.forEach { (name, list) ->
        val l = SportData.leagueOf(list.first())
        item(key = "h$name") { CompetitionHeader(name, l?.let { SportData.leagueLogos[it.path] }, list.count { it.state == 1 }, list.size) }
        items(list, key = { "g" + it.key }) { g -> val ch = hub.chans(g).firstOrNull(); MatchRow(g, ch != null, timeFirst, ch?.let { SportNet.stripPrefix(it.title) }) { hub.open(g) } }
    }
}

/** Live: all running events, grouped by competition, with sport, competition and favorite filters. */
@Composable
private fun HubLive(feed: SportFeed.State, hub: HubCtx, sport: String?, sportReq: FocusRequester, onSport: (String?) -> Unit) {
    val compReq = remember { FocusRequester() }
    var comp by remember { mutableStateOf<String?>(null) }
    var favOnly by remember { mutableStateOf(false) }
    val games = current(feed.games)
    val liveG = games.filter { it.state == 1 }
    val counts = liveG.groupingBy { SportData.sportOf(it) }.eachCount()
    val inSport = liveG.filter { sportMatch(it, sport) }
    val comps = inSport.map { it.groupName }.distinct().sortedBy { n -> inSport.firstOrNull { it.groupName == n }?.let { popularity(it) } ?: 99 }
    val c = comp?.takeIf { it in comps }
    val shown = inSport.filter { (c == null || it.groupName == c) && (!favOnly || SportHubFavs.isFavGame(it)) }
        .sortedWith(compareBy({ if (SportHubFavs.isFavGame(it)) 0 else 1 }, { popularity(it) }, { it.groupName }, { it.start }))
    Column(Modifier.fillMaxSize()) {
        SportFilter(sport, counts, sportReq, compReq) { onSport(it); comp = null }
        // Fof TV: no row of competition chips (the list is grouped by competition already), only "Favorites only"
        var multi by remember { mutableStateOf<List<Item>?>(null) }
        LazyRow(contentPadding = PaddingValues(horizontal = DS.PageX, vertical = dpx(4)), horizontalArrangement = Arrangement.spacedBy(dpx(10))) {
            item(key = "fav") { FilterPill("Nur Favoriten", favOnly, Modifier.trackFocus(compReq).focusProperties { up = sportReq }, aicon = if (favOnly) AIcon.Star else AIcon.StarOutline) { favOnly = !favOnly } }
            // Fof TV: up to four live games at once, the first channel of each
            if (shown.size >= 2) item(key = "multi") {
                FilterPill("Multiview", false, Modifier.trackFocus().focusProperties { up = sportReq }, aicon = AIcon.Grid) {
                    multi = shown.mapNotNull { hub.chans(it).firstOrNull() }.distinctBy { it.id }.take(4)
                }
            }
        }
        multi?.let { m -> MultiViewScreen(hub.live, start = m) { multi = null } }
        LazyColumn(contentPadding = PaddingValues(horizontal = DS.PageX, vertical = dpx(8)), verticalArrangement = Arrangement.spacedBy(dpx(4))) {
            when {
                feed.loading -> item(key = "load") { HubLoading("Lade Spiele …") }
                shown.isEmpty() -> {
                    item(key = "empty") {
                        HubEmpty(AIcon.Live, if (liveG.isEmpty()) "Gerade läuft nichts" else "Keine Spiele für diese Auswahl.",
                            if (liveG.isEmpty()) "Sobald ein Spiel deiner Ligen beginnt, erscheint es hier." else "Ändere die Filter oder schau in den Spielplan.",
                            "Spielplan öffnen") { hub.goTab(2, sport) }
                    }
                    val next = games.filter { it.state == 0 && statusKind(it) == 0 && sportMatch(it, sport) }.sortedBy { it.start }.take(10)
                    if (next.isNotEmpty()) {
                        item(key = "nt") { HubSectionTitle("Als Nächstes") }
                        items(next, key = { "n" + it.key }) { g -> val ch = hub.chans(g).firstOrNull(); MatchRow(g, ch != null, timeFirst = true, chanName = ch?.let { SportNet.stripPrefix(it.title) }) { hub.open(g) } }
                    }
                }
                else -> groupedGames(shown, hub, false)
            }
        }
    }
}

/** Schedule: date bar (7 days back, 14 ahead), sport filter, games of the day grouped by competition. */
@Composable
private fun HubSchedule(hub: HubCtx, sport: String?, onSport: (String?) -> Unit) {
    var day by remember { mutableIntStateOf(HubMemory.day) }
    var retry by remember { mutableIntStateOf(0) }
    val leaguesKey = SportLeagues.off.joinToString("|")
    val games by produceState<List<SportData.Game>?>(null, day, leaguesKey, retry) {
        value = null
        val ymd = SportData.ymd(day, General.zone())
        val r = coroutineScope {
            SportLeagues.enabled().map { l -> async { SportData.fetch(l, ymd, if (day == 0) 40_000 else 300_000) } }.awaitAll()
        }.flatten().distinctBy { it.key }
        SportFeed.put(r)
        value = r
    }
    val list = current(games.orEmpty())
    val counts = list.groupingBy { SportData.sportOf(it) }.eachCount()
    val shown = list.filter { sportMatch(it, sport) }.sortedWith(compareBy({ popularity(it) }, { it.groupName }, { it.start }))
    Column(Modifier.fillMaxSize()) {
        DateSelector(day, -7..14) { day = it; HubMemory.day = it }
        Spacer(Modifier.height(dpx(6)))
        SportFilter(sport, counts) { onSport(it) }
        LazyColumn(contentPadding = PaddingValues(horizontal = DS.PageX, vertical = dpx(8)), verticalArrangement = Arrangement.spacedBy(dpx(4))) {
            when {
                games == null -> item(key = "load") { HubLoading("Lade Spiele …") }
                list.isEmpty() && !SportData.lastOk -> item(key = "err") {
                    HubEmpty(AIcon.Warning, "Daten konnten nicht geladen werden", "ESPN antwortet gerade nicht. Bitte versuche es erneut.", "Erneut versuchen") { retry++ }
                }
                shown.isEmpty() -> item(key = "empty") {
                    HubEmpty(AIcon.Info, "Keine Spiele für diese Auswahl.", "An diesem Tag ist in deinen Ligen nichts angesetzt. Tennis, Formel 1, UFC, Golf und Cricket zeigt ESPN nur für die laufenden Turniere und Wochenenden.")
                }
                else -> groupedGames(shown, hub, true)
            }
        }
    }
}

/** The sports channels Fof TV shows: the ESPN networks and NBA TV (no pay-per-view or ESPN+ event feeds), US first. */
internal object SportChannels {
    private val ESPN = Regex("""\bespn(\s?[2u]|news|\s?deportes)?\b""", RegexOption.IGNORE_CASE)
    private val NBATV = Regex("""\bnba\s?tv\b""", RegexOption.IGNORE_CASE)
    private val SKIP = Regex("""espn\s?\+|espn\s?plus|\bppv\b|\bevent\b""", RegexOption.IGNORE_CASE)
    private val US = Regex("""(^|[^a-z])(us|usa)([^a-z]|$)""", RegexOption.IGNORE_CASE)

    fun wanted(i: Item): Boolean = i.kind == Kind.LIVE && !SKIP.containsMatchIn(i.title) && (ESPN.containsMatchIn(i.title) || NBATV.containsMatchIn(i.title))

    /** US channels first, then ESPN, ESPN2, ESPNU, ESPNews, Deportes, NBA TV; HD before SD. */
    fun rank(i: Item): Int {
        val t = i.title.lowercase()
        val us = if (US.containsMatchIn(i.title) || US.containsMatchIn(i.group)) 0 else 100
        val which = when {
            NBATV.containsMatchIn(t) -> 6
            Regex("""espn\s?2""").containsMatchIn(t) -> 2
            Regex("""espn\s?u\b""").containsMatchIn(t) -> 3
            "news" in t -> 4
            "deportes" in t -> 5
            else -> 1
        }
        return us + which * 10 + (if ("hd" in t || "fhd" in t) 0 else 1)
    }
}

/** Calendar day of a start time (seconds) in the device's time zone, for sorting. */
private fun localDay(startSec: Long): Long = (startSec * 1000 + java.util.TimeZone.getDefault().getOffset(startSec * 1000)) / 86_400_000L
