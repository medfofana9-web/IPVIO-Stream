package com.ipvio.stream

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Avatar value (pure): what a profile's picture is, stored as one text.
 *  - "img:<address>|<name>": artwork the app already shows (a scene of a show or movie, a player photo from Sports),
 *    loaded live like the posters.
 *  - "photo:<n>": the person's own photo, uploaded from the phone page (saved on the TV, travels with the account).
 *  - nothing: the first letter of the profile name on a color tile.
 * The app ships no studio, league or player pictures of its own.
 */
object AvatarValue {
    sealed class V {
        class Art(val url: String, val name: String) : V()
        class Photo(val n: Int) : V()
    }

    fun parse(s: String?): V? = when {
        s == null -> null
        s.startsWith("img:") -> s.removePrefix("img:").split('|', limit = 2).let { p ->
            p[0].takeIf { it.startsWith("https://") }?.let { V.Art(it, p.getOrElse(1) { "" }) }
        }
        s.startsWith("photo:") -> s.removePrefix("photo:").toIntOrNull()?.let { V.Photo(it) }
        else -> null
    }

    fun art(url: String, name: String) = "img:" + url + "|" + name.replace('|', ' ').take(60)

    /** Color of the letter tile by profile number. */
    val TILE = listOf(0xFF2D5BE3L, 0xFFC0392BL, 0xFF13A26BL, 0xFF7C4DFFL, 0xFFE07A1FL, 0xFF0E9AA7L, 0xFFD6336CL, 0xFF3F3D9EL)
}

/**
 * Picture of each profile (device-wide prefs "fof_avatars": "a<id>" = [AvatarValue] text). Also travels with the
 * account to other TVs (CloudSync).
 */
/** The Mac app's profile colors, in the same order. */
private val MAC_COLORS = listOf(0xFFE60023L, 0xFF2563EBL, 0xFF16A34AL, 0xFFD97706L, 0xFF7C3AEDL, 0xFFDB2777L, 0xFF0891B2L, 0xFF65A30DL)

object ProfileAvatars {
    /** What to draw: [model] for the image loader (address or file), or null for the letter tile. */
    class Preset(val model: Any?, val name: String, val letter: String, val color: Long)

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("fof_avatars", Context.MODE_PRIVATE)

    /** Bumped on every change so the screens showing avatars redraw. */
    var version by mutableIntStateOf(0)
        private set

    fun photoFile(c: Context, id: Int): File = File(c.applicationContext.filesDir, "avatars/p$id.jpg")

    fun presetFor(c: Context, id: Int): Preset {
        val name = Profiles.list.firstOrNull { it.id == id }?.let { Profiles.displayName(it) } ?: ""
        val letter = name.trim().take(1).uppercase().ifBlank { "?" }
        // Same as the Mac app: a flat color tile with the profile's letter, colors in profile order (1st red, 2nd blue …)
        val pos = Profiles.list.indexOfFirst { it.id == id }.let { if (it < 0) id else it }
        val color = MAC_COLORS[pos.mod(MAC_COLORS.size)]
        return Preset(null, "", letter, color)
    }

    /** Raw stored value (for the account sync). */
    fun keyOf(c: Context, id: Int): String? = prefs(c).getString("a$id", null)

    fun set(c: Context, id: Int, value: String?) {
        if (value == keyOf(c, id) && value?.startsWith("photo:") != true) return
        prefs(c).edit().apply { if (value == null) remove("a$id") else putString("a$id", value) }.apply()
        version++
        CloudSync.kick(c)
    }

    /** Own photo (JPEG bytes, made small on the phone): saved and set. Returns false for anything that is not a picture. */
    /** [fromAccount] = the value saved in the account: stored as is (no new upload, TVs don't take turns). */
    fun savePhoto(c: Context, id: Int, jpeg: ByteArray, fromAccount: String? = null): Boolean {
        val bmp = runCatching { android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) }.getOrNull() ?: return false
        if (bmp.width < 32 || bmp.height < 32) return false
        val f = photoFile(c, id)
        f.parentFile?.mkdirs()
        // Saved again by the TV: only a real picture lands on disk, square, at most 320 px
        val s = minOf(bmp.width, bmp.height)
        val sq = android.graphics.Bitmap.createBitmap(bmp, (bmp.width - s) / 2, (bmp.height - s) / 2, s, s)
        val out = android.graphics.Bitmap.createScaledBitmap(sq, minOf(s, 320), minOf(s, 320), true)
        f.outputStream().use { out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 86, it) }
        if (fromAccount != null) { prefs(c).edit().putString("a$id", fromAccount).apply(); version++ }
        else set(c, id, "photo:" + (System.currentTimeMillis() / 1000 % 1_000_000).toInt())
        return true
    }

    /** Photo bytes for the account sync (null = no photo). */
    fun photoBytes(c: Context, id: Int): ByteArray? = photoFile(c, id).takeIf { it.isFile && it.length() < 200_000 }?.readBytes()
}

