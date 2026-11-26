package com.ipvio.stream

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sports overview, split view (Fof TV, Nuvio look): the day's games as team-color cards on the left (Live now, Coming up,
 * Final), the selected game large on the right. A live game plays there muted once you rest on it for a moment
 * (720p at most, its own slim player); OK opens it full screen, other games open their game page.
 */
@Composable
internal fun SportSplit(games0: List<SportData.Game>, hub: HubCtx) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val nowS = System.currentTimeMillis() / 1000
    val games = games0.map { SportFeed.latest[it.key] ?: it }
    val liveG = games.filter { it.state == 1 && !it.solo }.sortedWith(compareBy({ if (SportFavs.isFavGame(it) || SportHubFavs.isFavGame(it)) 0 else 1 }, { it.start }))
    val soon = games.filter { it.state == 0 && !it.solo && it.start >= nowS - 3 * 3600 }.sortedBy { it.start }.take(30)
    val finals = games.filter { it.state == 2 && !it.solo }.sortedByDescending { it.start }.take(20)
    var sel by remember { mutableStateOf<SportData.Game?>(null) }
    val shown = sel?.let { SportFeed.latest[it.key] ?: it } ?: liveG.firstOrNull() ?: soon.firstOrNull() ?: finals.firstOrNull()
    // The preview channel: looked up only after the highlight rests on a live game (paging through stays light)
    var preview by remember { mutableStateOf<Item?>(null) }
    LaunchedEffect(shown?.key, shown?.state) {
        preview = null
        val g = shown ?: return@LaunchedEffect
        if (g.state != 1) return@LaunchedEffect
        delay(1_200)
        preview = runCatching { withContext(Dispatchers.Default) { SportWatch.startupChannels(ctx, g, hub.live) }.firstOrNull() }.getOrNull()
    }
    fun open(g: SportData.Game) {
        // Free the preview's connection first: many providers allow only one stream at a time
        preview = null
        scope.launch { delay(250); if (g.state == 1) hub.watch(g) else hub.open(g) }
    }
    if (games.isEmpty()) { HubLoading("Loading games…"); return }
    Row(Modifier.fillMaxSize().padding(start = DS.PageX, end = DS.PageX, top = dpx(8)), horizontalArrangement = Arrangement.spacedBy(dpx(26))) {
        LazyColumn(Modifier.width(dpx(560)).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(dpx(12)), contentPadding = PaddingValues(top = dpx(4), bottom = dpx(60))) {
            fun section(key: String, title: String, l: List<SportData.Game>, dot: Boolean) {
                if (l.isEmpty()) return
                item(key = "h_$key") {
                    Row(Modifier.padding(top = dpx(8), bottom = dpx(2)), verticalAlignment = Alignment.CenterVertically) {
                        if (dot) { Box(Modifier.size(dpx(10)).clip(RoundedCornerShape(50)).background(Color(0xFFE50914))); Spacer(Modifier.width(dpx(8))) }
                        Text(raw = true, text = title, color = Color.White, fontSize = spx(18), fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                        Spacer(Modifier.weight(1f))
                        Text(raw = true, text = "${l.size} " + if (l.size == 1) "game" else "games", color = Color(0xFF9A9A9A), fontSize = spx(14))
                    }
                }
                items(l, key = { key + "|" + it.key }) { g ->
                    SplitGameCard(SportFeed.latest[g.key] ?: g, playing = preview != null && shown?.key == g.key,
                        onFocus = { sel = g }) { open(g) }
                }
            }
            section("live", "LIVE NOW", liveG, true)
            section("soon", "COMING UP", soon, false)
            section("final", "FINAL", finals, false)
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(dpx(14))) {
            val g = shown
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(dpx(16))).background(Color(0xFF0C1018))) {
                if (g != null) {
                    // Behind the video (and instead of it when nothing plays): the two team colors with their logos
                    Row(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxHeight().background(Brush.verticalGradient(listOf(splitColor(g.away), Color(0xFF0C1018)))), contentAlignment = Alignment.Center) {
                            TeamLogo(g.away.logo, g.away.abbr, dpx(150))
                        }
                        Box(Modifier.weight(1f).fillMaxHeight().background(Brush.verticalGradient(listOf(splitColor(g.home), Color(0xFF0C1018)))), contentAlignment = Alignment.Center) {
                            TeamLogo(g.home.logo, g.home.abbr, dpx(150))
                        }
                    }
                    preview?.let { ch -> androidx.compose.runtime.key(ch.id) { SecondStream(ch, split = true, audioOn = false, modifier = Modifier.fillMaxSize()) { preview = null } } }
                    // Score line on the picture
                    Row(
                        Modifier.align(Alignment.BottomStart).fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xD9000000))))
                            .padding(start = dpx(26), end = dpx(26), top = dpx(60), bottom = dpx(20)),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Column(Modifier.weight(1f)) {
                            for (t in listOf(g.away, g.home)) Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(raw = true, text = t.short.ifBlank { t.name }, color = Color.White, fontSize = spx(32), fontWeight = FontWeight.ExtraBold, maxLines = 1)
                                if (g.state != 0 && !SportPrefs.hideScores) { Spacer(Modifier.width(dpx(14))); Text(raw = true, text = t.score.orEmpty(), color = Color.White, fontSize = spx(40), fontWeight = FontWeight.ExtraBold) }
                            }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            if (g.state == 1) LiveChip()
                            Text(raw = true, text = whenText(g), color = Color.White, fontSize = spx(24), fontWeight = FontWeight.Bold)
                            if (g.note.isNotBlank()) Text(raw = true, text = g.note, color = Color(0xFFCCCCCC), fontSize = spx(16), maxLines = 1)
                        }
                    }
                }
            }
            if (g != null) Row(horizontalArrangement = Arrangement.spacedBy(dpx(14))) {
                InfoPanel("GAME", listOf(g.league, g.venue, g.away.name + " at " + g.home.name).filter { it.isNotBlank() }.joinToString(" · "), Modifier.weight(1f))
                InfoPanel("WATCHING ON", (preview?.title ?: g.tv.ifBlank { g.nets.joinToString(" · ") }).ifBlank { "Finding a channel…" } +
                    if (g.state == 1) "\nOK: watch full screen" else "\nOK: game page", Modifier.width(dpx(300)))
            }
        }
    }
}

