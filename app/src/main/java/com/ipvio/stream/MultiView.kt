package com.ipvio.stream

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Layouts of the multi view: slots are laid out as fractions of the screen. */
object MultiViewLogic {
    const val GRID = 0   // two tiles of equal size side by side
    const val BIG = 1    // one large tile on the left, a smaller one on the right
    const val QUAD = 2   // four tiles, 2 x 2
    const val BIG3 = 3   // one large tile, three small ones below
    val LAYOUT_NAMES = listOf("Zwei gleich groß", "Einer groß, einer klein", "Four games (2 x 2)", "One big, three small")

    /**
     * How many videos this device can decode at once (hardware H.264 decoder instances), capped at 4.
     * Weaker boxes get fewer tiles instead of stuttering or crashing.
     */
    val maxStreams: Int by lazy {
        runCatching {
            val list = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
            val hw = list.filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals("video/avc", true) } &&
                !it.name.startsWith("OMX.google") && !it.name.startsWith("c2.android") }
            hw.maxOfOrNull { it.getCapabilitiesForType("video/avc").maxSupportedInstances } ?: 2
        }.getOrDefault(2).coerceIn(2, 4)
    }

    /** Most tiles at once. Four streams need a strong box (Fire TV 4K Max, Shield) and a provider with 4 connections. */
    const val SLOTS = 4

    fun tiles(layout: Int): Int = if (layout >= QUAD) 4 else 2

    /** Start channels: the first distinct live channels from favorites, then history, then the list. */
    fun startChannels(live: List<Item>, favs: List<String>, hist: List<String>, n: Int = 2): List<Item> {
        val byId = live.associateBy { it.id }
        val out = LinkedHashMap<String, Item>()
        for (id in favs + hist) byId[id]?.let { out.putIfAbsent(it.id, it) }
        for (i in live) { if (out.size >= n) break; out.putIfAbsent(i.id, i) }
        return out.values.take(n)
    }
}

