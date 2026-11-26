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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Calendar (Fof TV): new episodes of your shows (favorites, My List, Continue Watching), new movies, your teams' games.
 * Episode dates come from TMDB (cached for 6 hours), games from ESPN like the sports area. Times in the TV's time zone.
 */
object CalendarData {
    /** kind: 0 show, 1 movie, 2 game. [sec] = start in epoch seconds (0 = only the day is known). */
    class Entry(val day: LocalDate, val sec: Long, val title: String, val sub: String, val kind: Int, val image: String?,
                val item: Item?, val game: SportData.Game? = null, val mine: Boolean = true)

    private var cache: Pair<Long, List<Entry>>? = null
    private var airing: Pair<Long, List<Entry>>? = null

    private fun json(url: String): JSONObject? = runCatching {
        Net.base.newCall(Request.Builder().url(url).build()).execute().use { r -> if (r.isSuccessful) r.body?.string()?.let { JSONObject(it) } else null }
    }.getOrNull()
    private fun img(p: String?) = p?.takeIf { it.isNotBlank() && it != "null" }?.let { "https://image.tmdb.org/t/p/w500$it" }
    private fun date(s: String?) = runCatching { LocalDate.parse(s) }.getOrNull()

    /** My calendar: this and next month for your shows, upcoming movies, your teams' next 7 days. */
    suspend fun mine(ctx: android.content.Context, shows: List<Item>, movies: List<Item>): List<Entry> = withContext(Dispatchers.IO) {
        cache?.let { if (System.currentTimeMillis() - it.first < 6 * 3600_000L) return@withContext it.second }
        if (!MetaRepo.enabled()) return@withContext emptyList()
        val key = Config.TMDB_KEY
        val out = ArrayList<Entry>()
        coroutineScope {
            val showJobs = shows.distinctBy { it.id }.take(30).map { s -> async {
                val m = MetaRepo.basic(ctx, s) ?: return@async emptyList<Entry>()
                val tv = json("https://api.themoviedb.org/3/tv/${m.tmdbId}?api_key=$key&language=en-US") ?: return@async emptyList()
                val season = (tv.optJSONObject("next_episode_to_air") ?: tv.optJSONObject("last_episode_to_air"))?.optInt("season_number") ?: return@async emptyList()
                val se = json("https://api.themoviedb.org/3/tv/${m.tmdbId}/season/$season?api_key=$key&language=en-US") ?: return@async emptyList()
                val eps = se.optJSONArray("episodes") ?: return@async emptyList()
                val net = tv.optJSONArray("networks")?.optJSONObject(0)?.optString("name").orEmpty()
                (0 until eps.length()).mapNotNull { i ->
                    val e = eps.getJSONObject(i)
                    val d = date(e.optString("air_date")) ?: return@mapNotNull null
                    Entry(d, 0, s.title, "S${e.optInt("season_number")} · E${e.optInt("episode_number")}" + (if (net.isNotBlank()) " · $net" else ""),
                        0, img(e.optString("still_path")) ?: m.backdrop, s)
                }
            } }
            val movieJob = async {
                val up = json("https://api.themoviedb.org/3/movie/upcoming?api_key=$key&language=en-US&region=US")?.optJSONArray("results")
                (0 until (up?.length() ?: 0)).mapNotNull { i ->
                    val o = up!!.getJSONObject(i)
                    val d = date(o.optString("release_date")) ?: return@mapNotNull null
                    Entry(d, 0, o.optString("title"), "In theaters", 1, img(o.optString("backdrop_path")), null, mine = false)
                }
            }
            val gameJob = async {
                val zone = General.zone()
                (0..6).map { d -> async {
                    val ymd = SportData.ymd(d, zone)
                    SportLeagues.enabled().filter { it.kind == SportData.KIND_TEAM }.map { l -> async { runCatching { SportData.fetch(l, ymd) }.getOrDefault(emptyList()) } }.awaitAll().flatten()
                } }.awaitAll().flatten().filter { SportFavs.isFavGame(it) || SportHubFavs.isFavGame(it) }.distinctBy { it.key }.map { g ->
                    Entry(Instant.ofEpochSecond(g.start).atZone(ZoneId.systemDefault()).toLocalDate(), g.start,
                        g.away.short + " at " + g.home.short, g.league, 2, null, null, g)
                }
            }
            out += showJobs.awaitAll().flatten(); out += movieJob.await(); out += gameJob.await()
        }
        val r = out.sortedWith(compareBy({ it.day }, { it.sec }))
        if (r.isNotEmpty()) cache = System.currentTimeMillis() to r
        r
    }

