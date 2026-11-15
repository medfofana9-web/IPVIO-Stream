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
import kotlinx.coroutines.delay
import org.json.JSONArray

/**
 * Avatar catalog (pure): one entry per avatar in assets/avatars/catalog.json – key, display name, category.
 * New avatars or categories: add the picture (assets/avatars/<key>.webp, 240×240) and a line in the catalog;
 * nothing else changes. Pictures: original 3D art (Microsoft Fluent Emoji, MIT license, docs/THIRD-PARTY.md) baked onto
 * cinematic color tiles.
 */
object AvatarCatalog {
    class Avatar(val key: String, val name: String, val cat: String)

    /** Category tabs in order: key in the catalog to tab label. "all" shows everything. */
    val CATEGORIES = listOf("all" to "All", "tv" to "TV Shows", "mo" to "Movies", "nb" to "NBA", "so" to "Soccer", "nf" to "NFL", "sp" to "More Sports")

    fun parse(json: String): List<Avatar> = runCatching {
        val a = JSONArray(json)
        (0 until a.length()).mapNotNull { i ->
            a.optJSONObject(i)?.let { o -> Avatar(o.optString("k"), o.optString("n"), o.optString("c")).takeIf { it.key.isNotBlank() } }
        }.distinctBy { it.key }
    }.getOrDefault(emptyList())

    fun filter(all: List<Avatar>, cat: String, query: String): List<Avatar> {
        val q = query.trim().lowercase()
        return all.filter { (cat == "all" || it.cat == cat) && (q.isEmpty() || it.name.lowercase().contains(q) || label(it.cat).lowercase().contains(q)) }
    }

    fun label(cat: String): String = CATEGORIES.firstOrNull { it.first == cat }?.second ?: ""
}

/**
 * Chosen avatar per profile (device-wide prefs "fof_avatars", key "a<profile id>"); it also travels with the account
 * (CloudSync). Pictures ship inside the app: instant, also offline, never gone after a restart.
 */
object ProfileAvatars {
    class Preset(val key: String, val name: String, val cat: String) {
        val asset: String get() = "file:///android_asset/avatars/$key.webp"
        /** Same picture for the phone page (public copy in the project). */
        val phoneUrl: String get() = "https://raw.githubusercontent.com/medfofana9-web/IPVIO-Stream/main/app/src/main/assets/avatars/$key.webp"
    }

    @Volatile private var cache: List<Preset>? = null

    fun all(c: Context): List<Preset> = cache ?: synchronized(this) {
        cache ?: AvatarCatalog.parse(runCatching { c.applicationContext.assets.open("avatars/catalog.json").bufferedReader().use { it.readText() } }.getOrDefault("[]"))
            .map { Preset(it.key, it.name, it.cat) }.also { cache = it }
    }

    /** Profiles without a choice: a different character per profile number (from TV shows and movies). */
    private fun default(c: Context, id: Int): Preset {
        val l = all(c).filter { it.cat == "tv" || it.cat == "mo" }.ifEmpty { all(c) }
        return l[(id * 7) % l.size]
    }

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("fof_avatars", Context.MODE_PRIVATE)

    /** Bumped on every change so the screens showing avatars redraw. */
    var version by mutableIntStateOf(0)
        private set

    fun byKey(c: Context, k: String?): Preset? = all(c).firstOrNull { it.key == k }

    /** Earlier choices (cartoon portraits, drawn tiles) are no longer offered: those profiles get their default. */
    fun chosen(c: Context, id: Int): Preset? = byKey(c, prefs(c).getString("a$id", null))

    fun presetFor(c: Context, id: Int): Preset = chosen(c, id) ?: default(c, id)

    /** Raw stored key (for the account sync). */
    fun keyOf(c: Context, id: Int): String? = prefs(c).getString("a$id", null)

    fun set(c: Context, id: Int, key: String?) {
        if (key == prefs(c).getString("a$id", null)) return
        prefs(c).edit().apply { if (key == null) remove("a$id") else putString("a$id", key) }.apply()
        version++
        CloudSync.kick(c)
    }
}