/** The avatar: picture filling the tile, or the profile's letter on its color. */
@Composable
fun FofAvatar(p: ProfileAvatars.Preset, modifier: Modifier = Modifier, letterSize: androidx.compose.ui.unit.TextUnit = 64.sp) {
    Box(modifier.background(Color(p.color)), contentAlignment = Alignment.Center) {
        if (p.model == null) Text(p.letter, color = Color.White, fontSize = letterSize, fontWeight = FontWeight.ExtraBold, raw = true)
        else coil.compose.AsyncImage(p.model, p.name, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            alignment = BiasAlignment(0f, -0.35f))
    }
}

/** Avatar of the active profile, round (header). */
@Composable
fun ActiveAvatar(size: Dp, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val p = remember(Profiles.active, ProfileAvatars.version) { ProfileAvatars.presetFor(ctx, Profiles.active) }
    FofAvatar(p, modifier.size(size).clip(CircleShape), letterSize = (size.value * 0.42f).sp)
}

/** One choice in the picker. */
private class Choice(val value: String, val image: String, val name: String, val sub: String)

/** Choices from what the app already shows: scenes of shows and movies, players of the teams in Sports. */
private object AvatarSources {
    /** Your titles first, then the popular ones (loaded from the saved list or the internet if not in memory yet). */
    suspend fun titles(c: Context, kind: Kind): List<Choice> = withContext(Dispatchers.IO) {
        withContext(Dispatchers.Main) { CatalogItems.init(c) }
        Catalog.init(c)
        val pop = Catalog.cached(Catalog.popular(kind), 3).ifEmpty { runCatching { Catalog.items(Catalog.popular(kind), 3) }.getOrDefault(emptyList()) }
        titlesOf(CatalogItems.all().filter { it.kind == kind } + pop, kind)
    }

    private fun titlesOf(src: List<Item>, kind: Kind): List<Choice> {
        val seen = HashSet<String>()
        return src.mapNotNull { i ->
            val m = Catalog.meta(i)
            val pic = m?.backdrop?.takeIf { it.startsWith("https://") } ?: i.logo?.takeIf { it.startsWith("https://") } ?: return@mapNotNull null
            if (!seen.add(i.title.lowercase())) null
            else Choice(AvatarValue.art(pic, i.title), pic, i.title, if (kind == Kind.SERIES) "Show" else "Movie")
        }.take(120)
    }

    @Volatile private var playersCache: List<Choice>? = null

    /** Players of your teams first, then of today's and this week's NBA and top soccer games (ESPN photos). */
    suspend fun players(): List<Choice> = playersCache ?: withContext(Dispatchers.IO) {
        val w = SportAhead.cached()
        val games = (SportFeed.latest.values + w.nba + w.soccer + w.football).distinctBy { it.key }
        val pairs = LinkedHashMap<String, Pair<SportData.League, SportData.Team>>()
        fun add(g: SportData.Game, t: SportData.Team) {
            val l = SportData.league(g.league) ?: return
            if (t.id.isBlank()) return
            pairs.getOrPut(l.path + "|" + t.id) { l to t }
        }
        for (g in games.sortedBy { if (SportFavs.isFavGame(it)) 0 else if (it.league == "NBA") 1 else 2 }) { if (g.solo) continue; add(g, g.home); add(g, g.away) }
        val teams = pairs.values.take(10)
        val out = coroutineScope {
            teams.map { (l, t) -> async { runCatching { SportTeamData.fetch(l, t) }.getOrNull()?.roster.orEmpty().mapNotNull { p ->
                p.photo?.takeIf { it.startsWith("https://") }?.let { Choice(AvatarValue.art(it, p.name), it, p.name, t.short.ifBlank { t.name }) }
            } } }.awaitAll().flatten()
        }.distinctBy { it.name }
        out.also { if (it.isNotEmpty()) playersCache = it }
    }
}