    /** Airing today: what TMDB lists as on TV today (US), matched to your playlist when the title is there. */
    suspend fun airingToday(byTitle: Map<String, Item>): List<Entry> = withContext(Dispatchers.IO) {
        airing?.let { if (System.currentTimeMillis() - it.first < 3 * 3600_000L) return@withContext it.second }
        if (!MetaRepo.enabled()) return@withContext emptyList()
        val a = json("https://api.themoviedb.org/3/tv/airing_today?api_key=${Config.TMDB_KEY}&language=en-US&timezone=America/New_York")?.optJSONArray("results")
        val today = LocalDate.now()
        val r = (0 until (a?.length() ?: 0)).map { i ->
            val o = a!!.getJSONObject(i)
            val t = o.optString("name")
            Entry(today, 0, t, "Airing today", 0, img(o.optString("backdrop_path")), byTitle[t.lowercase(Locale.ROOT)], mine = false)
        }
        if (r.isNotEmpty()) airing = System.currentTimeMillis() to r
        r
    }
}

@Composable
internal fun CalendarScreen(all: List<Item>, onOpen: (Item) -> Unit) {
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }       // 0 My calendar, 1 Airing today, 2 Following
    var filter by remember { mutableIntStateOf(0) }    // 0 All, 1 Shows, 2 Movies, 3 Sports
    var view by remember { mutableIntStateOf(2) }      // 0 List, 1 Week, 2 Month
    var day by remember { mutableStateOf(LocalDate.now()) }
    val byId = remember(all) { all.associateBy { it.id } }
    val mineItems = remember(byId, Library.favs.size, Library.watch.size) {
        (Library.favs + Library.watch).mapNotNull { byId[it] ?: CatalogItems.get(it) }.filter { it.kind != Kind.LIVE }
    }
    val entries by produceState<List<CalendarData.Entry>?>(null, mineItems.size) {
        value = CalendarData.mine(ctx, mineItems.filter { it.kind == Kind.SERIES }, mineItems.filter { it.kind == Kind.MOVIE })
    }
    val airing by produceState<List<CalendarData.Entry>>(emptyList(), tab == 1) {
        if (tab == 1) value = CalendarData.airingToday(all.filter { it.kind == Kind.SERIES }.associateBy { it.title.lowercase(Locale.ROOT) })
    }
    val base = when (tab) { 1 -> airing; else -> entries.orEmpty() }
    val list = base.filter { filter == 0 || it.kind == filter - 1 }
    val open: (CalendarData.Entry) -> Unit = { e -> e.item?.let(onOpen) ?: e.game?.let { g -> openInHub(HubPage.Match(g)) } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = DS.PageX, end = DS.PageX, top = dpx(36), bottom = dpx(80)),
        verticalArrangement = Arrangement.spacedBy(dpx(16))) {
        item(key = "head") {
            Column(verticalArrangement = Arrangement.spacedBy(dpx(14))) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("Calendar", color = Color.White, fontSize = spx(44), fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.width(dpx(16)))
                    Text("Times in your time zone", color = Color(0xFF999999), fontSize = spx(17), modifier = Modifier.padding(bottom = dpx(8)))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(10))) {
                    listOf("My calendar", "Airing today", "Following").forEachIndexed { i, t -> CalPill(t, tab == i) { tab = i } }
                    Spacer(Modifier.weight(1f))
                    if (tab == 0) listOf("List", "Week", "Month").forEachIndexed { i, t -> CalPill(t, view == i, blue = true) { view = i } }
                }
                if (tab != 2) Row(horizontalArrangement = Arrangement.spacedBy(dpx(10))) {
                    listOf("All", "Shows", "Movies", "Sports").forEachIndexed { i, t -> CalPill(t, filter == i) { filter = i } }
                }
            }
        }
        if (entries == null && tab != 1) { item(key = "load") { HubLoading("Loading your calendar…") }; return@LazyColumn }
        when {
            tab == 2 -> {
                // Following: each of your shows with its next new episode
                val next = list.filter { it.kind == 0 && !it.day.isBefore(LocalDate.now()) }.groupBy { it.title }.map { it.value.first() }
                val rest = mineItems.filter { it.kind == Kind.SERIES && next.none { n -> n.title == it.title } }
                item(key = "fh") { CalHeader("Coming back", "${next.size} with a date") }
                item(key = "fr") { CalRow(next, open) }
                if (rest.isNotEmpty()) {
                    item(key = "fh2") { CalHeader("No new episode date yet", "") }
                    item(key = "fr2") { CalRow(rest.map { CalendarData.Entry(LocalDate.now(), 0, it.title, "No date announced", 0, it.logo, it) }, open) }
                }
            }
            tab == 1 -> {
                item(key = "ah") { CalHeader("Airing today", dayTitle(LocalDate.now())) }
                list.chunked(4).forEachIndexed { r, row -> item(key = "ar$r") { CalRow(row, open) } }
            }
            view == 0 -> {
                // List: from today on, one row per day
                list.filter { !it.day.isBefore(LocalDate.now()) }.groupBy { it.day }.toSortedMap().entries.take(21).forEach { (d, l) ->
                    item(key = "lh$d") { CalHeader(dayTitle(d), "${l.size}") }
                    item(key = "lr$d") { CalRow(l, open) }
                }
            }
            else -> {
                val sel = list.filter { it.day == day }
                item(key = "dh") { CalHeader(dayTitle(day), if (sel.isEmpty()) "Nothing this day" else "${sel.size} on this day") }
                if (sel.isNotEmpty()) item(key = "dr$day") { CalRow(sel, open) }
                val start = if (view == 1) day.minusDays((day.dayOfWeek.value % 7).toLong())
                    else day.withDayOfMonth(1).let { it.minusDays((it.dayOfWeek.value % 7).toLong()) }
                val weeks = if (view == 1) 1 else 6
                item(key = "month") {
                    Column(verticalArrangement = Arrangement.spacedBy(dpx(8))) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (view == 2) Text(raw = true, text = day.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)), color = Color.White, fontSize = spx(22), fontWeight = FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            CalPill("‹ Previous", false) { day = if (view == 1) day.minusWeeks(1) else day.minusMonths(1).withDayOfMonth(1) }
                            Spacer(Modifier.width(dpx(8)))
                            CalPill("Today", false) { day = LocalDate.now() }
                            Spacer(Modifier.width(dpx(8)))
                            CalPill("Next ›", false) { day = if (view == 1) day.plusWeeks(1) else day.plusMonths(1).withDayOfMonth(1) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(dpx(10))) {
                            listOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT").forEach { Text(raw = true, text = it, color = Color(0xFF999999), fontSize = spx(14), modifier = Modifier.weight(1f)) }
                        }
                        val byDay = list.groupBy { it.day }
                        for (w in 0 until weeks) Row(horizontalArrangement = Arrangement.spacedBy(dpx(10))) {
                            for (c in 0..6) {
                                val d = start.plusDays((w * 7 + c).toLong())
                                DayCell(d, byDay[d].orEmpty(), d == day, view == 2 && d.month != day.month, Modifier.weight(1f).height(dpx(if (view == 1) 150 else 76))) { day = d }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun dayTitle(d: LocalDate): String {
    val t = LocalDate.now()
    val name = d.format(DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.US))
    return when (d) { t -> "Today · $name"; t.plusDays(1) -> "Tomorrow · $name"; else -> name }
}

@Composable
private fun CalHeader(title: String, sub: String) {
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = dpx(6))) {
        Text(raw = true, text = title, color = Color.White, fontSize = spx(26), fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.width(dpx(12)))
        Text(raw = true, text = sub, color = Color(0xFF999999), fontSize = spx(16), modifier = Modifier.padding(bottom = dpx(3)))
    }
}

@Composable
private fun CalPill(text: String, on: Boolean, blue: Boolean = false, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Text(text, color = if (on && !blue) Color.Black else Color.White, fontSize = spx(18), fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
        modifier = Modifier.clip(shape)
            .background(if (on) (if (blue) Color(0xFF4D8BFF) else Color.White) else Color(0xFF1A2030))
            .border(3.dp, if (f) Color.White else Color.Transparent, shape)
            .onFocusChanged { f = it.isFocused }.clickable(onClick = onClick)
            .padding(horizontal = dpx(18), vertical = dpx(8)))
}

@Composable
private fun CalRow(l: List<CalendarData.Entry>, open: (CalendarData.Entry) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(dpx(16)), contentPadding = PaddingValues(vertical = dpx(6))) {
        items(l) { e -> EventCard(e, Modifier.width(dpx(320))) { open(e) } }
    }
}

