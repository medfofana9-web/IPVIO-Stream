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
    onPlay: (String, String, Long) -> Unit, onDismiss: () -> Unit,
    /** Fof TV one-click play: start the best stream as soon as the list is there (the list stays as fallback). */
    auto: Boolean = false
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
    val ctx = androidx.compose.ui.platform.LocalContext.current
    SourceMemory.init(ctx)
    // What was asked for: the identity every stream is checked against (no title search anywhere after this)
    val want = Stremio.Want(series, item.title, item.year ?: Catalog.meta(item)?.year?.toString()?.take(4)?.toIntOrNull(), season, ep)
    val cid = SourceMemory.contentId(imdb, series, season, ep)
    var partial by remember { mutableStateOf<List<Stremio.Source>>(emptyList()) }
    LaunchedEffect(imdb, season, ep) {
        val id = imdb ?: return@LaunchedEffect
        sources = null; partial = emptyList()
        sources = Stremio.streams(item.kind, id, season, ep) { partial = it }
    }
    fun choose(s: Stremio.Source) {
        NowSources.set(item.id, imdb, want, Stremio.rank(sources ?: partial, want, cid), s)
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
    var autoDone by remember { mutableStateOf(false) }
    var noVerified by remember { mutableStateOf(false) }
    LaunchedEffect(sources, partial) {
        val l = sources ?: partial
        // Automatic play only with a verified match for exactly this title/episode (starts with the first addon that has
        // one); when all addons answered without one, the list stays open to choose
        if (auto && !autoDone && FofPrefs.oneClick && l.isNotEmpty()) {
            val v = Stremio.bestVerified(l, want, cid)
            if (v != null) { autoDone = true; choose(v); return@LaunchedEffect }
            if (sources != null) { autoDone = true; noVerified = true }
        }
        if (sources == null) return@LaunchedEffect
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
            // Fills while addons answer; the final list replaces it
            val list = (sources ?: partial.takeIf { it.isNotEmpty() })?.let { Stremio.rank(it, want, cid) }
            if (noVerified) Text("No stream could be confirmed as this " + (if (series) "episode" else "movie") + ". Choose one below.",
                color = DS.Gold, fontSize = 15.sp, raw = true)
            if (sources == null && partial.isNotEmpty()) Text("More addons are still answering …", color = Muted, fontSize = 13.sp, raw = true)
            when {
                noId -> Text("Couldn't match this title on IMDb. Adding a free TMDB key (Settings > API keys) makes matching more reliable.", color = Muted, fontSize = 16.sp)
                list == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Spinner(Accent2, Modifier.size(28.dp))
                    Text("Asking your addons…", color = Muted, fontSize = 16.sp)
                }
                list.isEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("No playable streams found.", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    // Per addon: down, no links, or only links this app cannot play – tells whether to wait or fix an addon
                    Stremio.lastReport.forEach { Text(it, color = Muted, fontSize = 15.sp, raw = true) }
                }
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(list) { i, s ->
                        SourceRow(s, Stremio.match(s, want), SourceMemory.isWrong(cid, s.key), s.key == SourceMemory.lastGood(cid),
                            if (i == 0) Modifier.focusRequester(first) else Modifier) { choose(s) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SourceRow(s: Stremio.Source, match: Int, wrong: Boolean, lastGood: Boolean, modifier: Modifier, active: Boolean = false, onClick: () -> Unit) {
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
        val dim = wrong || match == StremioLogic.MISMATCH
        Column(Modifier.weight(1f)) {
            Text(s.label, color = if (dim) Muted else Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, raw = true)
            // Only what the addon itself says (no invented quality or speed)
            val facts = StremioLogic.facts(s.info)
            Text((listOf(s.addon) + facts + listOfNotNull(if (s.hash != null) "Real-Debrid" else null, if (lastGood) "Played before" else null)).joinToString("  ·  "),
                color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, raw = true)
        }
        val (tag, col) = when {
            active -> "Playing" to Color.White
            wrong -> "Reported wrong" to Color(0xFFFF6B5E)
            match == StremioLogic.VERIFIED -> "Match" to Color(0xFF4ADE80)
            match == StremioLogic.MISMATCH -> "Other episode/title" to Color(0xFFFFB020)
            else -> "Unverified" to Muted
        }
        Text(tag, color = col, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, raw = true)
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
    // Play: start the best verified source by itself, or always show the list
    SectionTitle("When I press Play")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PillButton(if (FofPrefs.oneClick) "✓ Auto-select best source" else "Auto-select best source", light = !FofPrefs.oneClick, raw = true) { FofPrefs.setFlag(ctx, "one_click", true) }
        PillButton(if (!FofPrefs.oneClick) "✓ Ask me every time" else "Ask me every time", light = FofPrefs.oneClick, raw = true) { FofPrefs.setFlag(ctx, "one_click", false) }
    }
    Hint("Auto-select only starts a stream whose file name confirms the movie or episode. Otherwise the list opens so you can choose.")
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
        // Home: the catalogs of the added addons (the user's source of truth), mixed movies and series;
        // Stremio's own popular lists only when no addon has a catalog
        val own = Stremio.addons.flatMap { it.cats }
        val cats = if (own.isEmpty()) Stremio.builtIn else {
            val m = own.filter { it.type == "movie" }; val s = own.filter { it.type == "series" }
            (0 until maxOf(m.size, s.size)).flatMap { listOfNotNull(m.getOrNull(it), s.getOrNull(it)) }.take(8)
        }
        for (cat in cats) {
            val metas by produceState(emptyList<Stremio.Meta>(), cat) { value = Stremio.catalog(cat) }
            if (metas.isEmpty()) continue
            StremioMetaRow(cat.name, metas, raw = cat.addon != "Cinemeta", onOpen = onOpen)
        }
    }
}

/** Movies or Series page: every catalog of that type from the added addons (falls back to Stremio's popular list). */
@Composable
fun StremioTypeRows(type: String, onOpen: (Item) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { Stremio.ensure(ctx) }
    val own = Stremio.addons.flatMap { it.cats }.filter { it.type == type }
    val cats = own.ifEmpty { Stremio.builtIn.filter { it.type == type } }
    if (cats.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(dpx(18))) {
        for (cat in cats) {
            val metas by produceState(emptyList<Stremio.Meta>(), cat) { value = Stremio.catalog(cat) }
            if (metas.isEmpty()) continue
            StremioMetaRow(cat.name.removeSuffix(" (movies)").removeSuffix(" (series)"), metas, raw = true, onOpen = onOpen)
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
    StremioMetaRow("Movies & shows", metas, raw = true, onOpen = onOpen)
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

/**
 * Sources of the title playing, over the player: the active one marked, right content first. OK switches at the same
 * position (the player goes back to the previous source if the new one does not start). Back closes.
 */
@Composable
internal fun PlayerSourcePicker(
    list: List<Stremio.Source>, want: Stremio.Want, cid: String, currentKey: String?, busy: Boolean,
    onPick: (Stremio.Source) -> Unit, onWrong: () -> Unit, onRetry: () -> Unit, onClose: () -> Unit
) {
    androidx.compose.ui.window.Popup(
        alignment = Alignment.CenterEnd, onDismissRequest = onClose,
        properties = PopupProperties(focusable = true)
    ) {
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { repeat(10) { kotlinx.coroutines.delay(60); if (runCatching { first.requestFocus() }.getOrDefault(false)) return@LaunchedEffect } }
        val shape = RoundedCornerShape(DS.RadiusM)
        Column(
            Modifier.padding(end = 40.dp).width(640.dp).heightIn(max = 560.dp).clip(shape)
                .background(Surf.copy(alpha = 0.97f)).border(1.dp, DS.Overlay3, shape).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Sources", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, raw = true)
            Text(want.title + if (want.series) "  ·  S${want.season} E${want.ep}" else "", color = Muted, fontSize = 15.sp, maxLines = 1, raw = true)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Wrong content", light = true, raw = true) { onWrong() }
                PillButton("Retry", light = true, raw = true) { onRetry() }
            }
            if (busy) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Spinner(Accent2, Modifier.size(20.dp)); Text("Opening the source …", color = Muted, fontSize = 14.sp, raw = true)
            }
            if (list.isEmpty()) Text("No other sources for this title.", color = Muted, fontSize = 15.sp, raw = true)
            val startAt = list.indexOfFirst { it.key == currentKey }.coerceAtLeast(0)
            androidx.compose.foundation.lazy.LazyColumn(
                state = androidx.compose.foundation.lazy.rememberLazyListState(maxOf(startAt - 2, 0)),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                itemsIndexed(list) { i, s ->
                    SourceRow(s, Stremio.match(s, want), SourceMemory.isWrong(cid, s.key), s.key == SourceMemory.lastGood(cid),
                        if (i == startAt) Modifier.focusRequester(first) else Modifier, active = s.key == currentKey) { onPick(s) }
                }
            }
        }
    }
}
