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
import androidx.compose.animation.core.animateFloat

/**
 * List of Stremio streams for a movie or one episode. For a series the episode can be changed with − / +.
 * Picking a stream plays it in the app's own player (same title format as the playlist, so resume and history work).
 */
@Composable
fun StremioSourcesPopup(
    item: Item, knownImdb: String?, startSeason: Int, startEp: Int,
    onPlay: (String, String, Long) -> Unit, onDismiss: () -> Unit,
    /** Fof TV one-click play: start the best stream as soon as the list is there (the list stays as fallback). */
    auto: Boolean = false,
    /** Picture behind the start screen (the title page's backdrop). */
    backdrop: String? = null
) {
    val series = item.kind == Kind.SERIES
    var season by remember { mutableIntStateOf(startSeason.coerceAtLeast(1)) }
    var ep by remember { mutableIntStateOf(startEp.coerceAtLeast(1)) }
    var imdb by remember { mutableStateOf<String?>(null) }
    var noId by remember { mutableStateOf(false) }
    var sources by remember { mutableStateOf<List<Stremio.Source>?>(null) }
    var streamReport by remember { mutableStateOf<List<String>>(emptyList()) }
    var refreshToken by remember { mutableIntStateOf(0) }
    var retriedAlternate by remember(item.id) { mutableStateOf(false) }
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
    // Your IPTV: its own section under the addon sources (needs no IMDb id; the provider's copy is found by name)
    var iptv by remember { mutableStateOf<Stremio.Source?>(null) }
    var iptvState by remember { mutableStateOf("loading") }   // loading, found, none
    LaunchedEffect(imdb, season, ep, refreshToken, Stremio.addons) {
        val id = imdb ?: return@LaunchedEffect
        sources = null; partial = emptyList(); streamReport = emptyList()
        // Manual source screens always refresh the add-ons rather than showing
        // a cached partial list. This matters when DragonStreams answers later.
        val lookup = Stremio.lookupStreams(item.kind, id, season, ep,
            onPart = { partial = it }, refresh = !auto || refreshToken > 0)
        if (lookup.sources.isEmpty() && !retriedAlternate && Stremio.addons.any { it.streams }) {
            // Some IPTV providers have the IMDb ID of a spin-off with the same name
            // (e.g. Survivor). When no addon finds streams for that ID, retry with
            // Cinemeta's exact title/year match, never with an unrelated title.
            // Updating imdb reruns this effect; a repeated ID cannot loop.
            val alternate = Stremio.alternateImdbFor(item, id)
            if (alternate != null) {
                retriedAlternate = true
                imdb = alternate
                return@LaunchedEffect
            }
        }
        streamReport = lookup.report
        sources = lookup.sources
    }
    fun choose(s: Stremio.Source) {
        NowSources.set(item.id, imdb, want, Stremio.rank((sources ?: partial) + listOfNotNull(iptv), want, cid), s)
        val title = item.title + if (series) " S${season}E$ep" else ""
        StartCover.set(item.id, item.title, if (series) "Season $season  ·  Episode $ep" else "", backdrop)
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
    LaunchedEffect(season, ep) {
        iptv = null; iptvState = "loading"
        val s = runCatching { ProviderVod.source(ctx, series, want.title, want.year, season, ep) }.getOrNull()
        iptv = s; iptvState = if (s != null) "found" else "none"
    }
    val iptvFocus = remember { FocusRequester() }
    // No addon stream (yet) but the IPTV copy is there: focus goes to it, so OK plays it
    LaunchedEffect(iptv) { if (iptv != null && sources.isNullOrEmpty()) { kotlinx.coroutines.delay(80); runCatching { iptvFocus.requestFocus() } } }
    var autoDone by remember { mutableStateOf(false) }
    var noVerified by remember { mutableStateOf(false) }
    // One-click play, fast: a source that played this title before or one whose file name confirms it starts at once;
    // otherwise 1.5 s after the first addon answered the best source that is not another title starts (slow addons
    // are not waited for). Only when every source looks like another title does the list open.
    var firstAt by remember(imdb, season, ep, refreshToken) { mutableLongStateOf(0L) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(firstAt) { if (firstAt > 0L) repeat(65) { kotlinx.coroutines.delay(200); tick++ } }
    LaunchedEffect(sources, partial, iptv, iptvState, tick) {
        val l = (sources ?: partial) + listOfNotNull(iptv)
        if (auto && !autoDone && FofPrefs.oneClick && l.isNotEmpty()) {
            if (firstAt == 0L) firstAt = System.currentTimeMillis()
            val waited = System.currentTimeMillis() - firstAt
            val known = SourceMemory.lastGood(cid)?.let { k -> l.firstOrNull { it.key == k && !SourceMemory.isWrong(cid, it.key) } }
            if (known != null && StremioLogic.sourcePriority(known.addon) == 0) { autoDone = true; choose(known); return@LaunchedEffect }
            // DragonStreams is the main source: give it up to 12 s to answer before anything else starts (its links are
            // scraped on request and often take 8–12 s; at 8 s the IPTV copy won although DragonStreams had the title)
            val dragonInstalled = Stremio.addons.any { it.streams && it.name.contains("dragon", true) }
            val dragonAnswered = imdb?.let { Stremio.dragonAnswered(item.kind, it, season, ep) } == true
            val dragonPending = dragonInstalled && sources == null && !dragonAnswered && l.none { it.addon.contains("dragon", true) } && waited < 12_000
            if (dragonPending) return@LaunchedEffect
            val top = Stremio.bestPlayable(l, want, cid)
            if (top != null && StremioLogic.sourcePriority(top.addon) <= 1) { autoDone = true; choose(top); return@LaunchedEffect }
            // No DragonStreams copy: Your IPTV comes next, so wait for its lookup before another addon starts
            if (iptvState == "loading" && waited < 8_000) return@LaunchedEffect
            val v = known ?: Stremio.bestVerified(l, want, cid)
            if (v != null) { autoDone = true; choose(v); return@LaunchedEffect }
            if (sources != null || waited >= 1_500) {
                autoDone = true
                val p = Stremio.bestPlayable(l, want, cid)
                if (p != null) { choose(p); return@LaunchedEffect } else noVerified = true
            }
        }
        if (sources == null) return@LaunchedEffect
        if (sources?.isNotEmpty() == true) runCatching { first.requestFocus() }
        else if (iptv != null) runCatching { iptvFocus.requestFocus() }
        else if (series) runCatching { stepFocus.requestFocus() }
    }

    // Play (one-click): the title's picture while the best confirmed source is found and started – the list only
    // comes up when no source confirms this title, Real-Debrid fails or nothing was found
    val startScreen = auto && FofPrefs.oneClick && !noVerified && !noId && rdError == null && sources?.isEmpty() != true
    if (startScreen) {
        StartScreen(item.title, if (series) "Season $season  ·  Episode $ep" else "", backdrop,
            if (resolving || autoDone) "Starting" else "Getting it ready", onDismiss)
        return
    }
    // Sources: a panel at the right edge, like the Mac app
    Popup(alignment = Alignment.CenterEnd, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Column(
            Modifier.width(600.dp).fillMaxHeight()
                .background(Color(0xFA0E0E0E)).border(1.dp, Color(0xFF1F1F1F))
                .padding(start = 34.dp, end = 30.dp, top = 40.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("SOURCES", color = Color(0xFF808080), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    letterSpacing = 2.sp, modifier = Modifier.weight(1f), raw = true)
                PillButton("Refresh", raw = true) {
                    refreshToken++
                    noVerified = false
                }
            }
            Text(item.title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, raw = true)
            if (series) Text("Season $season  ·  Episode $ep", color = Muted, fontSize = 15.sp, maxLines = 1, raw = true)
            if (series) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
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
            // If other addons answered but DragonStreams did not, show the actual per-addon
            // status instead of silently making it look like the source was never installed.
            val dragonInstalled = Stremio.addons.any { it.streams && it.name.contains("dragon", true) }
            if (sources != null && dragonInstalled && list.orEmpty().none { it.addon.contains("dragon", true) }) {
                val detail = streamReport.firstOrNull { it.substringBefore(':').contains("dragon", true) }
                    ?: "DragonStreams did not return any playable streams."
                Text("$detail · Choose Refresh sources to try again.", color = DS.Gold,
                    fontSize = 13.sp, maxLines = 2, raw = true)
            }
            when {
                noId -> Text("Couldn't match this title on IMDb. Adding a free TMDB key (Settings > API keys) makes matching more reliable.", color = Muted, fontSize = 16.sp)
                list == null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(4) { Box(Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(4.dp)).background(shimmerColor())) }
                }
                list.isEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("No playable streams found.", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    // Per addon: down, no links, or only links this app cannot play – tells whether to wait or fix an addon
                    streamReport.forEach { Text(it, color = Muted, fontSize = 15.sp, raw = true) }
                }
                else -> LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    itemsIndexed(list) { i, s ->
                        SourceRow(s, Stremio.match(s, want), SourceMemory.isWrong(cid, s.key), s.key == SourceMemory.lastGood(cid),
                            if (i == 0) Modifier.focusRequester(first) else Modifier) { choose(s) }
                    }
                }
            }
            // Your IPTV section (always shown, under the addon sources)
            Text("IPTV alternative", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, raw = true)
            when (iptvState) {
                "loading" -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Spinner(Accent2, Modifier.size(20.dp))
                    Text("Checking your IPTV (the first time takes a little longer)…", color = Muted, fontSize = 15.sp, raw = true)
                }
                "found" -> iptv?.let { s ->
                    SourceRow(s, StremioLogic.VERIFIED, false, s.key == SourceMemory.lastGood(cid), Modifier.focusRequester(iptvFocus)) { choose(s) }
                }
                else -> Text(if (PlaylistStore.savedUrl(ctx)?.let { XtreamLogic.fromUrl(it) } == null) "Your IPTV is not an Xtream login, so it cannot be searched."
                    else "Not on your IPTV" + (if (series) " (this episode)" else "") + ".", color = Muted, fontSize = 15.sp, raw = true)
            }
        }
    }
}

