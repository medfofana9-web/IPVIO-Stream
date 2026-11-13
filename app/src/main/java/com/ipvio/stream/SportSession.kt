package com.ipvio.stream

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Fof TV: a game started from the sports area. Knows the channels that can carry each live game, so the player can
 *  - move to the next channel by itself when a stream does not start (failover),
 *  - switch between live games with Up/Down instead of channel numbers,
 *  - show the score of the game on screen and a ticker of the other live games.
 * Channels that started fine are remembered as "verified".
 */
object SportSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Game keys in Up/Down order. */
    @Volatile private var order: List<String> = emptyList()
    private val cands = ConcurrentHashMap<String, List<Item>>()
    private val pick = ConcurrentHashMap<String, Item>()
    private val gameOfCh = ConcurrentHashMap<String, String>()
    private val failed = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** Counts up when the game list changes (overlays recompose). */
    var version by mutableIntStateOf(0)
        private set
    /** Channel shown in the player right now (set by the player), null outside the player. */
    var watching by mutableStateOf<String?>(null)
    /** Player controls or a menu are open: the overlays step aside. */
    var busy by mutableStateOf(false)
    /** Last switch time: the ticker shows for a few seconds after a switch. */
    var switchedAt by mutableStateOf(0L)

    @Volatile private var app: Context? = null
    fun init(c: Context) { app = c.applicationContext }

    private var okSet: MutableSet<String>? = null
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("fof_sport_ok", Context.MODE_PRIVATE)
    private fun ok(c: Context): MutableSet<String> =
        okSet ?: (prefs(c).getStringSet("ids", emptySet())?.toMutableSet() ?: HashSet()).also { okSet = it }

    /** Channel started a picture before: shown as "verified" on the Watch Live page. */
    fun isVerified(c: Context, chId: String) = chId in ok(c)

    /** The player had a picture on this channel. */
    fun started(c: Context, chId: String) {
        if (!owns(chId)) return
        val s = ok(c)
        if (s.add(chId)) prefs(c).edit().putStringSet("ids", HashSet(s)).apply()
    }

    @Volatile private var startedAt = 0L
    /** A session older than 6 hours is over (the games have ended). */
    /** Game tile pressed last (focus key); taken over by [start], so Back from the player lands on that tile. */
    @Volatile var lastTile: String? = null
    @Volatile var returnKey: String? = null

    fun owns(chId: String?) = chId != null && System.currentTimeMillis() - startedAt < 6 * 3600_000L && gameOfCh.containsKey(chId)

    /** Ends the session (a channel outside it was started). */
    fun stop() { returnKey = null; order = emptyList(); cands.clear(); pick.clear(); gameOfCh.clear(); failed.clear() }

    /** Starts a session for [g] with its [first] channels; other live games are added in the background. */
    fun start(g: SportData.Game, first: List<Item>, live: List<Item>) {
        val verified = app?.let { ok(it) } ?: emptySet()
        // Channels that worked before go first (same reason order otherwise)
        val sorted = first.sortedBy { if (it.id in verified) 0 else 1 }
        startedAt = System.currentTimeMillis()
        returnKey = lastTile ?: "game:" + g.key
        lastTile = null
        order = listOf(g.key)
        cands.clear(); pick.clear(); gameOfCh.clear(); failed.clear()
        cands[g.key] = sorted
        sorted.firstOrNull()?.let { pick[g.key] = it }
        sorted.forEach { gameOfCh.putIfAbsent(it.id, g.key) }
        switchedAt = System.currentTimeMillis()
        version++
        scope.launch { addLiveGames(g, live, verified) }
    }

    /** First channel to play for the game, verified channels first. */
    fun firstChannel(): Item? = order.firstOrNull()?.let { pick[it] }

    private suspend fun addLiveGames(start: SportData.Game, live: List<Item>, verified: Set<String>) {
        runCatching { SportWatch.prewarm(live) }
        val others = SportFeed.latest.values.filter { it.state == 1 && it.key != start.key }
            .sortedWith(compareBy({ it.league }, { it.start })).take(30)
        val keys = ArrayList<String>()
        keys.add(start.key)
        for (o in others) {
            val ch = runCatching { SportWatch.channels(o, live) }.getOrDefault(emptyList()).sortedBy { if (it.id in verified) 0 else 1 }
            if (ch.isEmpty()) continue
            cands[o.key] = ch
            pick.putIfAbsent(o.key, ch[0])
            ch.forEach { gameOfCh.putIfAbsent(it.id, o.key) }
            keys.add(o.key)
        }
        order = keys
        kotlinx.coroutines.withContext(Dispatchers.Main) { version++ }
    }

    /** Up/Down list: one channel per live game. */
    fun zapList(): List<Item> = order.mapNotNull { pick[it] }

    fun game(chId: String?): SportData.Game? = chId?.let { gameOfCh[it] }?.let { SportFeed.latest[it] }

    fun games(): List<SportData.Game> = order.mapNotNull { SportFeed.latest[it] }

    fun channelOf(g: SportData.Game): Item? = pick[g.key]

    /** Every stream found for the game that [chId] belongs to (channels that worked before first). */
    fun streams(chId: String?): List<Item> = chId?.let { gameOfCh[it] }?.let { cands[it] }.orEmpty()

    /** [ch] becomes the game's stream: Up/Down and the ticker come back to it. */
    fun choose(ch: Item) { gameOfCh[ch.id]?.let { k -> pick[k] = ch; failed.remove(ch.id) } }

    /** Next channel for the same game after [chId] did not start; null when none is left. */
    fun next(chId: String): Item? {
        val k = gameOfCh[chId] ?: return null
        failed.add(chId)
        val n = cands[k]?.firstOrNull { it.id !in failed } ?: return null
        pick[k] = n
        return n
    }
}

