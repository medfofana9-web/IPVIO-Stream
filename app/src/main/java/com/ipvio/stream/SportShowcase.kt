package com.ipvio.stream

import androidx.compose.ui.draw.drawWithCache
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

/**
 * Fof TV sports showcase (Nuvio style): wide game tiles in the two team colors with big logos and "VS"
 * (or the score), a LIVE badge and the start time; the title line and time sit under the tile.
 * Rows: Live now, then one row per sport.
 */
internal object SportShowcase {
    /** English row title per sport key. */
    fun title(sport: String): String = when (sport) {
        "soccer" -> "Soccer"; "basketball" -> "Basketball"; "nfl" -> "Football"; "nhl" -> "Hockey"
        "tennis" -> "Tennis"; "f1" -> "Formula 1"; "mlb" -> "Baseball"; "ufc" -> "UFC / MMA"
        "cricket" -> "Cricket"; "golf" -> "Golf"; else -> sport.replaceFirstChar { it.uppercase() }
    }

    fun time(sec: Long): String = runCatching {
        val f = java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.US)
        f.timeZone = General.zone()
        f.format(java.util.Date(sec * 1000))
    }.getOrDefault("")

    fun line(g: SportData.Game): String =
        if (g.solo) g.title.ifBlank { g.league } else g.away.short.ifBlank { g.away.name } + " vs " + g.home.short.ifBlank { g.home.name }

    fun sub(g: SportData.Game): String = when (g.state) {
        1 -> g.detail.ifBlank { "Live" }
        2 -> "Final" + (if (g.detail.isNotBlank() && g.detail != "Final") " · " + g.detail else "")
        else -> time(g.start)
    }
}

/** Darkened team color, so white text stays readable on any color. */
private fun deep(c: Color, f: Float = 0.62f) = Color(c.red * f, c.green * f, c.blue * f, 1f)

/** Team color as a quiet accent: mostly the near-black card, a hint of the team at its own side. */
private fun accentBrush(away: Color, home: Color): Brush {
    val base = Color(0xFF111114)
    fun mix(c: Color) = Color(base.red + (c.red - base.red) * 0.32f, base.green + (c.green - base.green) * 0.32f, base.blue + (c.blue - base.blue) * 0.32f, 1f)
    return Brush.horizontalGradient(0f to mix(away), 0.45f to base, 0.55f to base, 1f to mix(home))
}

@Composable
internal fun GameTile(g: SportData.Game, hub: HubCtx, modifier: Modifier = Modifier) =
    GameTileCard(g, modifier) { if (g.state == 1 && hub.chans(g).isNotEmpty()) hub.watch(g) else hub.open(g) }

/** The matchup tile itself (also used by the Live sports row on the home screen). */
@Composable
internal fun GameTileCard(g: SportData.Game, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(if (f) focusScale(1.04f) else 1f, androidx.compose.animation.core.tween(150), label = "gametile")
    val shape = RoundedCornerShape(dpx(14))
    val live = g.state == 1
    val w = dpx(360); val h = dpx(202)
    Column(modifier.width(w), verticalArrangement = Arrangement.spacedBy(dpx(10))) {
        Box(
            // Flat card (no gradients): cleaner, and cheaper to draw on a TV
            Modifier.size(w, h).graphicsLayer { scaleX = sc; scaleY = sc }.clip(shape)
                .background(if (f) Color(0xFF23262E) else Color(0xFF16181D))
                .border(if (f) 2.dp else 1.dp, if (f) Color.White else Color(0x1AFFFFFF), shape)
                .onFocusChanged { f = it.isFocused }
                .trackFocus()
                .restoreFocus("game:" + g.key)
                .clickable { SportSession.lastTile = "game:" + g.key; onClick() }
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = dpx(16), vertical = dpx(12)), verticalAlignment = Alignment.CenterVertically) {
                if (live) {
                    Box(Modifier.size(dpx(8)).clip(RoundedCornerShape(50)).background(Color(0xFFFF3B30)))
                    Text(raw = true, text = "LIVE", color = Color.White, fontSize = spx(13), fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = dpx(6)))
                } else {
                    // One clear label per state: started (no live data), final, postponed/canceled; scheduled games show only their time
                    val k = statusKind(g)
                    val label = when { k == 5 -> "STARTED"; g.state == 2 -> "FINAL"; k == 3 || k == 4 -> statusText(g).uppercase(); else -> null }
                    if (label != null) Text(raw = true, text = label, color = if (k == 3 || k == 4) Color(0xFFFFB020) else Color(0xCCFFFFFF), fontSize = spx(13), fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.weight(1f))
                // Live: the match clock or period ("48'", "Q3 5:12") instead of the start time
                val clock = if (live) g.detail.takeIf { it.isNotBlank() && it.length <= 14 } else null
                Text(raw = true, text = (clock ?: SportShowcase.time(g.start)).uppercase(), color = Color(0xE6FFFFFF), fontSize = spx(13), fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                if (SportHubFavs.isFavGame(g) || SportFavs.isFavGame(g)) AppIcon(AIcon.Star, DS.Gold, size = dpx(16)) else Spacer(Modifier.width(dpx(40)))
            }
            if (g.solo) {
                Column(Modifier.align(Alignment.Center).padding(horizontal = dpx(20)), horizontalAlignment = Alignment.CenterHorizontally) {
                    val l = SportData.leagueOf(g)
                    TeamLogo(l?.let { SportData.leagueLogos[it.path] }, g.league, dpx(72))
                    Spacer(Modifier.height(dpx(10)))
                    Text(raw = true, text = g.title.ifBlank { g.league }.uppercase(), color = Color.White, fontSize = spx(17), fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
            } else {
                Row(Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = dpx(14)), verticalAlignment = Alignment.CenterVertically) {
                    TileSide(g.away, Modifier.weight(1f))
                    Column(Modifier.width(dpx(110)), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(raw = true, text = SportShowcase.title(SportData.sportOf(g)).uppercase(), color = Color(0xB3FFFFFF), fontSize = spx(10),
                            fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, maxLines = 1)
                        // A missing score is shown as VS, never as 0 – 0
                        val showScore = g.state != 0 && !SportPrefs.hideScores && !g.away.score.isNullOrBlank() && !g.home.score.isNullOrBlank()
                        Text(raw = true, text = if (showScore) g.away.score + " – " + g.home.score else "VS", color = Color.White,
                            fontSize = spx(if (showScore) 30 else 32), fontWeight = FontWeight.ExtraBold, letterSpacing = if (showScore) 0.sp else 2.sp, maxLines = 1)
                    }
                    TileSide(g.home, Modifier.weight(1f))
                }
            }
            Text(raw = true, text = g.league.uppercase(), color = Color(0x99FFFFFF), fontSize = spx(10), fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
                maxLines = 1, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = dpx(12)))
        }
        // One line under the tile: the full matchup (the tile shortens long names); state and time are on the tile
        Text(raw = true, text = SportShowcase.line(g), color = Tok.TextHi, fontSize = spx(17), fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = dpx(4)))
    }
}