/**
 * Full-screen avatar selection: tabs (Shows, Movies, Players, Your photo), search, a grid of pictures (focus = lift,
 * white frame, name), then a large preview to confirm, then the profile card.
 */
@Composable
fun AvatarPicker(profileId: Int, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var tab by remember { mutableStateOf("tv") }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<Choice?>(null) }
    var saved by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf<Choice?>(null) }
    val shows by produceState<List<Choice>?>(null) { value = AvatarSources.titles(ctx, Kind.SERIES) }
    val movies by produceState<List<Choice>?>(null) { value = AvatarSources.titles(ctx, Kind.MOVIE) }
    val players by produceState<List<Choice>?>(null, tab) { if (tab == "pl" && value == null) value = AvatarSources.players() }
    val cur = when (tab) { "tv" -> shows; "mo" -> movies; "pl" -> players; else -> emptyList() }
    val all = cur.orEmpty()
    val shown = remember(all, query) { val q = query.trim().lowercase(); if (q.isEmpty()) all else all.filter { it.name.lowercase().contains(q) || it.sub.lowercase().contains(q) } }
    val gridStart = remember { FocusRequester() }
    val tabFirst = remember { FocusRequester() }
    val grid = rememberLazyGridState()
    LaunchedEffect(tab, query) { runCatching { grid.scrollToItem(0) } }
    BackHandler {
        when {
            saved -> onClose()
            preview != null -> preview = null
            searching -> { searching = false; query = "" }
            else -> onClose()
        }
    }
    // Start on the first picture as soon as there is one (else on the tabs)
    LaunchedEffect(shown.isNotEmpty()) {
        repeat(10) { delay(60); if (runCatching { gridStart.requestFocus() }.getOrDefault(false) || (shown.isEmpty() && runCatching { tabFirst.requestFocus() }.getOrDefault(false))) return@LaunchedEffect }
    }
    val profileName = remember { Profiles.list.firstOrNull { it.id == profileId }?.let { Profiles.displayName(it) } ?: "" }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF101116), Color(0xFF050507))))) {
        Column(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 34.dp)) {
            Text("Choose Your Avatar", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold, raw = true)
            Spacer(Modifier.height(4.dp))
            Text("Pick a scene from a show or movie, a player, or your own photo.", color = Color(0x99FFFFFF), fontSize = 16.sp, raw = true)
            Spacer(Modifier.height(18.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                items(listOf("tv" to "Shows", "mo" to "Movies", "pl" to "Players", "ph" to "Your photo"), key = { it.first }) { (k, label) ->
                    AvatarTab(label, tab == k, if (k == "tv") Modifier.focusRequester(tabFirst) else Modifier) { tab = k; query = "" }
                }
                if (tab != "ph") item(key = "search") {
                    AvatarTab(if (query.isBlank()) "Search" else "Search: $query", searching || query.isNotBlank()) { searching = true }
                }
            }
            if (searching) {
                Spacer(Modifier.height(12.dp))
                Box(Modifier.width(460.dp)) { Field(query, { query = it.take(30) }, "Search by name", autoOpen = true, onDone = { searching = false }) }
            }
            Spacer(Modifier.height(16.dp))
            Box(Modifier.height(30.dp)) {
                focused?.takeIf { tab != "ph" }?.let { p ->
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(p.name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, raw = true)
                        Spacer(Modifier.width(10.dp))
                        Text(p.sub, color = Color(0x80FFFFFF), fontSize = 15.sp, maxLines = 1, raw = true)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            when {
                tab == "ph" -> PhotoHelp()
                cur == null -> Text(if (tab == "pl") "Loading players …" else "Loading …", color = Color(0x99FFFFFF), fontSize = 18.sp, raw = true)
                shown.isEmpty() -> Text(
                    if (query.isNotBlank()) "Nothing matches “$query”." else if (tab == "pl") "No players yet – open Sports once, or follow a team." else "Nothing here yet – browse some titles first.",
                    color = Color(0x99FFFFFF), fontSize = 18.sp, raw = true)
                else -> LazyVerticalGrid(
                    GridCells.Fixed(8), state = grid, modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 40.dp, start = 4.dp, end = 4.dp)
                ) {
                    items(shown, key = { it.value }) { p ->
                        AvatarChoice(p, if (p === shown.first()) Modifier.focusRequester(gridStart) else Modifier, onFocus = { focused = p }) { preview = p }
                    }
                }
            }
        }
        preview?.let { p ->
            AvatarStage {
                coil.compose.AsyncImage(p.image, p.name, Modifier.size(260.dp).clip(RoundedCornerShape(30.dp)).background(Color(0xFF15161A)),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop, alignment = BiasAlignment(0f, -0.35f))
                Spacer(Modifier.height(26.dp))
                Text(p.name, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold, raw = true)
                Text(p.sub, color = Color(0x99FFFFFF), fontSize = 17.sp, raw = true)
                Spacer(Modifier.height(30.dp))
                val ok = remember { FocusRequester() }
                LaunchedEffect(p.value) { repeat(8) { delay(50); if (runCatching { ok.requestFocus() }.getOrDefault(false)) return@LaunchedEffect } }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    PillButton("Use this avatar", Modifier.focusRequester(ok), light = true, icon = AIcon.Check, raw = true) {
                        ProfileAvatars.set(ctx, profileId, p.value); preview = null; saved = true
                    }
                    PillButton("Back", raw = true) { preview = null }
                }
            }
        }
        if (saved) AvatarStage {
            val p = remember(ProfileAvatars.version) { ProfileAvatars.presetFor(ctx, profileId) }
            FofAvatar(p, Modifier.size(200.dp).clip(RoundedCornerShape(26.dp)))
            Spacer(Modifier.height(24.dp))
            Text(profileName, color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold, raw = true)
            Spacer(Modifier.height(6.dp))
            if (p.name.isNotBlank()) Text("as " + p.name, color = Color(0xB3FFFFFF), fontSize = 19.sp, raw = true)
            Spacer(Modifier.height(30.dp))
            val done = remember { FocusRequester() }
            LaunchedEffect(Unit) { repeat(8) { delay(50); if (runCatching { done.requestFocus() }.getOrDefault(false)) return@LaunchedEffect } }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PillButton("Done", Modifier.focusRequester(done), light = true, icon = AIcon.Check, raw = true) { onClose() }
                PillButton("Change avatar", raw = true) { saved = false }
            }
        }
    }
}