@Composable
internal fun SourceRow(s: Stremio.Source, match: Int, wrong: Boolean, lastGood: Boolean, modifier: Modifier, active: Boolean = false, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(4.dp)
    Row(
        modifier.fillMaxWidth().clip(shape)
            .background(if (f) Color.White else Color.Transparent)
            .onFocusChanged { f = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val q = when (s.q) { 3 -> "4K"; 2 -> "1080p"; 1 -> "720p"; else -> "—" }
        val ink = if (f) Color.Black else Color.White
        val soft = if (f) Color(0xFF555555) else Muted
        Text(q, color = ink, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(56.dp), raw = true)
        val dim = wrong || match == StremioLogic.MISMATCH
        Column(Modifier.weight(1f)) {
            Text(s.label, color = if (dim && !f) Muted else ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, raw = true)
            // Only what the addon itself says (no invented quality or speed)
            val facts = StremioLogic.facts(s.info)
            Text((listOf(s.addon) + facts + listOfNotNull(if (s.hash != null) "Real-Debrid" else null, if (lastGood) "Played before" else null)).joinToString("  ·  "),
                color = soft, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, raw = true)
        }
        // Only what needs attention gets a word; a confirmed match needs none
        val tag = when {
            active -> "Playing"
            wrong -> "Reported wrong"
            s.label.startsWith(StremioLogic.LAST_RESORT) -> "Manual"
            match == StremioLogic.MISMATCH -> "Other title"
            match == StremioLogic.VERIFIED -> null
            else -> "Not confirmed"
        }
        if (tag != null) Text(tag, color = if (tag == "Playing") ink else soft, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, raw = true)
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

/** Play: the title's picture with a thin red line while the source starts (no list, no spinner). Back cancels. */
@Composable
internal fun StartScreen(title: String, sub: String, backdrop: String?, note: String, onBack: () -> Unit) {
    Popup(alignment = Alignment.Center, onDismissRequest = onBack, properties = PopupProperties(focusable = true)) {
        StartPicture(Modifier.fillMaxSize(), title, sub, backdrop, note)
    }
}

@Composable
internal fun StartPicture(modifier: Modifier, title: String, sub: String, backdrop: String?, note: String) {
    Box(modifier.background(Color.Black)) {
        if (backdrop != null) coil.compose.AsyncImage(backdrop, null, Modifier.fillMaxSize().graphicsLayer { alpha = 0.55f },
            contentScale = androidx.compose.ui.layout.ContentScale.Crop, alignment = Alignment.TopCenter)
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color(0xE6000000), Color(0x66000000), Color(0x14000000)))))
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xD9000000))))
        Column(Modifier.align(Alignment.BottomStart).padding(start = 64.dp, bottom = 72.dp).widthIn(max = 640.dp)) {
            Text(title, color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 48.sp, raw = true)
            if (sub.isNotEmpty()) Text(sub, color = Color(0xFFB3B3B3), fontSize = 18.sp, modifier = Modifier.padding(top = 6.dp), raw = true)
            LoadLine(Modifier.padding(top = 26.dp, bottom = 12.dp))
            Text(note, color = Color(0xFF8C8C8C), fontSize = 14.sp, raw = true)
        }
    }
}

