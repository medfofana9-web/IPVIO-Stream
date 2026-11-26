package com.ipvio.stream

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import org.json.JSONArray

/**
 * Tonight for us (Option B): one pick for tonight from what this profile and the others on this TV are watching –
 * a show you both have going comes first (started at the episode of whoever is further behind), then what the
 * other profile is watching, then what you started. "Something else" moves to the next pick.
 */
object TonightLogic {
    class Pick(val entry: Entry, val reason: String, val score: Int)

    private fun read(c: Context, id: Int): List<Entry> = runCatching {
        val raw = c.applicationContext.getSharedPreferences(Profiles.scopedName("ipvio_watch", id), Context.MODE_PRIVATE).getString("e", null) ?: return emptyList()
        val a = JSONArray(raw)
        (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            Entry(o.getString("i"), o.getString("t"), o.optString("u"), o.optLong("p"), o.optLong("d"), o.optLong("ts"), o.optInt("s"), o.optInt("e"), o.optBoolean("n"))
        }
    }.getOrDefault(emptyList())

    /** Picks (best first) and the other profile's name; empty when no other profile on this TV watches anything. */
    fun picks(c: Context): Pair<String, List<Pick>> {
        val others = Profiles.list.filter { it.id != Profiles.active }.map { Profiles.displayName(it) to read(c, it.id) }.filter { it.second.isNotEmpty() }
        if (others.isEmpty()) return "" to emptyList()
        val (name, theirs) = others.maxByOrNull { it.second.maxOf { e -> e.ts } }!!
        val mine = WatchStore.entries.toList()
        val out = LinkedHashMap<String, Pick>()
        for (t in theirs) {
            val m = mine.firstOrNull { it.itemId == t.itemId }
            if (m != null) {
                // Both watching: start at whoever is further behind, so you can watch it together
                val behind = if (m.season * 1000 + m.ep <= t.season * 1000 + t.ep) m else t
                val same = m.season == t.season && m.ep == t.ep
                out[t.itemId] = Pick(behind, if (same) "You're both on this episode" else "You both watch this", 300 + (maxOf(m.ts, t.ts) / 1000 % 100).toInt())
            } else out[t.itemId] = Pick(t, "$name is watching this", 200)
        }
        for (m in mine) if (m.itemId !in out) out[m.itemId] = Pick(m, "You started this", 100)
        val recent = (theirs + mine).associate { it.itemId to it.ts }
        return name to out.values.sortedWith(compareByDescending<Pick> { it.score / 100 }.thenByDescending { recent[it.entry.itemId] ?: 0L }).take(6)
    }
}

@Composable
internal fun TonightForUs(byId: Map<String, Item>, onResume: (Entry, Item) -> Unit) {
    val ctx = LocalContext.current
    val ver = WatchStore.entries.size
    val (name, list) = remember(ver, Profiles.active) { TonightLogic.picks(ctx) }
    val picks = list.filter { byId[it.entry.itemId] != null }
    if (name.isBlank() || picks.isEmpty()) return
    var i by remember(picks.size) { mutableIntStateOf(0) }
    val p = picks[i % picks.size]
    val item = byId[p.entry.itemId] ?: return
    val me = Profiles.activeName()
    val shape = RoundedCornerShape(dpx(24))
    Column(Modifier.padding(horizontal = DS.PageX, vertical = dpx(10)), verticalArrangement = Arrangement.spacedBy(dpx(16))) {
        Box(Modifier.fillMaxWidth().height(dpx(420)).clip(shape).background(Color(0xFF1A0D10))) {
            val m = rememberMeta(item)
            val url = m?.backdrop?.let { cardBackdrop(it) } ?: m?.poster?.let { cardPoster(it) }
            if (url != null) AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopEnd)
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color(0xF20B0C10), 0.45f to Color(0xB30B0C10), 0.8f to Color.Transparent)))
            Column(Modifier.align(Alignment.BottomStart).padding(dpx(40)).width(dpx(820)), verticalArrangement = Arrangement.spacedBy(dpx(8))) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Initial(me, Color(0xFF2563EB)); Box(Modifier.offset(x = (-10).dp)) { Initial(name, Color(0xFFD0527A)) }
                    Text(raw = true, text = "  Our pick for tonight · " + p.reason, color = Color(0xFFDDDDDD), fontSize = spx(17))
                }
                Text(raw = true, text = item.title, color = Color.White, fontSize = spx(56), fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val ep = if (p.entry.season > 0 || p.entry.ep > 0) "S${p.entry.season} E${p.entry.ep}" else ""
                val left = PresenceLogic.minutesLeft(p.entry.pos, p.entry.dur)
                Text(raw = true, text = listOfNotNull(ep.ifBlank { null }, if (left > 0 && !p.entry.next) "$left min left" else null, if (p.entry.next) "next episode" else null).joinToString(" · "),
                    color = Color(0xFFCCCCCC), fontSize = spx(19))
                Row(Modifier.padding(top = dpx(10)), horizontalArrangement = Arrangement.spacedBy(dpx(14))) {
                    PickButton(if (ep.isNotBlank()) "▶ Start $ep" else "▶ Play", true) { onResume(p.entry, item) }
                    if (picks.size > 1) PickButton("Something else", false) { i++ }
                }
            }
        }
        // Or try: the other picks, one press starts them
        val rest = picks.filterIndexed { k, _ -> k != i % picks.size }.take(4)
        if (rest.isNotEmpty()) {
            Text(raw = true, text = "Or try", color = Color.White, fontSize = spx(22), fontWeight = FontWeight.ExtraBold)
            Row(horizontalArrangement = Arrangement.spacedBy(dpx(18))) {
                rest.forEach { r -> byId[r.entry.itemId]?.let { it2 -> SmallPick(it2, r) { onResume(r.entry, it2) } } }
            }
        }
    }
}

@Composable
private fun SmallPick(item: Item, p: TonightLogic.Pick, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(dpx(12))
    Column(Modifier.width(dpx(300)).onFocusChanged { f = it.isFocused }.trackFocus().clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(shape).background(Color(0xFF1A1D26)).border(3.dp, if (f) Color.White else Color.Transparent, shape)) {
            val m = rememberMeta(item)
            val url = m?.backdrop?.let { cardBackdrop(it) } ?: m?.poster?.let { cardPoster(it) }
            if (url != null) AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Text(raw = true, text = item.title, color = Color.White, fontSize = spx(17), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = dpx(6)))
        Text(raw = true, text = p.reason, color = Color(0xFF999999), fontSize = spx(13), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Initial(name: String, c: Color) {
    Box(Modifier.size(dpx(34)).clip(CircleShape).background(c).border(2.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
        Text(raw = true, text = name.take(1).uppercase(), color = Color.White, fontSize = spx(15), fontWeight = FontWeight.Black)
    }
}

@Composable
private fun PickButton(label: String, primary: Boolean, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Box(
        Modifier.clip(shape).background(if (f) Color.White else if (primary) Color(0x40FFFFFF) else Color(0x1FFFFFFF))
            .onFocusChanged { f = it.isFocused }.trackFocus().clickable(onClick = onClick)
            .padding(horizontal = dpx(26), vertical = dpx(13))
    ) { Text(raw = true, text = label, color = if (f) Color.Black else Color.White, fontSize = spx(19), fontWeight = FontWeight.ExtraBold, maxLines = 1) }
}