/**
 * Multi view: two live streams at once. Only one tile has sound (highlighted border). OK on a tile = sound to this tile,
 * long OK = choose another channel for this tile. Many providers allow only one connection per account; failing tiles say so.
 * Opened as a full-screen dialog from the Live TV list.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MultiViewScreen(live: List<Item>, start: List<Item>? = null, onExit: ((Item?) -> Unit)? = null, onClose: () -> Unit = {}) {
    // [start]: channels picked by the caller (from the player: the game being watched is tile 1; sports: one per live game)
    val cap = MultiViewLogic.maxStreams
    val slots = remember { mutableStateListOf<Item>().also { it.addAll((start ?: MultiViewLogic.startChannels(live, Library.favs.toList(), History.ids.toList())).take(cap)) } }
    var layout by remember { mutableIntStateOf(if (slots.size > 2 && cap >= 4) MultiViewLogic.QUAD else MultiViewLogic.GRID) }
    var audio by remember { mutableIntStateOf(0) }
    var picking by remember { mutableIntStateOf(-1) }
    var gamePick by remember { mutableIntStateOf(-1) }
    var menuFor by remember { mutableIntStateOf(-1) }
    var full by remember { mutableIntStateOf(-1) }
    // Leaving: the game with the sound continues in the normal player (or the caller just closes)
    val close: () -> Unit = { val sel = slots.getOrNull(audio); if (onExit != null) onExit(sel) else onClose() }
    // Live games with a channel, for "Add game" (computed off the main thread)
    val liveGames by androidx.compose.runtime.produceState(emptyList<Pair<SportData.Game, Item>>(), live) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            SportFeed.latest.values.filter { it.state == 1 }.sortedBy { it.league }.take(40).mapNotNull { g ->
                (SportSession.channelOf(g) ?: runCatching { SportWatch.channels(g, live).firstOrNull() }.getOrNull())?.let { g to it }
            }
        }
    }
    val pickAt: (Int) -> Unit = { i -> if (liveGames.isNotEmpty()) gamePick = i else picking = i }
    Dialog(onDismissRequest = { if (full >= 0) full = -1 else close() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Mehrfachansicht", color = Color.White, fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                SmallPill(MultiViewLogic.LAYOUT_NAMES[layout], icon = AIcon.Grid) {
                    // Layouts with four tiles only where the device can decode four videos
                    val n0 = if (cap >= 4) MultiViewLogic.LAYOUT_NAMES.size else 2
                    layout = (layout + 1) % n0
                    // Going from four tiles back to two: close the extra streams (they hold provider connections)
                    val n = MultiViewLogic.tiles(layout)
                    while (slots.size > n) slots.removeAt(slots.lastIndex)
                    if (audio >= n) audio = 0
                }
                if (slots.size < MultiViewLogic.tiles(layout)) SmallPill("Add game", icon = AIcon.Plus) { pickAt(slots.size) }
                SmallPill("Exit", icon = AIcon.Close) { close() }
                Text(raw = true, text = "OK = sound here · hold OK = options" + (if (cap < 4) " · this device plays up to $cap at once" else ""), color = Color(0xFFA0A6B8), fontSize = 14.sp)
            }
            @Composable
            fun tile(i: Int, m: Modifier) {
                val item = slots.getOrNull(i)
                var f by remember { mutableStateOf(false) }
                Box(
                    m.onFocusChanged { f = it.isFocused }
                        .border(if (f) 4.dp else 0.dp, if (f) Color.White else Color.Transparent, RoundedCornerShape(10.dp))
                        .combinedClickable(onClick = { if (item != null) audio = i else pickAt(minOf(i, slots.size)) }, onLongClick = { if (item != null) menuFor = i else pickAt(minOf(i, slots.size)) })
                ) {
                    if (item != null) androidx.compose.runtime.key(item.id) { SecondStream(item, split = false, audioOn = audio == i, modifier = Modifier.fillMaxSize(), small = full != i) }
                    if (item != null && audio == i) Box(Modifier.align(Alignment.TopEnd).padding(10.dp).background(Color(0xCC000000), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                        Text(raw = true, text = "🔊", fontSize = 14.sp, color = Color.White)
                    }
                    if (item != null) TileScore(item, live, Modifier.align(Alignment.TopStart).padding(10.dp))
                    else Text("OK = pick a channel", color = Color(0xFFA0A6B8), fontSize = 16.sp, modifier = Modifier.align(Alignment.Center))
                }
            }
            if (full >= 0) Box(Modifier.weight(1f).fillMaxWidth()) { tile(full, Modifier.fillMaxSize()) }
            else when (layout) {
                MultiViewLogic.QUAD -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        tile(0, Modifier.weight(1f).fillMaxSize()); tile(1, Modifier.weight(1f).fillMaxSize())
                    }
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        tile(2, Modifier.weight(1f).fillMaxSize()); tile(3, Modifier.weight(1f).fillMaxSize())
                    }
                }
                MultiViewLogic.BIG3 -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    tile(0, Modifier.weight(2f).fillMaxWidth())
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        tile(1, Modifier.weight(1f).fillMaxSize()); tile(2, Modifier.weight(1f).fillMaxSize()); tile(3, Modifier.weight(1f).fillMaxSize())
                    }
                }
                else -> Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tile(0, Modifier.weight(if (layout == MultiViewLogic.GRID) 1f else 2f).fillMaxSize())
                    tile(1, Modifier.weight(1f).fillMaxSize())
                }
            }
        }
        if (menuFor >= 0) {
            val i = menuFor
            val acts = listOf("Make audio active", if (full == i) "Back to all games" else "Fullscreen", "Replace game", "Remove", "Move to the big tile")
            OptionPopup(acts, -1, { menuFor = -1 }) { a ->
                menuFor = -1
                when (a) {
                    0 -> audio = i
                    1 -> full = if (full == i) -1 else i
                    2 -> pickAt(i)
                    3 -> if (slots.size > 1 && i < slots.size) { slots.removeAt(i); if (audio >= slots.size) audio = 0; if (full >= slots.size) full = -1 }
                    4 -> if (i in 1 until slots.size) { val t = slots[0]; slots[0] = slots[i]; slots[i] = t; if (audio == i) audio = 0 else if (audio == 0) audio = i }
                }
            }
        }
        if (gamePick >= 0) {
            val labels = liveGames.map { (g, _) -> (if (g.solo) g.title.ifBlank { g.league } else g.away.short + " vs " + g.home.short) + "  ·  " + g.league + "  ·  " + g.detail } + "Search all channels …"
            OptionPopup(labels, -1, { gamePick = -1 }) { a ->
                val p = gamePick
                gamePick = -1
                if (a >= liveGames.size) picking = p
                else { val ch = liveGames[a].second; if (p in 0 until slots.size) slots[p] = ch else if (p == slots.size && slots.size < cap) slots.add(ch) }
            }
        }
        if (picking >= 0) GuideSearchDialog(live, onPick = { ch ->
            val p = picking
            if (p in 0 until slots.size) slots[p] = ch else if (p == slots.size) slots.add(ch)
            picking = -1
        }, onClose = { picking = -1 })
    }
}


/** Score of the game running on this channel (if the sports data knows one), as a small chip on the tile. */
@Composable
private fun TileScore(ch: Item, live: List<Item>, modifier: Modifier) {
    // The session already knows which game a channel carries; otherwise matched once (not on every redraw)
    val g = SportSession.game(ch.id) ?: androidx.compose.runtime.remember(ch.id, SportFeed.latest.size) {
        SportFeed.latest.values.firstOrNull { g -> g.state == 1 && !g.solo && SportWatch.channels(g, live).any { it.id == ch.id } }
    } ?: return
    val txt = if (SportPrefs.hideScores) g.away.short + " at " + g.home.short + "  ·  " + g.detail
        else g.away.short + " " + g.away.score.orEmpty() + " – " + g.home.score.orEmpty() + " " + g.home.short + "  ·  " + g.detail
    Box(modifier.background(Color(0xCC000000), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 5.dp)) {
        Text(txt, color = if (GameDayLogic.isClose(g)) Color(0xFFFFA284) else Color.White, fontSize = 15.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, maxLines = 1, raw = true)
    }
}