@Composable
private fun EventCard(e: CalendarData.Entry, modifier: Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(dpx(12))
    Box(modifier.aspectRatio(16f / 9f).clip(shape).background(Color(0xFF1A2030))
        .then(if (f) Modifier.border(3.dp, Color.White, shape) else Modifier)
        .onFocusChanged { f = it.isFocused }.clickable(onClick = onClick)) {
        val g = e.game
        if (g != null) {
            Column(Modifier.fillMaxSize().padding(dpx(12)), verticalArrangement = Arrangement.spacedBy(dpx(6))) {
                Text(raw = true, text = g.league, color = Color(0xFFBBBBBB), fontSize = spx(13))
                for (t in listOf(g.away, g.home)) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(dpx(6))).background(calColor(t.color)).padding(horizontal = dpx(8), vertical = dpx(4)),
                    verticalAlignment = Alignment.CenterVertically) {
                    TeamLogo(t.logo, t.abbr, dpx(22)); Spacer(Modifier.width(dpx(8)))
                    Text(raw = true, text = t.short.ifBlank { t.name }, color = Color.White, fontSize = spx(17), fontWeight = FontWeight.Bold, maxLines = 1)
                }
                Text(raw = true, text = SportShowcase.time(g.start), color = Color.White, fontSize = spx(14))
            }
        } else {
            if (e.image != null) AsyncImage(e.image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xD9000000))))
            Column(Modifier.align(Alignment.BottomStart).padding(dpx(12))) {
                Text(raw = true, text = e.title, color = Color.White, fontSize = spx(20), fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(raw = true, text = e.sub, color = Color(0xFFDDDDDD), fontSize = spx(13), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun DayCell(d: LocalDate, l: List<CalendarData.Entry>, selected: Boolean, faded: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(dpx(10))
    val first = l.firstOrNull()
    Box(modifier.clip(shape).background(Color(0xFF151A25))
        .border(3.dp, if (f) Color.White else if (selected) Color(0xFF4D8BFF) else Color.Transparent, shape)
        .onFocusChanged { f = it.isFocused; if (it.isFocused) onClick() }.clickable(onClick = onClick)) {
        if (first?.image != null) AsyncImage(first.image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = if (faded) 0.35f else 0.85f)
        else if (first?.game != null) Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(calColor(first.game.away.color), calColor(first.game.home.color)))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x66000000), 1f to Color(0xB3000000))))
        Text(raw = true, text = d.dayOfMonth.toString(), color = if (faded) Color(0x88FFFFFF) else Color.White, fontSize = spx(16), fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(start = dpx(8), top = dpx(4)))
        if (first != null) Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = dpx(8), vertical = dpx(4)), verticalAlignment = Alignment.CenterVertically) {
            Text(raw = true, text = first.title, color = Color.White, fontSize = spx(13), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (l.size > 1) Text(raw = true, text = "+" + (l.size - 1), color = Color.White, fontSize = spx(12),
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0x99000000)).padding(horizontal = dpx(6)))
        }
    }
}

private fun calColor(hex: String?): Color = runCatching {
    val v = hex!!.removePrefix("#").toLong(16)
    val c = Color((0xFF000000 or v).toInt()); Color(c.red * 0.8f, c.green * 0.8f, c.blue * 0.8f)
}.getOrDefault(Color(0xFF2A3142))
