package com.ipvio.stream

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Fof sports settings (per device): hide scores, leagues of the home row. */
object SportPrefs {
    @Volatile var hideScores = false
        private set
    var version by mutableIntStateOf(0)
        private set
    private var inited = false
    private fun prefs(c: Context) = Profiles.sp(c, "fof_sport")

    fun init(c: Context) {
        if (inited) return
        inited = true
        hideScores = prefs(c).getBoolean("hide_scores", false)
        bugOff = prefs(c).getBoolean("bug_off", false)
    }

    /** Score box over a game in the player turned off (Show/Hide score button in the player). */
    var bugOff by mutableStateOf(false)
        private set
    fun setBugOff(c: Context, off: Boolean) { bugOff = off; prefs(c).edit().putBoolean("bug_off", off).apply() }

    fun setHideScores(c: Context, on: Boolean) {
        hideScores = on
        prefs(c).edit().putBoolean("hide_scores", on).apply()
        SportData.clearCache()
        version++
    }
}

/** Leagues checked for the home row "Live sports" (team sports with a day schedule). */
// Fof TV: the leagues each profile follows (taste setup / Leagues on-off), from the main pro leagues and soccer competitions
private val HOME_LEAGUES = listOf("NFL", "NBA", "WNBA", "College Football", "Premier League", "Champions League", "LaLiga", "Serie A", "Bundesliga", "Ligue 1", "MLS",
    "Europa League", "Liga MX", "Concacaf Nations League", "International Friendlies", "FA Cup", "Copa del Rey", "Club World Cup")

/**
 * Home row "Live sports": games running now (and favorite-team games starting within the hour), one click plays
 * the matching channel from the playlist (same matching as Watch Live). Without a channel the source picker opens.
 */