@Composable
private fun SplitGameCard(g: SportData.Game, playing: Boolean, onFocus: () -> Unit, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(dpx(14))
    Row(
        Modifier.fillMaxWidth().height(dpx(96)).clip(shape).background(Color(0xFF141A26))
            .border(3.dp, if (f) Color(0xFF5AA0FF) else Color.Transparent, shape)
            .onFocusChanged { f = it.isFocused; if (it.isFocused) onFocus() }
            .trackFocus()
            .clickable(onClick = onClick)
    ) {
        Box(Modifier.width(dpx(52)).fillMaxHeight().background(Color(0xFF0D1119)), contentAlignment = Alignment.Center) {
            TeamLogo(SportData.leagueOf(g)?.let { SportData.leagueLogos[it.path] }, g.league, dpx(30))
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            for (t in listOf(g.away, g.home)) Row(
                Modifier.weight(1f).fillMaxWidth().background(Brush.horizontalGradient(listOf(splitColor(t), splitColor(t).copy(alpha = 0.55f)))).padding(horizontal = dpx(12)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TeamLogo(t.logo, t.abbr, dpx(28))
                Spacer(Modifier.width(dpx(10)))
                Column(Modifier.weight(1f)) {
                    Text(raw = true, text = t.short.ifBlank { t.name }, color = Color.White, fontSize = spx(19), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    t.record?.takeIf { it.isNotBlank() }?.let { Text(raw = true, text = it, color = Color(0xB3FFFFFF), fontSize = spx(12), maxLines = 1) }
                }
                if (g.state != 0 && !SportPrefs.hideScores) Text(raw = true, text = t.score.orEmpty(), color = Color.White, fontSize = spx(26), fontWeight = FontWeight.ExtraBold)
            }
        }
        Column(Modifier.width(dpx(140)).fillMaxHeight().background(Color(0xFF10151F)).padding(horizontal = dpx(12)), verticalArrangement = Arrangement.Center) {
            when {
                playing -> Text(raw = true, text = "▶ PLAYING", color = Color(0xFF8FBAFF), fontSize = spx(11), fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.border(1.5.dp, Color(0xFF5AA0FF), RoundedCornerShape(dpx(4))).padding(horizontal = dpx(5), vertical = dpx(1)))
                g.state == 1 -> LiveChip()
            }
            Text(raw = true, text = whenText(g), color = Color.White, fontSize = spx(17), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = dpx(4)))
            val net = g.tv.ifBlank { g.nets.joinToString(" · ") }
            if (net.isNotBlank()) Text(raw = true, text = net, color = Color(0xFF9A9A9A), fontSize = spx(12), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun LiveChip() {
    Text(raw = true, text = "● LIVE", color = Color.White, fontSize = spx(11), fontWeight = FontWeight.ExtraBold,
        modifier = Modifier.clip(RoundedCornerShape(dpx(4))).background(Color(0xFFE50914)).padding(horizontal = dpx(6), vertical = dpx(1)))
}

@Composable
private fun InfoPanel(title: String, body: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(dpx(14))).background(Color(0xFF141A26)).padding(horizontal = dpx(18), vertical = dpx(14))) {
        Text(raw = true, text = title, color = Color(0xFF9A9A9A), fontSize = spx(13), fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.height(dpx(6)))
        Text(raw = true, text = body, color = Color.White, fontSize = spx(16), maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

private fun whenText(g: SportData.Game): String = when {
    g.state == 1 -> g.detail.ifBlank { "Live" }
    g.state == 2 -> "Final"
    statusKind(g) == 3 || statusKind(g) == 4 -> statusText(g)
    else -> SportShowcase.time(g.start)
}

/** ESPN team color ("0b6e7f"), darkened a little so white text stays readable; grey when unknown. */
private fun splitColor(t: SportData.Team): Color = runCatching {
    val v = t.color!!.removePrefix("#").toLong(16)
    val c = Color((0xFF000000 or v).toInt())
    Color(c.red * 0.8f, c.green * 0.8f, c.blue * 0.8f)
}.getOrDefault(Color(0xFF2A3142))