@Composable
private fun TileSide(t: SportData.Team, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        TeamLogo(t.logo, t.abbr.ifBlank { t.short }, dpx(64))
        Spacer(Modifier.height(dpx(10)))
        Text(raw = true, text = t.short.ifBlank { t.name }.uppercase(), color = Color.White, fontSize = spx(15), fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/** One showcase row: title (with "See all") and wide game tiles. */
@Composable
internal fun GameTileRow(title: String, games: List<SportData.Game>, hub: HubCtx, onMore: (() -> Unit)?) {
    Column {
        HubSectionTitle(title, raw = true, action = if (onMore != null) "See all" else null, onAction = onMore)
        Spacer(Modifier.height(dpx(10)))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(dpx(18)), contentPadding = PaddingValues(vertical = dpx(10))) {
            items(games.take(30), key = { title + it.key }) { g -> GameTile(g, hub) }
        }
    }
}

/** Soccer and NBA games of the coming week for the top of the sports page (ESPN date range: one request per league). */
internal object SportAhead {
    class Week(val soccer: List<SportData.Game>, val nba: List<SportData.Game>, val football: List<SportData.Game> = emptyList())

    private const val MAX_AGE = 30 * 60_000L
    @Volatile private var last = Week(emptyList(), emptyList())

    /** Week ahead besides soccer: NBA and WNBA, NFL and college football (college basketball has too many games per day). */
    private val AHEAD_OTHER = setOf("NBA", "WNBA", "NFL", "College Football")

    fun cached(): Week = last

    suspend fun load(): Week = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val tz = General.zone()
        val range = SportData.ymd(0, tz) + "-" + SportData.ymd(7, tz)
        val now = System.currentTimeMillis() / 1000
        val ls = SportData.LEAGUES.filter { SportLeagues.on(it) && it.kind == SportData.KIND_TEAM && (it.sport == "soccer" || it.name in AHEAD_OTHER) }
        val got = kotlinx.coroutines.coroutineScope {
            ls.map { l -> async { l to runCatching { SportData.fetch(l, range, MAX_AGE) }.getOrDefault(emptyList()) } }.awaitAll()
        }
        fun keep(l: List<SportData.Game>) = l.filter { it.state == 1 || (it.state == 0 && it.start > now - 3 * 3600) }.distinctBy { it.key }
        val w = Week(keep(got.filter { it.first.sport == "soccer" }.flatMap { it.second }),
            keep(got.filter { it.first.sport == "basketball" }.flatMap { it.second }),
            keep(got.filter { it.first.sport == "nfl" }.flatMap { it.second }))
        if (w.soccer.isNotEmpty() || w.nba.isNotEmpty() || w.football.isNotEmpty()) last = w
        w
    }
}
