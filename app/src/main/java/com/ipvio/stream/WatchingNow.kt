package com.ipvio.stream

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/** Option A: banner at the top of Home while another profile is watching – who, what, where, and Join. */
@Composable
internal fun WatchingNowBanner(w: Presence.Now, byId: Map<String, Item>, onResume: (Entry, Item) -> Unit) {
    val item = byId[w.itemId] ?: byId.values.firstOrNull { it.kind != Kind.LIVE && it.title.equals(w.title, true) }
    val shape = RoundedCornerShape(dpx(20))
    var any by remember { mutableStateOf(false) }
    Row(
        Modifier.padding(horizontal = DS.PageX, vertical = dpx(10)).fillMaxWidth().clip(shape).background(Color(0xCC141A26))
            .border(4.dp, if (any) Color.White else Color.Transparent, shape)
            .onFocusChanged { any = it.hasFocus }
            .padding(horizontal = dpx(24), vertical = dpx(18)),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(24))
    ) {
        Box(Modifier.size(dpx(72)).clip(CircleShape).background(avatarColor(w.name)).border(3.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
            Text(raw = true, text = w.name.take(1).uppercase(), color = Color.White, fontSize = spx(30), fontWeight = FontWeight.Black)
        }
        Box(Modifier.width(dpx(210)).height(dpx(118)).clip(RoundedCornerShape(dpx(12))).background(Color(0xFF2A2440))) {
            if (item != null) {
                val m = rememberMeta(item)
                val url = m?.backdrop?.let { cardBackdrop(it) } ?: m?.poster?.let { cardPoster(it) }
                if (url != null) AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            if (w.durMs > 0) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth((w.posMs.toFloat() / w.durMs).coerceIn(0f, 1f)).height(5.dp).background(Color(0xFFE50914)))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(dpx(11)).clip(CircleShape).background(Color(0xFFE50914)))
                Text(raw = true, text = "  ${w.name} is watching now" + (if (w.device.isNotBlank()) " · ${w.device}" else ""), color = Color(0xFFCCCCCC), fontSize = spx(17), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(raw = true, text = w.title, color = Color.White, fontSize = spx(34), fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val left = PresenceLogic.minutesLeft(w.posMs, w.durMs)
            val sub = listOfNotNull(if (w.season > 0 || w.ep > 0) "S${w.season} E${w.ep}" else null, if (left > 0) "$left min left" else null).joinToString(" · ")
            if (sub.isNotBlank()) Text(raw = true, text = sub, color = Color(0xFFBBBBBB), fontSize = spx(18))
        }
        if (item != null) {
            // The playlist's own address for this episode, so resuming does not go to the add-ons first
            val u = if (item.kind == Kind.SERIES) item.episodes.firstOrNull { it.season == w.season && it.num == w.ep }?.url.orEmpty() else item.url
            BannerButton("▶ Join " + w.name, true) { onResume(Entry(item.id, w.title, u, w.posMs, w.durMs, System.currentTimeMillis(), w.season, w.ep), item) }
            BannerButton("Watch from start", false) { onResume(Entry(item.id, w.title, u, 0L, w.durMs, System.currentTimeMillis(), w.season, w.ep), item) }
        } else Text(raw = true, text = "Not in your playlist", color = Color(0xFF999999), fontSize = spx(16))
    }
}

@Composable
private fun BannerButton(label: String, primary: Boolean, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Box(
        Modifier.clip(shape).background(if (f) Color.White else if (primary) Color(0x33FFFFFF) else Color(0x1FFFFFFF))
            .border(2.dp, if (primary && !f) Color(0x66FFFFFF) else Color.Transparent, shape)
            .onFocusChanged { f = it.isFocused }.trackFocus().clickable(onClick = onClick)
            .padding(horizontal = dpx(24), vertical = dpx(13))
    ) { Text(raw = true, text = label, color = if (f) Color.Black else Color.White, fontSize = spx(19), fontWeight = FontWeight.ExtraBold, maxLines = 1) }
}

private fun avatarColor(name: String): Color {
    val c = listOf(0xFFD0527A, 0xFF2563EB, 0xFF2E9D5B, 0xFFF08C2B, 0xFF7C4DFF, 0xFF00A3A3)
    return Color(c[(name.lowercase().hashCode() and 0x7fffffff) % c.size])
}
