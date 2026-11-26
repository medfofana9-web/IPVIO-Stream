package com.ipvio.stream

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

/**
 * Calendar, Option B: the teams and shows you follow, in a list beside the calendar. Teams are your favorite teams
 * (the same ones Sports uses); shows are picked here (until you pick any: your favorites and My List). Synced.
 */
object CalFollow {
    var version by mutableIntStateOf(0)
        private set
    @Volatile var dirty = false
    private fun prefs(c: Context) = Profiles.sp(c, "fof_calendar")
    fun init(c: Context) {}

    /** Followed show ids; null in the store = never picked, so favorites and My List stand in. */
    fun shows(c: Context): List<String> {
        val raw = prefs(c).getString("shows", null)
        if (raw == null) return (Library.favs + Library.watch).distinct()
        return raw.split('\n').filter { it.isNotBlank() }
    }

    fun isShow(c: Context, id: String) = id in shows(c)

    fun toggleShow(c: Context, item: Item) {
        val l = shows(c).toMutableList()
        if (!l.remove(item.id)) {
            l.add(item.id); CatalogItems.put(c, item)
            Library.init(c); if (!Library.isFav(item.id)) Library.toggleFav(c, item.id)   // tracked as a favorite app-wide
        }
        prefs(c).edit().putString("shows", l.joinToString("\n")).apply()
        CloudSync.kick(c)
        dirty = true; version++
    }

    fun toggleTeam(c: Context, t: SportData.Team, league: SportData.League?) {
        if (league != null) SportHubFavs.toggleTeam(c, league, t) else SportFavs.toggle(c, t)
        dirty = true; version++
    }
}

private fun teamLabel(key: String): String =
    SportHubFavs.teams[key]?.name ?: key.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

private fun tint(s: String): Color {
    val c = listOf(0xFF1D428A, 0xFFC8102E, 0xFF2E9D5B, 0xFFF08C2B, 0xFF7C4DFF, 0xFF00A3A3, 0xFF552583)
    return Color(c[(s.hashCode() and 0x7fffffff) % c.size])
}

/** Left list: teams and shows followed; OK twice on one unfollows it; the last row adds more. */
@Composable
internal fun FollowRail(shows: List<Item>, onAdd: () -> Unit) {
    val ctx = LocalContext.current
    SportFavs.init(ctx); SportHubFavs.init(ctx)
    @Suppress("UNUSED_VARIABLE") val v = CalFollow.version
    Column(
        Modifier.width(dpx(380)).fillMaxHeight().background(Color(0xFF0F1218)).verticalScroll(rememberScrollState())
            .padding(start = DS.PageX, end = dpx(18), top = dpx(40), bottom = dpx(40)),
        verticalArrangement = Arrangement.spacedBy(dpx(4))
    ) {
        Text(raw = true, text = "Following", color = Color.White, fontSize = spx(30), fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(start = dpx(8), bottom = dpx(6)))
        RailHead("TEAMS")
        if (SportFavs.keys.isEmpty()) RailNote("No teams yet")
        SportFavs.keys.toList().forEach { k ->
            val fav = SportHubFavs.teams[k]
            RailRow(teamLabel(k), fav?.logo, true) {
                val lg = fav?.let { f -> SportData.LEAGUES.firstOrNull { it.path == f.path } }
                if (fav != null && lg != null) SportHubFavs.toggleTeam(ctx, lg, fav.team())
                else {
                    SportFavs.keys.remove(k)
                    Profiles.sp(ctx, "ipvio_sportfavs").edit().putString("k", SportFavs.keys.joinToString("|")).apply()
                    CloudSync.kick(ctx)
                }
                CalFollow.dirty = true
            }
        }
        RailHead("SHOWS")
        if (shows.isEmpty()) RailNote("No shows yet")
        shows.forEach { s ->
            val m = rememberMeta(s)
            RailRow(s.title, m?.poster?.let { cardPoster(it) } ?: s.logo, false) { CalFollow.toggleShow(ctx, s) }
        }
        Box(Modifier.padding(top = dpx(14))) { RailAdd(onAdd) }
    }
}

@Composable private fun RailHead(t: String) =
    Text(raw = true, text = t, color = Color(0xFF8A8F99), fontSize = spx(13), fontWeight = FontWeight.ExtraBold, letterSpacing = 1.6.sp,
        modifier = Modifier.padding(start = dpx(8), top = dpx(18), bottom = dpx(4)))

@Composable private fun RailNote(t: String) =
    Text(raw = true, text = t, color = Color(0xFF777777), fontSize = spx(15), modifier = Modifier.padding(start = dpx(8)))