/** The avatar picture (rounded or round by the caller's clip). A dark base under it, so nothing flickers while it loads. */
@Composable
fun FofAvatar(p: ProfileAvatars.Preset, modifier: Modifier = Modifier) {
    Box(modifier.background(Color(0xFF15161A))) {
        coil.compose.AsyncImage(p.asset, p.name, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
    }
}

/** Avatar of the active profile, round (header, settings). */
@Composable
fun ActiveAvatar(size: Dp, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val p = remember(Profiles.active, ProfileAvatars.version) { ProfileAvatars.presetFor(ctx, Profiles.active) }
    FofAvatar(p, modifier.size(size).clip(CircleShape))
}

/**
 * Full-screen avatar selection: title, category tabs, search, a grid of tiles (focus = slight lift, white frame, name),
 * then a large preview to confirm, then the profile card (avatar, profile name, character, change).
 */
@Composable
fun AvatarPicker(profileId: Int, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val all = remember { ProfileAvatars.all(ctx) }
    val current = remember { ProfileAvatars.presetFor(ctx, profileId).key }
    var cat by remember { mutableStateOf("all") }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<ProfileAvatars.Preset?>(null) }
    var saved by remember { mutableStateOf<ProfileAvatars.Preset?>(null) }
    var focusedName by remember { mutableStateOf<ProfileAvatars.Preset?>(null) }
    val gridStart = remember { FocusRequester() }
    val tabFocus = remember { FocusRequester() }
    val shown = remember(cat, query) {
        AvatarCatalog.filter(all.map { AvatarCatalog.Avatar(it.key, it.name, it.cat) }, cat, query).mapNotNull { a -> all.firstOrNull { it.key == a.key } }
    }
    val grid = rememberLazyGridState()
    LaunchedEffect(cat, query) { grid.scrollToItem(0) }
    BackHandler {
        when {
            saved != null -> onClose()
            preview != null -> preview = null
            searching -> { searching = false; query = "" }
            else -> onClose()
        }
    }
    LaunchedEffect(Unit) {
        // Start on the current avatar when it is on the first screen, else on the first tile
        repeat(10) { delay(60); if (runCatching { gridStart.requestFocus() }.getOrDefault(false)) return@LaunchedEffect }
    }
    val profileName = remember { Profiles.list.firstOrNull { it.id == profileId }?.let { Profiles.displayName(it) } ?: "" }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF101116), Color(0xFF050507))))) {
        Column(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 34.dp)) {
            Text("Choose Your Avatar", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold, raw = true)
            Spacer(Modifier.height(4.dp))
            Text("Pick a character, legend, or icon that represents you.", color = Color(0x99FFFFFF), fontSize = 16.sp, raw = true)
            Spacer(Modifier.height(18.dp))
            // Tabs and search
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                item(key = "search") {
                    AvatarTab(if (query.isBlank()) "Search" else "Search: $query", searching || query.isNotBlank()) { searching = true }
                }
                items(AvatarCatalog.CATEGORIES, key = { it.first }) { (k, label) ->
                    AvatarTab(label, cat == k && query.isBlank(), modifier = if (k == "all") Modifier.focusRequester(tabFocus) else Modifier) { cat = k; query = "" }
                }
            }
            if (searching) {
                Spacer(Modifier.height(12.dp))
                Box(Modifier.width(460.dp)) {
                    Field(query, { query = it.take(30) }, "Search avatars", autoOpen = true, onDone = { searching = false })
                }
            }
            Spacer(Modifier.height(18.dp))
            // Name of the focused avatar (fixed height: nothing shifts)
            Box(Modifier.height(30.dp)) {
                focusedName?.let { p ->
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(p.name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, raw = true)
                        Spacer(Modifier.width(10.dp))
                        Text(AvatarCatalog.label(p.cat), color = Color(0x80FFFFFF), fontSize = 15.sp, raw = true)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            if (shown.isEmpty()) Text("No avatar matches “$query”.", color = Color(0x99FFFFFF), fontSize = 18.sp, raw = true)
            LazyVerticalGrid(
                GridCells.Fixed(8), state = grid, modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 40.dp, start = 4.dp, end = 4.dp)
            ) {
                items(shown, key = { it.key }) { p ->
                    val first = p.key == (shown.firstOrNull { it.key == current } ?: shown.firstOrNull())?.key
                    AvatarChoice(p, p.key == current, if (first) Modifier.focusRequester(gridStart) else Modifier,
                        onFocus = { focusedName = p }) { preview = p }
                }
            }
        }
        // Large preview: confirm
        preview?.let { p ->
            AvatarStage {
                FofAvatar(p, Modifier.size(260.dp).clip(RoundedCornerShape(30.dp)))
                Spacer(Modifier.height(26.dp))
                Text(p.name, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold, raw = true)
                Text(AvatarCatalog.label(p.cat), color = Color(0x99FFFFFF), fontSize = 17.sp, raw = true)
                Spacer(Modifier.height(30.dp))
                val ok = remember { FocusRequester() }
                LaunchedEffect(p.key) { repeat(8) { delay(50); if (runCatching { ok.requestFocus() }.getOrDefault(false)) return@LaunchedEffect } }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    PillButton("Use this avatar", Modifier.focusRequester(ok), light = true, icon = AIcon.Check, raw = true) {
                        ProfileAvatars.set(ctx, profileId, p.key); preview = null; saved = p
                    }
                    PillButton("Back", raw = true) { preview = null }
                }
            }
        }
        // Profile card after saving
        saved?.let { p ->
            AvatarStage {
                FofAvatar(p, Modifier.size(200.dp).clip(RoundedCornerShape(26.dp)))
                Spacer(Modifier.height(24.dp))
                Text(profileName, color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold, raw = true)
                Spacer(Modifier.height(6.dp))
                Text("as " + p.name, color = Color(0xB3FFFFFF), fontSize = 19.sp, raw = true)
                Spacer(Modifier.height(30.dp))
                val done = remember { FocusRequester() }
                LaunchedEffect(p.key) { repeat(8) { delay(50); if (runCatching { done.requestFocus() }.getOrDefault(false)) return@LaunchedEffect } }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    PillButton("Done", Modifier.focusRequester(done), light = true, icon = AIcon.Check, raw = true) { onClose() }
                    PillButton("Change avatar", raw = true) { saved = null }
                }
            }
        }
    }
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
private fun AvatarTab(label: String, selected: Boolean, modifier: Modifier = Modifier, icon: AIcon? = null, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Row(
        modifier.onFocusChanged { f = it.isFocused }.clip(shape)
            .background(when { f -> Color.White; selected -> Color(0x33FFFFFF); else -> Color(0x12FFFFFF) })
            .clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (icon != null) AppIcon(icon, if (f) Color.Black else Color.White, size = 16.dp)
        Text(label, color = if (f) Color.Black else if (selected) Color.White else Color(0xB3FFFFFF), fontSize = 16.sp,
            fontWeight = if (selected || f) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1, raw = true)
    }
}

@Composable
private fun AvatarChoice(p: ProfileAvatars.Preset, selected: Boolean, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (f) 1.09f else 1f, tween(150), label = "av")
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier.aspectRatio(1f).graphicsLayer { scaleX = s; scaleY = s }
            .onFocusChanged { f = it.isFocused; if (it.isFocused) onFocus() }
            .clip(shape)
            .border(if (f) 3.dp else 0.dp, Color.White, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        FofAvatar(p, Modifier.fillMaxSize())
        if (!f) Box(Modifier.fillMaxSize().background(Color(0x26000000)))
        if (selected) Box(Modifier.align(Alignment.TopEnd).padding(7.dp).size(24.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
            AppIcon(AIcon.Check, Color.Black, size = 15.dp)
        }
    }
}