private fun scoreText(t: SportData.Team): String = if (SportPrefs.hideScores) "" else t.score.orEmpty()

internal fun teamColor(t: SportData.Team): Color = runCatching {
    val h = t.color?.removePrefix("#") ?: return Color(0xFF2A2A30)
    if (h.length != 6) Color(0xFF2A2A30) else Color(("FF$h").toLong(16))
}.getOrDefault(Color(0xFF2A2A30))

/**
 * Score bug (top right) for the game on screen, and a ticker of the other live games for a few seconds after a switch.
 * Shown above the player; takes no focus.
 */
@Composable
fun BoxScope.SportPlayerOverlay() {
    val ch = SportSession.watching ?: return
    @Suppress("UNUSED_VARIABLE") val v = SportSession.version
    val g = SportSession.game(ch) ?: return
    if (!SportSession.busy) Box(Modifier.align(Alignment.TopEnd).padding(top = 26.dp, end = 34.dp)) { ScoreBug(g) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(SportSession.switchedAt) { tick++; delay(7000); tick++ }
    val showTicker = System.currentTimeMillis() - SportSession.switchedAt < 7000 && !SportSession.busy
    if (showTicker && tick >= 0) {
        val games = SportSession.games()
        if (games.size > 1) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 34.dp, vertical = 24.dp)) {
            Ticker(games, g.key)
        }
    }
}

@Composable
private fun ScoreBug(g: SportData.Game) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.clip(shape).background(Color(0xE60B0B0D)).border(1.dp, Color(0x26FFFFFF), shape),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (g.solo) {
            Text(raw = true, text = g.title.ifBlank { g.league }, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp), maxLines = 1)
        } else {
            BugTeam(g.away); BugTeam(g.home)
        }
        Text(raw = true, text = g.detail, color = Color(0xFFE6E6EA), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 12.dp), maxLines = 1)
    }
}

@Composable
private fun BugTeam(t: SportData.Team) {
    Row(Modifier.background(teamColor(t).copy(alpha = 0.85f)).padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        if (t.logo != null) AsyncImage(t.logo, null, Modifier.size(22.dp))
        Text(raw = true, text = t.abbr.ifBlank { t.short }, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp))
        val s = scoreText(t)
        if (s.isNotEmpty()) Text(raw = true, text = s, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun Ticker(games: List<SportData.Game>, current: String) {
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(Color(0xE60B0B0D)).border(1.dp, Color(0x1FFFFFFF), shape).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(raw = true, text = "Live now  ·  Up / Down switches game", color = Color(0xFF9A9AA2), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        val i = games.indexOfFirst { it.key == current }.coerceAtLeast(0)
        // The current game in the middle, neighbours around it
        val from = (i - 2).coerceIn(0, maxOf(0, games.size - 5))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            games.drop(from).take(5).forEach { g ->
                val on = g.key == current
                Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (on) Color(0xFFE50914) else Color(0xFF1C1C21))
                    .padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Column {
                        val line = if (g.solo) g.title.ifBlank { g.league }
                            else g.away.abbr.ifBlank { g.away.short } + " " + scoreText(g.away) + "  " + g.home.abbr.ifBlank { g.home.short } + " " + scoreText(g.home)
                        Text(raw = true, text = line, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(raw = true, text = g.detail, color = if (on) Color.White else Color(0xFF9A9AA2), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** Other streams of the game being watched; OK switches, Back closes. */
@Composable
fun GameStreamPicker(title: String, streams: List<Item>, currentId: String?, onPick: (Item) -> Unit, onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.ui.window.Popup(
        alignment = Alignment.Center, onDismissRequest = onClose,
        properties = androidx.compose.ui.window.PopupProperties(focusable = true)
    ) {
        val fr = remember { androidx.compose.ui.focus.FocusRequester() }
        LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
        val start = streams.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        val shape = RoundedCornerShape(16.dp)
        Column(
            Modifier.width(520.dp).heightIn(max = 460.dp).clip(shape).background(Color(0xF2141418))
                .border(1.dp, Color(0x33FFFFFF), shape).padding(14.dp)
        ) {
            Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, raw = true, modifier = Modifier.padding(start = 6.dp, bottom = 4.dp))
            Text("Streams found for this game", color = Color(0x99FFFFFF), fontSize = 14.sp, raw = true, modifier = Modifier.padding(start = 6.dp, bottom = 10.dp))
            androidx.compose.foundation.lazy.LazyColumn(state = androidx.compose.foundation.lazy.rememberLazyListState(maxOf(start - 2, 0))) {
                items(streams.size) { i ->
                    val ch = streams[i]
                    val note = when {
                        ch.id == currentId -> "  ·  playing"
                        SportSession.isVerified(ctx, ch.id) -> "  ·  worked before"
                        else -> ""
                    }
                    OptionRow(ch.title + note, ch.id == currentId,
                        if (i == start) Modifier.focusRequester(fr) else Modifier) { onPick(ch) }
                }
            }
        }
    }
}
