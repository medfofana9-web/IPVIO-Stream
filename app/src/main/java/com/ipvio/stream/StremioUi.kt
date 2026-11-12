package com.ipvio.stream

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.launch

/**
 * List of Stremio streams for a movie or one episode. For a series the episode can be changed with − / +.
 * Picking a stream plays it in the app's own player (same title format as the playlist, so resume and history work).
 */
@Composable
fun StremioSourcesPopup(
    item: Item, knownImdb: String?, startSeason: Int, startEp: Int,
    onPlay: (String, String, Long) -> Unit, onDismiss: () -> Unit
) {
    val series = item.kind == Kind.SERIES
    var season by remember { mutableIntStateOf(startSeason.coerceAtLeast(1)) }
    var ep by remember { mutableIntStateOf(startEp.coerceAtLeast(1)) }
    var imdb by remember { mutableStateOf<String?>(null) }
    var noId by remember { mutableStateOf(false) }
    var sources by remember { mutableStateOf<List<Stremio.Source>?>(null) }
    var resolving by remember { mutableStateOf(false) }
    var rdError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    val stepFocus = remember { FocusRequester() }

    LaunchedEffect(item.id) {
        imdb = Stremio.imdbFor(item, knownImdb)
        noId = imdb == null
    }
    LaunchedEffect(imdb, season, ep) {
        val id = imdb ?: return@LaunchedEffect
        sources = null
        sources = Stremio.streams(item.kind, id, season, ep)
    }
    LaunchedEffect(sources) {
        if (sources?.isNotEmpty() == true) runCatching { first.requestFocus() }
        else if (series) runCatching { stepFocus.requestFocus() }
    }

    Popup(alignment = Alignment.Center, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        val shape = RoundedCornerShape(DS.RadiusM)
        Column(
            Modifier.width(720.dp).heightIn(max = 520.dp).clip(shape)
                .background(Surf.copy(alpha = 0.98f)).border(1.dp, DS.Overlay3, shape).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Stremio sources", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(item.title + if (series) "  ·  S$season E$ep" else "", color = Muted, fontSize = 16.sp, maxLines = 1, raw = true)
            if (series) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    StepRow("Season", "$season", { if (season > 1) { season--; ep = 1 } }, { season++; ep = 1 }, Modifier.focusRequester(stepFocus))
                    StepRow("Episode", "$ep", { if (ep > 1) ep-- }, { ep++ })
                }
            }
            if (resolving) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Spinner(Accent2, Modifier.size(22.dp)); Text("Getting the link from Real-Debrid…", color = Muted, fontSize = 15.sp)
            }
            rdError?.let { Text(it, color = DS.Gold, fontSize = 15.sp, raw = true) }
            val list = sources
            when {
                noId -> Text("Couldn't match this title on IMDb. Adding a free TMDB key (Settings > API keys) makes matching more reliable.", color = Muted, fontSize = 16.sp)
                list == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Spinner(Accent2, Modifier.size(28.dp))
                    Text("Asking your addons…", color = Muted, fontSize = 16.sp)
                }
                list.isEmpty() -> Text("No playable streams found. Torrent-only results are skipped; addons set up with a debrid service return playable links.", color = Muted, fontSize = 16.sp)
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(list) { i, s ->
                        SourceRow(s, if (i == 0) Modifier.focusRequester(first) else Modifier) {
                            val title = item.title + if (series) " S${season}E$ep" else ""
                            if (s.hash == null) { onDismiss(); onPlay(s.url, title, 0L) }
                            else if (!resolving) {
                                resolving = true; rdError = null
                                scope.launch {
                                    val r = runCatching { Debrid.resolve(s.hash, s.file) }
                                    resolving = false
                                    r.onSuccess { u -> onDismiss(); onPlay(u, title, 0L) }.onFailure { e -> rdError = e.message ?: "Real-Debrid error" }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceRow(s: Stremio.Source, modifier: Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(DS.RadiusS)
    Row(
        modifier.fillMaxWidth().clip(shape)
            .background(if (f) DS.Overlay4 else Color.Transparent)
            .border(DS.Border, if (f) Accent2 else Color.Transparent, shape)
            .onFocusChanged { f = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val q = when (s.q) { 3 -> "4K"; 2 -> "FHD"; 1 -> "HD"; else -> null }
        Box(Modifier.width(52.dp)) { if (q != null) QualityBadge(q) }
        Column(Modifier.weight(1f)) {
            Text(s.label, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, raw = true)
            Text(s.addon, color = Muted, fontSize = 13.sp, maxLines = 1, raw = true)
        }
    }
}

/** Settings > Stremio addons: paste an addon link, see and remove added addons. */
@Composable
internal fun StremioPage(ctx: Context) {
    LaunchedEffect(Unit) { Stremio.ensure(ctx) }
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Hint("Paste the install link of a Stremio addon (the one that ends in manifest.json, or a stremio:// link). Movies and series then get a \"Stremio\" button that lists the addon's streams next to your playlist. Tip: the Fire TV app on your phone lets you type and paste into this field.")
    SectionTitle("Add an addon")
    Field(link, { link = it; status = "" }, "https://…/manifest.json")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        PillButton(if (busy) "Checking…" else "Add addon", light = true, icon = AIcon.Plus) {
            if (busy || link.isBlank()) return@PillButton
            busy = true
            scope.launch {
                val err = Stremio.add(ctx, link)
                status = err ?: "Addon added"
                if (err == null) link = ""
                busy = false
            }
        }
        if (status.isNotBlank()) Text(status, color = if (status == "Addon added") Accent2 else Muted, fontSize = 16.sp)
    }

    SectionTitle("Your addons")
    val list = Stremio.addons
    if (list.isEmpty()) Hint("No addons yet.")
    list.forEach { a ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                Text(a.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, raw = true)
                Text(a.base.substringAfter("://").substringBefore('/'), color = Muted, fontSize = 14.sp, raw = true)
            }
            ConfirmPill("Remove") { Stremio.remove(ctx, a.base) }
        }
    }
    SectionTitle("Real-Debrid")
    Debrid.init(ctx)
    var rd by remember { mutableStateOf("") }
    var rdStatus by remember { mutableStateOf(if (Debrid.on) "Connected (token " + Debrid.masked() + ")" else "") }
    Hint("With your Real-Debrid token, torrent results from your addons play too (marked RD). Get the token at real-debrid.com/apitoken. It stays encrypted on this TV.")
    Field(rd, { rd = it; rdStatus = "" }, if (Debrid.on) "Paste a new token to replace it" else "Real-Debrid API token", password = true)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        PillButton("Save token", light = true, icon = AIcon.Check) {
            scope.launch { val r = Debrid.save(ctx, rd); rd = ""; rdStatus = if (r.startsWith("!")) r.drop(1) else "Connected as $r" }
        }
        if (Debrid.on) ConfirmPill("Remove token") { scope.launch { Debrid.save(ctx, ""); rdStatus = "Removed" } }
        if (rdStatus.isNotBlank()) Text(rdStatus, color = if (rdStatus.startsWith("Connected")) Accent2 else Muted, fontSize = 16.sp, raw = true)
    }
    Hint("Without Real-Debrid, only streams with a direct link play.")
}

/**
 * Home rows from Stremio catalogs (Stremio's popular lists plus the catalogs of added addons). A title opens its detail page,
 * where Play lists the streams from your addons. Hidden while no stream addon is added (nothing could play).
 */
@Composable
fun StremioCatalogRows(onOpen: (Item) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { Stremio.ensure(ctx) }
    if (Stremio.addons.none { it.streams }) return
    Column(verticalArrangement = Arrangement.spacedBy(dpx(18))) {
        for (cat in Stremio.allCatalogs().take(8)) {
            val metas by produceState(emptyList<Stremio.Meta>(), cat) { value = Stremio.catalog(cat) }
            if (metas.isEmpty()) continue
            StremioMetaRow(cat.name, metas, raw = cat.addon != "Cinemeta", onOpen = onOpen)
        }
    }
}

@Composable
private fun StremioMetaRow(title: String, metas: List<Stremio.Meta>, raw: Boolean, onOpen: (Item) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(dpx(10))) {
        Text(title, color = Color.White, fontSize = spx(24), fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = RowPadX), raw = raw)
        androidx.compose.foundation.lazy.LazyRow(contentPadding = PaddingValues(horizontal = RowPadX), horizontalArrangement = Arrangement.spacedBy(dpx(14))) {
            items(metas.size, key = { metas[it].imdb + it }) { i ->
                val m = metas[i]
                val item = remember(m) { Stremio.itemOf(m) }
                StremioPoster(m, Modifier.width(dpx(150))) { onOpen(item) }
            }
        }
    }
}