@Composable
private fun RailRow(name: String, img: String?, round: Boolean, onUnfollow: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) { if (armed) { delay(4000); armed = false } }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(dpx(12))).background(if (f) Color.White else Color.Transparent)
            .onFocusChanged { f = it.isFocused; if (!it.isFocused) armed = false }.trackFocus()
            .clickable { if (armed) { armed = false; onUnfollow() } else armed = true }
            .padding(horizontal = dpx(8), vertical = dpx(7)),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(12))
    ) {
        val shape = if (round) CircleShape else RoundedCornerShape(dpx(6))
        Box(Modifier.size(if (round) dpx(38) else dpx(30), dpx(38)).clip(shape).background(tint(name)), contentAlignment = Alignment.Center) {
            if (img != null) AsyncImage(img, null, Modifier.size(if (round) dpx(30) else dpx(30), dpx(38)), contentScale = if (round) ContentScale.Fit else ContentScale.Crop)
            else Text(raw = true, text = name.take(2).uppercase(), color = Color.White, fontSize = spx(12), fontWeight = FontWeight.Black)
        }
        Text(raw = true, text = if (armed) "OK again to unfollow" else name, color = if (f) (if (armed) Color(0xFFC8102E) else Color.Black) else Color(0xFFDDDDDD),
            fontSize = spx(17), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RailAdd(onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(dpx(12))
    Box(
        Modifier.fillMaxWidth().clip(shape).background(if (f) Color.White else Color.Transparent).border(2.dp, if (f) Color.White else Color(0x33FFFFFF), shape)
            .onFocusChanged { f = it.isFocused }.trackFocus().clickable(onClick = onClick).padding(vertical = dpx(12)),
        contentAlignment = Alignment.Center
    ) { Text(raw = true, text = "+ Follow a team or show", color = if (f) Color.Black else Color(0xFFAAAAAA), fontSize = spx(16), fontWeight = FontWeight.SemiBold) }
}

/** Pick teams (from your sports leagues) and shows (from the catalog and your playlist), with search. */
@Composable
internal fun FollowPicker(all: List<Item>, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var q by remember { mutableStateOf("") }
    @Suppress("UNUSED_VARIABLE") val v = CalFollow.version
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
    val teams by produceState<List<TeamPick>>(emptyList()) {
        SportLeagues.init(ctx)
        val names = SportLeagues.enabled().filter { it.kind == SportData.KIND_TEAM }.map { it.name }.take(12)
        value = runCatching { loadTeams(names) }.getOrDefault(emptyList())
    }
    val shows = remember(all) { (all.filter { it.kind == Kind.SERIES } + CatalogItems.all().filter { it.kind == Kind.SERIES }).distinctBy { it.title.lowercase() } }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(dpx(760)).clip(RoundedCornerShape(dpx(20))).background(Color(0xFF10141C)).padding(dpx(22)),
            verticalArrangement = Arrangement.spacedBy(dpx(12))
        ) {
            Text(raw = true, text = "Follow a team or show", color = Color.White, fontSize = spx(26), fontWeight = FontWeight.ExtraBold)
            Row(horizontalArrangement = Arrangement.spacedBy(dpx(10))) {
                PickTab("Teams", tab == 0, Modifier.focusRequester(first)) { tab = 0 }
                PickTab("Shows", tab == 1, Modifier) { tab = 1 }
                PickTab("Done", false, Modifier) { onClose() }
            }
            Field(q, { q = it.take(40) }, if (tab == 0) "Search teams" else "Search shows")
            val needle = q.trim().lowercase()
            LazyColumn(Modifier.height(dpx(480)).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(dpx(4))) {
                if (tab == 0) {
                    val l = teams.filter { needle.isEmpty() || it.team.name.lowercase().contains(needle) }.take(150)
                    if (teams.isEmpty()) item { Text(raw = true, text = "Loading teams…", color = Color(0xFF999999), fontSize = spx(16)) }
                    l.forEach { p -> item(key = "t" + p.team.id + p.league) {
                        val on = SportFavs.has(p.team)
                        PickRow(p.team.name, p.league, p.team.logo, on, true) { CalFollow.toggleTeam(ctx, p.team, SportData.league(p.league)) }
                    } }
                } else {
                    val l = shows.filter { needle.isEmpty() || it.title.lowercase().contains(needle) }.take(150)
                    l.forEach { s -> item(key = "s" + s.id) {
                        PickRow(s.title, s.year?.toString().orEmpty(), s.logo, CalFollow.isShow(ctx, s.id), false) { CalFollow.toggleShow(ctx, s) }
                    } }
                }
            }
        }
    }
}

@Composable
private fun PickTab(label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    Box(
        modifier.clip(RoundedCornerShape(50)).background(if (f) Color.White else if (on) Color(0x40FFFFFF) else Color(0x14FFFFFF))
            .onFocusChanged { f = it.isFocused }.clickable(onClick = onClick).padding(horizontal = dpx(20), vertical = dpx(10))
    ) { Text(raw = true, text = label, color = if (f) Color.Black else Color.White, fontSize = spx(17), fontWeight = FontWeight.Bold) }
}

@Composable
private fun PickRow(name: String, sub: String, img: String?, on: Boolean, round: Boolean, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(dpx(12))).background(if (f) Color.White else Color(0xFF151A24))
            .onFocusChanged { f = it.isFocused }.clickable(onClick = onClick).padding(horizontal = dpx(12), vertical = dpx(9)),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(14))
    ) {
        Box(Modifier.size(dpx(36)).clip(if (round) CircleShape else RoundedCornerShape(dpx(6))).background(tint(name)), contentAlignment = Alignment.Center) {
            if (img != null) AsyncImage(img, null, Modifier.size(dpx(30)), contentScale = ContentScale.Fit)
        }
        Column(Modifier.weight(1f)) {
            Text(raw = true, text = name, color = if (f) Color.Black else Color.White, fontSize = spx(17), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotBlank()) Text(raw = true, text = sub, color = if (f) Color(0xFF555555) else Color(0xFF999999), fontSize = spx(13), maxLines = 1)
        }
        Text(raw = true, text = if (on) "✓ Following" else "+ Follow", color = if (f) Color.Black else if (on) Color(0xFF7BE39A) else Color(0xFFAAAAAA), fontSize = spx(15), fontWeight = FontWeight.Bold)
    }
}