/** "Your photo": upload on the phone page; the picture shows here as soon as it arrives. */
@Composable
private fun PhotoHelp() {
    var phone by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Use your own photo", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, raw = true)
        Text("Open the Fof TV page on your phone, go to “Your avatar” and pick any picture. It appears here and on your other TVs right away.",
            color = Color(0xB3FFFFFF), fontSize = 18.sp, raw = true, modifier = Modifier.widthIn(max = 720.dp))
        PillButton("Show the phone code", raw = true) { phone = true }
    }
    if (phone) Box(Modifier.fillMaxWidth().height(420.dp)) { RemoteOverlay(Modifier.align(Alignment.Center), onClose = { phone = false }) }
}

/** Dimmed full screen with a centered column (preview and profile card). */
@Composable
private fun AvatarStage(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xF2050507)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0x1FFFFFFF), Color.Transparent))))
        Column(horizontalAlignment = Alignment.CenterHorizontally, content = content)
    }
}

@Composable
private fun AvatarTab(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Row(
        modifier.onFocusChanged { f = it.isFocused }.clip(shape)
            .background(when { f -> Color.White; selected -> Color(0x33FFFFFF); else -> Color(0x12FFFFFF) })
            .clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = if (f) Color.Black else if (selected) Color.White else Color(0xB3FFFFFF), fontSize = 16.sp,
            fontWeight = if (selected || f) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1, raw = true)
    }
}

@Composable
private fun AvatarChoice(p: Choice, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (f) 1.09f else 1f, tween(150), label = "av")
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier.aspectRatio(1f).graphicsLayer { scaleX = s; scaleY = s }
            .onFocusChanged { f = it.isFocused; if (it.isFocused) onFocus() }
            .clip(shape).background(Color(0xFF15161A))
            .border(if (f) 3.dp else 0.dp, Color.White, shape)
            .clickable(onClick = onClick)
    ) {
        coil.compose.AsyncImage(p.image, p.name, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            alignment = BiasAlignment(0f, -0.35f))
        if (!f) Box(Modifier.fillMaxSize().background(Color(0x26000000)))
    }
}