/** Search results from Stremio (titles not already found in the playlist), below the playlist results. */
@Composable
fun StremioSearchRow(q: String, have: List<String>, onOpen: (Item) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { Stremio.ensure(ctx) }
    if (Stremio.addons.none { it.streams } || q.length < 2) return
    val metas by produceState(emptyList<Stremio.Meta>(), q) {
        kotlinx.coroutines.delay(450)
        val own = have.map { StremioLogic.cleanTitle(it).lowercase() }.toHashSet()
        value = Stremio.search(q).filter { StremioLogic.cleanTitle(it.name).lowercase() !in own }
    }
    if (metas.isEmpty()) return
    StremioMetaRow("From your Stremio addons", metas, raw = false, onOpen = onOpen)
}


/** Poster card for a catalog title: shows the catalog's own poster right away (no metadata lookup per card). */
@Composable
private fun StremioPoster(m: Stremio.Meta, modifier: Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val sc by androidx.compose.animation.core.animateFloatAsState(if (f) focusScale(1.07f) else 1f, label = "stp")
    val shape = RoundedCornerShape(dpx(8))
    Box(
        modifier.aspectRatio(2f / 3f).graphicsLayer { scaleX = sc; scaleY = sc }.clip(shape).background(Surf2)
            .border(1.dp, if (f) Color(0x40FFFFFF) else Color.Transparent, shape)
            .onFocusChanged { f = it.isFocused }
            .trackFocus()
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(m.name, color = Muted, fontSize = 14.sp, maxLines = 3, raw = true, modifier = Modifier.padding(8.dp))
        if (m.poster != null) coil.compose.AsyncImage(m.poster, m.name, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
    }
}