@Composable
fun LiveSportsRow(live: List<Item>, onOpen: (Item) -> Unit, onSports: () -> Unit) {
    val ctx = LocalContext.current
    SportFavs.init(ctx); SportLeagues.init(ctx); SportFix.init(ctx); SportLearn.init(ctx); SportPrefs.init(ctx)
    val scope = rememberCoroutineScope()
    // Resolve the on-screen games BEFORE the viewer presses OK. A big playlist must
    // never be indexed synchronously on the click path.
    val readyGames = remember(live) { mutableStateMapOf<String, List<Item>>() }
    val readyVersions = remember(live) { HashMap<String, Int>() }
    // Ignore repeated OK presses while the same match is resolving.
    var openingGame by remember { mutableStateOf<String?>(null) }
    var teamGames by remember { mutableStateOf(emptyList<SportData.Game>()) }
    val games by produceState(emptyList<SportData.Game>(), SportPrefs.version) {
        while (true) {
            val ymd = SportData.ymd(0, General.zone())
            val leagues = HOME_LEAGUES.mapNotNull { SportData.league(it) }.filter { SportLeagues.on(it) }
            val all = runCatching {
                coroutineScope { leagues.map { l -> async { runCatching { SportData.fetch(l, ymd, 8_000) }.getOrDefault(emptyList()) } }.awaitAll().flatten() }
            }.getOrDefault(emptyList())
            SportFeed.put(all)
            // Your teams today, not live: upcoming first, then final scores
            teamGames = all.filter { SportFavs.isFavGame(it) && it.state != 1 }.sortedWith(compareBy<SportData.Game> { if (it.state == 0) 0 else 1 }.thenBy { it.start })
            val now = System.currentTimeMillis() / 1000
            value = all.filter { g ->
                g.state == 1 || (g.state == 0 && SportFavs.isFavGame(g) && g.start - now in 0..3600)
            }.sortedWith(compareByDescending<SportData.Game> { GameDayLogic.isClose(it) }.thenByDescending { SportFavs.isFavGame(it) }.thenByDescending { it.state }.thenBy { it.start })
            delay(if (value.isNotEmpty()) 10_000L else 180_000L)
        }
    }
    // Live scores refresh every ten seconds. Re-running playlist-wide matching
    // on each scoreboard update causes visible card jank on memory-limited TVs.
    // Key discovery to game identity (not changing score/clock fields).
    val gameKeys = remember(games) { games.map { it.key } }
    val lookupVersion = ((SportWatch.prepVersion * 31 + SportWatch.version) * 31 + SportAddons.version) * 31 + SportFresh.version
    LaunchedEffect(Unit) { SportFresh.awaitRefresh() }
    LaunchedEffect(gameKeys, live, lookupVersion) {
        if (live.isEmpty() || games.isEmpty()) return@LaunchedEffect
        val missing = games.filter { readyVersions[it.key] != lookupVersion }
        if (missing.isEmpty()) return@LaunchedEffect
        withContext(kotlinx.coroutines.Dispatchers.Default) {
            SportWatch.prewarm(live)
            for (g in missing) {
                val channels = SportWatch.channels(g, live)
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    readyGames[g.key] = channels
                    readyVersions[g.key] = lookupVersion
                }
            }
        }
    }
    if (teamGames.isNotEmpty()) Column(Modifier.padding(top = dpx(10)), verticalArrangement = Arrangement.spacedBy(dpx(10))) {
        Text("Your teams today", color = Color.White, fontSize = spx(24), fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = RowPadX))
        LazyRow(contentPadding = PaddingValues(horizontal = RowPadX, vertical = dpx(10)), horizontalArrangement = Arrangement.spacedBy(dpx(18))) {
            items(teamGames, key = { "t" + it.key }) { g -> GameTileCard(g) { onSports() } }
        }
    }
    if (games.isEmpty()) return
    Column(Modifier.padding(top = dpx(10)), verticalArrangement = Arrangement.spacedBy(dpx(10))) {
        Text("Live sports", color = Color.White, fontSize = spx(24), fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = RowPadX))
        // Same matchup tiles as the sports area (one look everywhere)
        LazyRow(contentPadding = PaddingValues(horizontal = RowPadX, vertical = dpx(10)), horizontalArrangement = Arrangement.spacedBy(dpx(18))) {
            items(games, key = { it.key }) { g ->
                GameTileCard(g) {
                    if (openingGame != null) return@GameTileCard
                    openingGame = g.key
                    scope.launch {
                        try {
                            val channels = readyGames[g.key]?.takeIf {
                                readyVersions[g.key] == lookupVersion && it.isNotEmpty()
                            } ?: SportWatch.startupChannels(ctx.applicationContext, g, live)
                            if (channels.isNotEmpty()) {
                                SportSession.start(g, channels, live)
                                ZapContext.list = SportSession.zapList().ifEmpty { channels }
                                ZapContext.key = null
                                onOpen(SportSession.firstChannel() ?: channels.first())
                            } else {
                                openInHub(HubPage.Watch(g))
                                onSports()
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            AutoReport.issue(ctx, "sports", "home source startup failed: " + e.javaClass.simpleName)
                            openInHub(HubPage.Watch(g))
                            onSports()
                        } finally {
                            openingGame = null
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveGameCard(g: SportData.Game, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (f) focusScale(1.06f) else 1f, label = "livegame")
    val shape = RoundedCornerShape(DS.RadiusM)
    Column(
        Modifier.width(dpx(320)).graphicsLayer { scaleX = s; scaleY = s }.clip(shape)
            .background(Color(0xFF1C1C1C))
            .border(if (f) 3.dp else 0.dp, if (f) Color.White else Color.Transparent, shape)
            .onFocusChanged { f = it.isFocused }
            .trackFocus()
            .restoreFocus("game:" + g.key)
            .clickable(onClick = onClick)
            .padding(dpx(14)),
        verticalArrangement = Arrangement.spacedBy(dpx(8))
    ) {
        val ink = Color.White
        val sub = Color(0xFFA3A3A3)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(raw = true, text = g.league, color = sub, fontSize = spx(13), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
            if (g.state == 1) LiveTag()
        }
        for (t in listOf(g.away, g.home)) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(10))) {
            // Thin bar in the team color (ESPN), then a larger logo
            val tc = t.color?.let { c -> runCatching { Color(android.graphics.Color.parseColor("#" + c.removePrefix("#"))) }.getOrNull() } ?: Color(0x55FFFFFF)
            Box(Modifier.width(dpx(4)).height(dpx(34)).clip(RoundedCornerShape(dpx(2))).background(tc))
            TeamLogo(t.logo, t.abbr, dpx(38))
            Text(raw = true, text = t.short, color = ink, fontSize = spx(18), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!SportPrefs.hideScores) Text(raw = true, text = t.score.orEmpty(), color = ink, fontSize = spx(26), fontWeight = FontWeight.ExtraBold)
        }
        Text(raw = true, text = listOf(statusText(g), g.tv).filter { it.isNotBlank() }.joinToString(" · "), color = sub, fontSize = spx(13), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Settings > Sports: spoiler-free mode and where the channel matching comes from. */
@Composable
internal fun FofSportsPage(ctx: Context) {
    SportPrefs.init(ctx)
    var hide by remember { mutableStateOf(SportPrefs.hideScores) }
    ToggleRow("Hide scores (spoiler-free)", "Scores are hidden on the home screen and in the sports area until you turn this off.", hide) {
        hide = it; SportPrefs.setHideScores(ctx, it)
    }
    GameDay.init(ctx)
    var close by remember { mutableStateOf(GameDay.closeAlerts) }
    ToggleRow("Close-game alerts (Game Day)", "A popup when any game today is close in the final minutes, even while you watch something else.", close) {
        close = it; GameDay.setCloseAlerts(ctx, it)
    }
    Hint("Auto-tune: open an upcoming game in the sports area, choose Watch Live, then Auto-tune at start. When it starts, the TV switches to the game.")
    SectionTitle("How games find their channel")
    Hint("In this order: a channel you picked for the game, your usual channel for that team or network, provider event channels that name both teams, the TV guide, team channels (for example \"NBA: Indiana Pacers\" in an NBA group), and finally the network ESPN lists for the game (ESPN, TNT, FanDuel Sports Indiana and so on). Pick a channel by hand once under Watch Live and the app remembers it for that team.")
    SectionTitle("Home screen")
    Hint("The \"Live sports\" row shows games in progress and your favorite teams' games starting within the hour. It can be moved or hidden under Settings > Customize home screen.")
}
