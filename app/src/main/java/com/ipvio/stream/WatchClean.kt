package com.ipvio.stream

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Watch Live page, clean look (Option A): a full-width game header in the two team colors with the score in the
 * middle, and the sources as plain rows, best first, each with why it was picked and whether it played before.
 */
@Composable
internal fun WatchHeader(g: SportData.Game) {
    val shape = RoundedCornerShape(dpx(18))
    Row(Modifier.fillMaxWidth().height(dpx(190)).clip(shape)) {
        if (g.solo) {
            Column(
                Modifier.fillMaxWidth().fillMaxHeight().background(Color(0xFF141A26)).padding(dpx(24)),
                verticalArrangement = Arrangement.spacedBy(dpx(8), Alignment.CenterVertically)
            ) {
                MatchStatus(g, big = true)
                Text(raw = true, text = g.title, color = Color.White, fontSize = spx(34), fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val info = listOf(g.note, g.venue).filter { it.isNotBlank() }.joinToString(" · ")
                if (info.isNotBlank()) Text(raw = true, text = info, color = Color(0xFFCCCCCC), fontSize = spx(16), maxLines = 1)
            }
            return@Row
        }
        TeamSide(g.home, Modifier.weight(1f), toRight = true)
        Column(
            Modifier.width(dpx(260)).fillMaxHeight().background(Color(0xFF0D1119)).padding(horizontal = dpx(12)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(dpx(4), Alignment.CenterVertically)
        ) {
            MatchStatus(g, big = true)
            val long = (g.home.score?.length ?: 0) > 6 || (g.away.score?.length ?: 0) > 6
            val big = if (g.state == 0) hhmm(g.start) else if (SportPrefs.hideScores) "vs" else scoreLine(g)
            if (!long) Text(raw = true, text = big, color = Color.White, fontSize = spx(58), fontWeight = FontWeight.Black, maxLines = 1)
            val info = listOf(if (g.state == 0) dayLabel(g.start) else "", g.venue).filter { it.isNotBlank() }.joinToString(" · ")
            if (info.isNotBlank()) Text(raw = true, text = info, color = Color(0xFFCCCCCC), fontSize = spx(15), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TeamSide(g.away, Modifier.weight(1f), toRight = false)
    }
}

@Composable
private fun TeamSide(t: SportData.Team, modifier: Modifier, toRight: Boolean) {
    val c = teamColor(t)
    val dark = Color(c.red * 0.3f, c.green * 0.3f, c.blue * 0.3f)
    Column(
        modifier.fillMaxHeight().background(Brush.linearGradient(if (toRight) listOf(c, dark) else listOf(dark, c))),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(dpx(6), Alignment.CenterVertically)
    ) {
        Box(Modifier.size(dpx(96)).clip(RoundedCornerShape(50)).background(Color(0x55000000)), contentAlignment = Alignment.Center) {
            TeamLogo(t.logo, t.abbr, dpx(70))
        }
        Text(raw = true, text = t.name, color = Color.White, fontSize = spx(24), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        t.record?.let { Text(raw = true, text = it, color = Color(0xFFDDDDDD), fontSize = spx(15)) }
    }
}

@Composable
internal fun SourcesTitle(n: Int) {
    Row(Modifier.padding(top = dpx(6), bottom = dpx(4)), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(dpx(10))) {
        Text(raw = true, text = "Sources", color = Color.White, fontSize = spx(22), fontWeight = FontWeight.ExtraBold)
        Text(raw = true, text = "$n found · best first", color = Color(0xFF999999), fontSize = spx(15))
    }
}

private fun whyText(h: SportWatch.Hit): String = when (h.why) {
    0 -> "You picked this channel"
    1 -> "TV guide: " + h.show
    3 -> "On " + h.show
    4 -> "Team channel · " + h.show
    5 -> "Your usual channel"
    6 -> h.show + " · matchup unconfirmed"
    else -> "Channel name matches"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SourceRow(h: SportWatch.Hit, best: Boolean, onClick: () -> Unit) {
    val ctx = LocalContext.current
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(dpx(14))
    val dead = DeadLinks.isDead(h.ch.url)
    val verified = !dead && SportSession.isVerified(ctx, h.ch.id)
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (f) Color(0x24FFFFFF) else Color(0xFF141A26))
            .border(3.dp, if (f) Color.White else Color.Transparent, shape)
            .onFocusChanged { f = it.isFocused }
            .trackFocus()
            .combinedClickable(onClick = onClick)
            .padding(horizontal = dpx(16), vertical = dpx(12)),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(16))
    ) {
        Box(Modifier.width(dpx(64)).height(dpx(40)).clip(RoundedCornerShape(dpx(8))).background(Color(0xFF232A38)), contentAlignment = Alignment.Center) {
            TeamLogo(h.ch.logo, h.ch.title.filter { it.isLetter() }.take(3).uppercase(), dpx(34))
        }
        Column(Modifier.weight(1f)) {
            Text(raw = true, text = h.ch.title, color = Color.White, fontSize = spx(20), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(raw = true, text = whyText(h), color = Color(0xFFAAAAAA), fontSize = spx(14), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (best && !dead) Chip("BEST PICK", Color.White, Color(0xFFE50914))
        when {
            dead -> Chip("Not working", Color(0xFFFFD27A), Color(0x33FFB020))
            verified -> Chip("✓ Worked before", Color(0xFF7BE39A), Color(0xFF1E5A32))
            else -> Chip("Available", Color(0xFFCCCCCC), Color(0x18FFFFFF))
        }
    }
}

@Composable
private fun Chip(text: String, fg: Color, bg: Color) {
    Box(Modifier.clip(RoundedCornerShape(dpx(12))).background(bg).padding(horizontal = dpx(10), vertical = dpx(4))) {
        Text(raw = true, text = text, color = fg, fontSize = spx(13), fontWeight = FontWeight.Bold, maxLines = 1)
    }
}