/** The title shown from Play until the player has its first picture (same look as the start screen). */
object StartCover {
    private var id: String? = null; private var title = ""; private var sub = ""; private var art: String? = null; private var at = 0L
    fun set(itemId: String, t: String, s: String, a: String?) { id = itemId; title = t; sub = s; art = a; at = System.currentTimeMillis() }
    /** Only for the title just started from Play (and not for a stale one from long ago). */
    fun owns(itemId: String, playingTitle: String): Boolean =
        System.currentTimeMillis() - at < 10 * 60_000L && (itemId == id || (title.isNotEmpty() && playingTitle.startsWith(title)))
    @Composable fun View(modifier: Modifier, note: String) = StartPicture(modifier, title, sub, art, note)
}

/** Thin red line moving along a grey track: loading without a spinner. Still with "Motion: off". */
@Composable
internal fun LoadLine(modifier: Modifier = Modifier) {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "line")
    val x by t.animateFloat(-0.36f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1150, easing = androidx.compose.animation.core.FastOutSlowInEasing)), label = "lineX")
    Box(modifier.width(220.dp).height(2.dp).clip(RoundedCornerShape(1.dp)).background(Color(0x29FFFFFF))) {
        Box(Modifier.fillMaxHeight().width(80.dp).graphicsLayer { translationX = (if (Look.motion > 0) x else 0f) * 220.dp.toPx() }.background(Color(0xFFE60023)))
    }
}
