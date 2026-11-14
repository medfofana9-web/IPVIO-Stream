package com.ipvio.stream

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val S_SORT_AZ = 6
private const val S_SORT_PLAYLIST = 8

/** Index of the current list – is kept when leaving and coming back. */
internal object SearchCache {
    private var idx: SearchIndex? = null
    @Synchronized fun get(all: List<Item>): SearchIndex? = idx?.takeIf { it.all === all }
    @Synchronized fun build(all: List<Item>): SearchIndex = get(all) ?: SearchIndex(all).also { idx = it }

    private var sug: Pair<List<Item>, WordSuggest>? = null
    @Synchronized fun suggest(all: List<Item>): WordSuggest = sug?.takeIf { it.first === all }?.second ?: WordSuggest(all).also { sug = all to it }
}

/** Search (tab 4): "Recently watched" separated into movies, series and Live TV. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    tab: Int, state: LazyListState,
    onOpen: (Item) -> Unit, onBrowse: (BrowseReq) -> Unit, onTab: (Int) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val all = PlaylistStore.channels
    // Build the index in the background; searches also run in the background and only after a short typing pause
    val index by produceState(SearchCache.get(all), all) { value = safely(null) { SearchCache.build(all) } }
    val byId = index?.byId.orEmpty()
    val suggestIdx by produceState<WordSuggest?>(null, all) { value = safely(null) { SearchCache.suggest(all) } }
    val firstSugg = remember { FocusRequester() }
    val hist = History.ids.mapNotNull { byId[it] }
    // Smaller cards than elsewhere so more hits are visible next to the keyboard
    val cardW = (AppSettings.cardW * 0.8f).toInt().coerceAtLeast(110)
    val firstRes = remember { FocusRequester() }
    val firstHistory = remember { FocusRequester() }
    val sideFocus = remember { FocusRequester() }
    var inSidebar by remember { mutableStateOf(false) }

    SideEffect { NavState.tab = tab; ContentFocus.tab = tab }
    var canVoice by remember { mutableStateOf(VoiceSearch.available(ctx)) }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        res.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let {
            SearchState.query = it
            AppSettings.addSearch(ctx, it)
        }
    }
    var fieldFocused by remember { mutableStateOf(false) }
    var focusItem by remember { mutableStateOf<Item?>(null) }
    // When opening the search go straight to the search field (not when returning from a detail page: then to the chosen title).
    // Only stop when the field REALLY has the focus – the sidebar still holds it briefly when switching.
    LaunchedEffect(Unit) {
        if (LastFocus.pending != null) return@LaunchedEffect
        AppNav.focusTab = -1
        repeat(20) {
            delay(100)
            if (fieldFocused) return@LaunchedEffect
            runCatching { ContentFocus.req.requestFocus() }
        }
    }
    BackHandler(enabled = inSidebar && tab != 0) { onTab(0) }

    val q = SearchState.query.trim()
    val none = Triple(emptyList<Item>(), emptyList<Item>(), emptyList<Item>())
    // A result always belongs to exactly one input: while still searching it says "Searching …" instead of "No hits"
    val found by produceState("" to none, q, index) {
        val idx = index ?: return@produceState
        if (q.length < 2) { value = q to none; return@produceState }
        delay(250)
        value = q to safely(none) {
            val (m, s, l) = idx.find(q)
            Triple(PlaylistLogic.firstPerKey(m) { it.mk + "|" + it.lang }, s, l)
        }
    }
    val searching = q.length >= 2 && found.first != q
    val (rm0, rs0, rl0) = if (q.length < 2 || searching) none else found.second
    val fl = SearchState.filter
    // Word suggestions for the word being typed
    val sugg = remember(SearchState.query, suggestIdx) { suggestIdx?.suggest(WordSuggest.lastWord(SearchState.query)).orEmpty() }
    // Sports hub filter: live channels that look like sports channels (the hub itself opens with the button)
    val rl = when (fl) { 0, 3 -> rl0; 4 -> remember(rl0) { rl0.filter { SearchSport.isSport(it) } }; else -> emptyList() }
    val rs = if ((fl == 0 || fl == 2) && FofPrefs.providerVod) rs0 else emptyList()
    // The same movie in several versions only once (the source is chosen on the detail page)
    val rmAll: List<Item> = rm0
    val rm = if ((fl == 0 || fl == 1) && FofPrefs.providerVod) rmAll else emptyList()
    // Fof TV: one search over everything – catalog movies and shows (Cinemeta), people, games, teams and leagues (ESPN),
    // channels and what is on TV. Each source loads on its own after a short typing pause; a slow one never holds up the rest.
    SportLeagues.init(ctx); SportFavs.init(ctx); SportPrefs.init(ctx)
    val live = remember(all) { all.filter { it.kind == Kind.LIVE } }
    LaunchedEffect(Unit) { Stremio.ensure(ctx); launch(Dispatchers.IO) { runCatching { SearchSports.games() } } }
    val cm by produceState(q to (emptyList<Item>() to emptyList<Item>()), q) {
        if (q.length < 2) { value = q to (emptyList<Item>() to emptyList()); return@produceState }
        delay(300)
        val r = safely(emptyList<Item>() to emptyList<Item>()) {
            val m = async { Catalog.search(Kind.MOVIE, q) }
            val s = async { Catalog.search(Kind.SERIES, q) }
            SearchAllLogic.order(m.await().distinctBy { it.id }, q, { it.title }, { "${it.year ?: 0}" }) to
                SearchAllLogic.order(s.await().distinctBy { it.id }, q, { it.title }, { "${it.year ?: 0}" })
        }
        value = q to r
    }
    val person by produceState<Pair<String, List<Item>>?>(null, q) {
        value = null
        if (q.length < 3) return@produceState
        delay(450)
        value = runCatching { TmdbExtra.person(q) }.getOrNull()?.let { (n, l) -> n to l.distinctBy { it.id } }
    }
    val sport by produceState(Triple(emptyList<SportData.Game>(), emptyList<Pair<SportData.League, SportData.Team>>(), emptyList<SportData.League>()), q) {
        value = Triple(emptyList(), emptyList(), emptyList())
        if (q.length < 2) return@produceState
        delay(300)
        value = safely(Triple(emptyList(), emptyList(), emptyList())) {
            val g = SearchSports.games()
            Triple(SearchSports.findGames(g, q), SearchSports.findTeams(g, q), SearchSports.findLeagues(q))
        }
    }
    val shows by produceState(emptyList<GuideSearchLogic.Hit>(), q, live) {
        value = emptyList()
        if (q.length < 3) return@produceState
        delay(350)
        value = safely(emptyList()) { SearchGuide.shows(live, q) }
    }
    val cmReady = q.length >= 2 && cm.first == q
    val cmM = if ((fl == 0 || fl == 1) && cmReady) cm.second.first else emptyList()
    val cmS = if ((fl == 0 || fl == 2) && cmReady) cm.second.second else emptyList()
    val per = person?.takeIf { fl == 0 || fl == 1 || fl == 2 }
    val (sg, st, sl) = if (fl == 0 || fl == 4) sport else Triple(emptyList(), emptyList(), emptyList())
    val sh = if (fl == 0 || fl == 3) shows else emptyList()
    // Live games play at once on the best matching channel (failover and score bug from the sports session);
    // other games open their page in the sports area
    val onGame: (SportData.Game) -> Unit = { g ->
        AppSettings.addSearch(ctx, q)
        scope.launch {
            val chans = if (g.state == 1) withContext(Dispatchers.Default) { SportWatch.prewarm(live); SportWatch.channels(g, live) } else emptyList()
            if (chans.isNotEmpty()) {
                SportSession.start(g, chans, live)
                ZapContext.list = SportSession.zapList().ifEmpty { chans }; ZapContext.key = null
                onOpen(SportSession.firstChannel() ?: chans[0])
            } else { openInHub(HubPage.Match(g)); onTab(MenuLogic.SPORT) }
        }
    }
    val firstKey = when {
        sg.any { it.state == 1 } -> "games"; cmM.isNotEmpty() -> "cmm"; cmS.isNotEmpty() -> "cms"; per != null -> "person"
        sg.isNotEmpty() -> "games"; st.isNotEmpty() || sl.isNotEmpty() -> "teams"
        rm.isNotEmpty() -> "rm"; rs.isNotEmpty() -> "rs"; rl.isNotEmpty() -> "rl"; sh.isNotEmpty() -> "shows"; else -> ""
    }
    fun fr(k: String) = if (firstKey == k) firstRes else null
    // Back: in the search field (keyboard already closed) first to the recent searches or hits, otherwise into the sidebar
    BackHandler(enabled = !inSidebar) {
        val target = when {
            !fieldFocused -> null
            firstKey.isNotEmpty() -> firstRes
            else -> null
        }
        // requestFocus returns false (no exception) when the target is not on screen: then go on to the sidebar instead of doing nothing
        val moved = target != null && runCatching { target.requestFocus() }.getOrDefault(false)
        if (!moved) runCatching { sideFocus.requestFocus() }
    }

    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize().sidebarPad()) {
            // Left: input, quick filters, suggestions and last searches above the keyboard, the keyboard at the bottom
            Column(
                Modifier.width(430.dp).fillMaxHeight().padding(start = DS.PageX, top = 16.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Spacer(Modifier.weight(1f))
                // Everything sits directly above the keyboard and is fully visible (wraps into more rows instead of scrolling away)
                val recent = AppSettings.searchHistory.toList().take(6)
                if (recent.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    recent.forEachIndexed { i, term ->
                        // OK searches again, long OK / menu key removes just this entry
                        MiniChip(term, false, (if (i == 0) Modifier.focusRequester(firstHistory) else Modifier)
                            .menuKeys(onMenu = { AppSettings.removeSearch(ctx, term) }, onLong = { AppSettings.removeSearch(ctx, term) }), raw = true) { SearchState.query = term }
                    }
                    MiniChip("Verlauf löschen", false) { AppSettings.clearSearch(ctx) }
                }
                if (sugg.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    sugg.forEachIndexed { i, w ->
                        MiniChip(w.replaceFirstChar { it.uppercase() }, false, if (i == 0) Modifier.focusRequester(firstSugg) else Modifier, raw = true) {
                            SearchState.query = WordSuggest.apply(SearchState.query, w)
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SEARCH_FILTERS.forEachIndexed { i, name -> MiniChip(name, SearchState.filter == i, raw = true) { SearchState.filter = i } }
                }
                if (AppSettings.systemKeyboard) {
                    // The device's own keyboard (e.g. Gboard) in a normal text field: OK or a tap opens it
                    Field(
                        SearchState.query, { SearchState.query = it.take(60) }, "Filme, Serien oder Sender suchen…",
                        Modifier.fillMaxWidth(),
                        inner = Modifier.focusRequester(ContentFocus.req).onFocusChanged { fieldFocused = it.isFocused || it.hasFocus },
                        onDone = { SearchState.query.trim().takeIf { it.length >= 2 }?.let { AppSettings.addSearch(ctx, it) } }
                    )
                } else {
                QueryBox(SearchState.query, Modifier.fillMaxWidth())
                // Own on-screen keyboard (instead of the TV's keyboard): hits appear while typing
                SearchKeyboard(
                    Modifier.fillMaxWidth(),
                    onKey = { SearchState.query = (SearchState.query + it).take(60) },
                    onSpace = { if (SearchState.query.isNotEmpty() && !SearchState.query.endsWith(" ")) SearchState.query += " " },
                    onDelete = { SearchState.query = SearchState.query.dropLast(1) },
                    onClear = { SearchState.query = "" },
                    onPhone = { RemoteUi.show = true },
                    onVoice = if (canVoice) ({ runCatching { voice.launch(VoiceSearch.intent()) }.onFailure { canVoice = false } }) else null,
                    firstKey = ContentFocus.req,
                    onFocusChange = { fieldFocused = it },
                    onRight = {
                        val target = if (firstKey.isNotEmpty()) firstRes else null
                        target != null && runCatching { target.requestFocus() }.isSuccess
                    }
                )
                }
            }
            // Right: info panel of the highlighted title on top, the hits below
            Column(Modifier.weight(1f).fillMaxHeight()) {
                val preview = focusItem ?: (cmM.firstOrNull() ?: cmS.firstOrNull() ?: rm.firstOrNull() ?: rs.firstOrNull() ?: rl.firstOrNull() ?: hist.firstOrNull())
                SearchInfoPanel(preview, Modifier.fillMaxWidth().height(190.dp).padding(start = 24.dp, end = DS.PageX, top = 16.dp))
                CompositionLocalProvider(LocalSearchFocus provides { focusItem = it }) {
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = state, contentPadding = PaddingValues(bottom = 48.dp)) {
                if (fl == 4) item(key = "hubbtn") {
                    Row(Modifier.padding(horizontal = DS.PageX, vertical = 6.dp)) {
                        PillButton("Sports Hub öffnen", icon = AIcon.Live) { onTab(MenuLogic.SPORT) }
                    }
                }
                if (q.length < 2) {
                    val hm = hist.filter { it.kind == Kind.MOVIE }
                    val hs = hist.filter { it.kind == Kind.SERIES }
                    val hl = hist.filter { it.kind == Kind.LIVE }
                    if (hm.isNotEmpty()) item(key = "hm") {
                        SearchRow("Zuletzt geschaut · Filme", hm.take(30), cardW, onOpen) {
                            onBrowse(BrowseReq("Zuletzt geschaut · Filme", hm, S_SORT_PLAYLIST))
                        }
                    }
                    if (hs.isNotEmpty()) item(key = "hs") {
                        SearchRow("Zuletzt geschaut · Serien", hs.take(30), cardW, onOpen) {
                            onBrowse(BrowseReq("Zuletzt geschaut · Serien", hs, S_SORT_PLAYLIST))
                        }
                    }
                    if (hl.isNotEmpty()) item(key = "hl") {
                        SearchRow("Zuletzt geschaut · Live TV", hl.take(30), cardW, onOpen) {
                            onBrowse(BrowseReq("Zuletzt geschaut · Live TV", hl, S_SORT_PLAYLIST))
                        }
                    }
                } else {
                    val openS: (Item) -> Unit = { i -> AppSettings.addSearch(ctx, q); onOpen(i) }
                    if (searching) item {
                        Text("Suche …", color = Muted, fontSize = 16.sp, modifier = Modifier.padding(horizontal = DS.PageX, vertical = 16.dp))
                    } else if (cmReady && rm.isEmpty() && rs.isEmpty() && rl.isEmpty() && cmM.isEmpty() && cmS.isEmpty() && per == null && sg.isEmpty() && st.isEmpty() && sl.isEmpty() && sh.isEmpty()) item {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            EmptyState(AIcon.Info, "Keine Treffer", "Prüfe die Schreibweise oder suche nach einem Teil des Titels. Kleine Tippfehler findet die Suche trotzdem.")
                        }
                    }
                    val report: (Item) -> Unit = { focusItem = it }
                    if (sg.any { it.state == 1 }) item(key = "games") { SearchGameRow(sg, fr("games"), onGame) }
                    if (cmM.isNotEmpty()) item(key = "cmm") { SearchTitleRow("Movies", cmM, cardW, fr("cmm"), report, openS) }
                    if (cmS.isNotEmpty()) item(key = "cms") { SearchTitleRow("Shows", cmS, cardW, fr("cms"), report, openS) }
                    if (per != null) item(key = "person") { SearchTitleRow(per.first, per.second, cardW, fr("person"), report, openS) }
                    if (sg.isNotEmpty() && sg.none { it.state == 1 }) item(key = "games") { SearchGameRow(sg, fr("games"), onGame) }
                    if (st.isNotEmpty() || sl.isNotEmpty()) item(key = "teams") {
                        SearchTeamRow(st, sl, fr("teams"),
                            onTeam = { l, t -> AppSettings.addSearch(ctx, q); openInHub(HubPage.Team(l, t)); onTab(MenuLogic.SPORT) },
                            onLeague = { l -> AppSettings.addSearch(ctx, q); openInHub(HubPage.League(l)); onTab(MenuLogic.SPORT) })
                    }
                    if (rm.isNotEmpty()) item(key = "rm") {
                        SearchRow("Filme", rm, cardW, openS, if (firstKey == "rm") firstRes else null) {
                            AppSettings.addSearch(ctx, q); onBrowse(BrowseReq("Suche „$q“ · Filme", rm, S_SORT_AZ))
                        }
                    }
                    if (rs.isNotEmpty()) item(key = "rs") {
                        SearchRow("Serien", rs, cardW, openS, if (firstKey == "rs") firstRes else null) {
                            AppSettings.addSearch(ctx, q); onBrowse(BrowseReq("Suche „$q“ · Serien", rs, S_SORT_AZ))
                        }
                    }
                    if (rl.isNotEmpty()) item(key = "rl") {
                        SearchRow("Live TV", rl, cardW, openS, if (firstKey == "rl") firstRes else null) {
                            AppSettings.addSearch(ctx, q); onBrowse(BrowseReq("Suche „$q“ · Live TV", rl, S_SORT_AZ))
                        }
                    }
                    if (sh.isNotEmpty()) item(key = "shows") { SearchShowRow(sh, fr("shows")) { ch -> AppSettings.addSearch(ctx, q); onOpen(ch) } }
                }
                    }
                }
            }
        }
        Sidebar(
            tab,
            { id -> if (id == tab) scope.launch { state.scrollToItem(0) }; onTab(id) },
            Modifier.align(SidebarPrefs.align()), sideFocus
        ) { inSidebar = it }
    }
}

@Composable
private fun SearchRowHeader(title: String, count: Int, headerReq: FocusRequester, firstCard: FocusRequester, rowState: androidx.compose.foundation.lazy.LazyListState, onAll: () -> Unit) {
    val sc = rememberCoroutineScope()
    val key = NavState.tab.toString() + "|" + title
    LaunchedEffect(Unit) {
        if (PillMemory.key == key) {
            delay(250)
            runCatching { headerReq.requestFocus() }
            PillMemory.key = null
        }
    }
    Row(Modifier.padding(start = DS.PageX, top = 22.dp), verticalAlignment = Alignment.CenterVertically) {
        SmallPill("Alle $count", Modifier.focusRequester(headerReq).onPreviewKeyEvent { ev ->
            if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionDown) {
                // First card not in view (row shifted sideways) or not yet composed: first to the start, then highlight.
                // Before, the focus got stuck here because the failure was swallowed.
                if (runCatching { firstCard.requestFocus() }.isFailure) sc.launch {
                    rowState.scrollToItem(0)
                    repeat(3) { withFrameNanos { } }
                    runCatching { firstCard.requestFocus() }
                }
                true
            } else false
        }, trailing = AIcon.Right) { PillMemory.key = key; onAll() }
        Spacer(Modifier.width(16.dp))
        Box(Modifier.width(4.dp).height(22.dp).clip(RoundedCornerShape(2.dp)).background(Accent1))
        Spacer(Modifier.width(10.dp))
        Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SearchAllTile(live: Boolean, w: Dp, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (f) focusScale(1.08f) else 1f, label = "searchalltile")
    if (live) {
        val shape = RoundedCornerShape(DS.RadiusM)
        Column(
            Modifier.width(w).graphicsLayer { scaleX = s; scaleY = s }.focusGlow(f, shape).clip(shape).background(Surf)
                .border(DS.FocusBorder, if (f) Accent2 else Surf2, shape)
                .onFocusChanged { f = it.isFocused }
                .clickable(onClick = onClick)
        ) {
            Box(Modifier.fillMaxWidth().height(110.dp), contentAlignment = Alignment.Center) {
                IconLabel(null, "Alle anzeigen", Color.White, 17.sp, FontWeight.Bold, trailing = AIcon.Right)
            }
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(" ", fontSize = 15.sp, maxLines = 1)
                Text(" ", fontSize = 13.sp, maxLines = 1)
            }
        }
    } else {
        val shape = RoundedCornerShape(DS.RadiusM)
        Column(
            Modifier.width(w).graphicsLayer { scaleX = s; scaleY = s }
                .onFocusChanged { f = it.isFocused }
                .clickable(onClick = onClick)
        ) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(2f / 3f).focusGlow(f, shape).clip(shape).background(Surf)
                    .border(DS.FocusBorder, if (f) Accent2 else Surf2, shape),
                contentAlignment = Alignment.Center
            ) {
                IconLabel(null, "Alle anzeigen", Color.White, 18.sp, FontWeight.Bold, trailing = AIcon.Right)
            }
            Text(" ", fontSize = 15.sp, maxLines = 1, modifier = Modifier.padding(top = 8.dp))
            Text(" ", fontSize = 13.sp, maxLines = 1)
        }
    }
}

@Composable
private fun SearchRow(
    title: String, list: List<Item>, cardW: Int, onOpen: (Item) -> Unit,
    firstReq: FocusRequester? = null, onAll: () -> Unit
) {
    val rowState = RowStates.get("${NavState.tab}|$title")
    val liveRow = list.firstOrNull()?.kind == Kind.LIVE
    val headerReq = remember { FocusRequester() }
    val firstCard = firstReq ?: remember { FocusRequester() }
    val report = LocalSearchFocus.current
    Column {
        SearchRowHeader(title, list.size, headerReq, firstCard, rowState, onAll)
        Box(
            Modifier.onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp && e.nativeKeyEvent.repeatCount == 0) {
                    runCatching { headerReq.requestFocus() }.isSuccess
                } else false
            }
        ) {
            LazyRow(
                state = rowState,
                contentPadding = PaddingValues(horizontal = DS.PageX, vertical = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                itemsIndexed(list.take(60)) { i, x ->
                    val fr = (if (i == 0) Modifier.focusRequester(firstCard) else Modifier).onFocusChanged { if (it.hasFocus) report(x) }
                    if (x.kind == Kind.LIVE) LiveCard(x, { onOpen(x) }, Modifier.width((cardW * 4 / 3).dp).then(fr))
                    else PosterCard(x, { onOpen(x) }, Modifier.width(cardW.dp).restoreFocus(x.id).then(fr))
                }
                item { SearchAllTile(liveRow, if (liveRow) (cardW * 4 / 3).dp else cardW.dp, onAll) }
            }
        }
    }
}


/** Display of the input (read only: typing is done with the on-screen keyboard on the left). */
@Composable
private fun QueryBox(text: String, modifier: Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier.height(62.dp).clip(shape).background(Surf).border(DS.Border, Surf2, shape).padding(horizontal = 20.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        if (text.isEmpty()) Text("Movies, shows, games, teams, channels…", color = Muted, fontSize = 20.sp, maxLines = 1, raw = true)
        else Text(text, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, raw = true)
    }
}

private val SEARCH_FILTERS = listOf("All", "Movies", "Shows", "Live TV", "Sports")

/** Heuristic for the sports hub filter: channel name or category looks like sports. */
internal object SearchSport {
    private val WORDS = listOf("sport", "dazn", "espn", "eurosport", "bein", "arena", "sky sport", "nba", "nfl", "nhl", "ufc", "f1", "fußball", "fussball", "football", "soccer", "tennis", "golf")
    fun isSport(i: Item): Boolean {
        val t = (i.title + " " + i.group).lowercase()
        return WORDS.any { it in t }
    }
}

private val KB_DIGITS = "1234567890"
private val KB_LETTERS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
private val KB_SYMBOLS = listOf("-/:;()&@\"", ".,?!'+=_%", "#*<>[]{}~")
// Special letters for other languages (German, French, Spanish, Turkish, Nordic, Slavic ...)
private val KB_SPECIAL = listOf("äöüßéèêàâç", "ñışğčćšžđå", "øæœíóúáãõ")

/**
 * QWERTY on-screen keyboard: a number row on top (no extra layer needed), three key rows, then layer switch (symbols / special letters),
 * backspace, wide space bar and clear.
 */
@Composable
internal fun SearchKeyboard(
    modifier: Modifier, onKey: (String) -> Unit, onSpace: () -> Unit, onDelete: () -> Unit, onClear: () -> Unit,
    onPhone: (() -> Unit)?, onVoice: (() -> Unit)?,
    firstKey: FocusRequester, onFocusChange: (Boolean) -> Unit, onRight: () -> Boolean
) {
    var symbols by remember { mutableStateOf(false) }
    var special by remember { mutableStateOf(false) }
    val rows = if (symbols) KB_SYMBOLS else if (special) KB_SPECIAL else KB_LETTERS
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Number row (always visible)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            KB_DIGITS.forEachIndexed { c, k ->
                KbKey(k.toString(), Modifier.weight(1f).then(if (c == 0) Modifier.focusRequester(firstKey) else Modifier), onFocusChange, if (c == KB_DIGITS.lastIndex) onRight else null) { onKey(k.toString()) }
            }
        }
        rows.forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                // Row 2 is indented half a key like on a real keyboard
                if (r == 1 && !symbols) Spacer(Modifier.weight(0.5f))
                if (r == 2) KbKey(if (symbols) "ABC" else "#+=", Modifier.weight(1.5f), onFocusChange, null) { symbols = !symbols; special = false }
                row.forEachIndexed { c, k ->
                    KbKey(k.toString(), Modifier.weight(1f), onFocusChange, if (r < 2 && c == row.lastIndex) onRight else null) { onKey(k.toString()) }
                }
                if (r == 1 && !symbols) Spacer(Modifier.weight(0.5f))
                if (r == 2) KbKey("⌫", Modifier.weight(1.5f), onFocusChange, onRight) { onDelete() }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            KbKey("Leer", Modifier.weight(if (onPhone == null) (if (onVoice != null) 3.9f else 4.5f) else if (onVoice != null) 2f else 2.6f), onFocusChange, null) { onSpace() }
            if (onPhone != null) KbKey("Handy", Modifier.weight(1.9f), onFocusChange, null, icon = AIcon.Phone) { onPhone() }
            if (onVoice != null) KbKey("", Modifier.weight(0.9f), onFocusChange, null, icon = AIcon.Mic) { onVoice() }
            KbKey(if (special) "abc" else "äöü", Modifier.weight(1.5f), onFocusChange, null) { special = !special; symbols = false }
            KbKey("Alles", Modifier.weight(1.8f), onFocusChange, onRight) { onClear() }
        }
    }
}

@Composable
private fun KbKey(label: String, modifier: Modifier, onFocusChange: (Boolean) -> Unit, onRight: (() -> Boolean)?, icon: AIcon? = null, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(if (f) focusScale(1.08f) else 1f, label = "kbkey")
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.height(42.dp).graphicsLayer { scaleX = sc; scaleY = sc }.clip(shape)
            .background(if (f) DS.FocusFill else DS.Overlay2)
            .onPreviewKeyEvent { e ->
                if (onRight != null && e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) onRight() else false
            }
            .onFocusChanged { if (f != it.isFocused) { f = it.isFocused; onFocusChange(it.isFocused) } }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            if (icon != null) AppIcon(icon, if (f) DS.FocusInk else Color.White, size = 18.dp)
            if (label.isNotEmpty()) Text(label, color = if (f) DS.FocusInk else Color.White, fontSize = if (label.length > 1) 15.sp else 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

private val LocalSearchFocus = compositionLocalOf<(Item) -> Unit> { {} }

/** Info panel above the hits (like the cinema view): picture, title, format, year, rating, genres and description of the highlighted title. */
@Composable
private fun SearchInfoPanel(item: Item?, modifier: Modifier) {
    val ctx = LocalContext.current
    val shape = RoundedCornerShape(DS.RadiusM)
    Box(modifier.clip(shape).background(Surf).border(DS.Border, Surf2, shape)) {
        if (item == null) {
            Text("Suche nach einem Titel: hier erscheinen Beschreibung, Bewertung und Format des markierten Treffers.", color = Muted, fontSize = 16.sp,
                modifier = Modifier.align(Alignment.Center).padding(24.dp))
            return@Box
        }
        // Wait a moment so quickly moving across the hits does not load anything
        val meta by produceState<Meta?>(null, item.id) {
            value = null
            if (item.kind == Kind.LIVE) return@produceState
            delay(200)
            value = MetaRepo.basic(ctx, item) ?: MetaRepo.cached(item)
        }
        val epg by produceState(emptyList<EpgEntry>(), item.id) {
            value = emptyList()
            if (item.kind != Kind.LIVE) return@produceState
            delay(200)
            value = kotlinx.coroutines.withContext(Dispatchers.Default) { Epg.now(item) }
        }
        val m = meta
        val nowE = Epg.current(epg)
        Row(Modifier.fillMaxSize().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val img = m?.poster ?: item.logo
            Box(Modifier.fillMaxHeight().aspectRatio(if (item.kind == Kind.LIVE) 1f else 2f / 3f).clip(RoundedCornerShape(DS.RadiusS)).background(DS.Overlay2)) {
                if (img != null) coil.compose.AsyncImage(img, null, Modifier.fillMaxSize(), contentScale = if (item.kind == Kind.LIVE) androidx.compose.ui.layout.ContentScale.Fit else androidx.compose.ui.layout.ContentScale.Crop)
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(raw = true, text = item.title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                val parts = ArrayList<String>()
                parts.add(when (item.kind) { Kind.MOVIE -> "Film"; Kind.SERIES -> "Serie"; else -> "Live" })
                (item.year?.toString() ?: m?.year)?.let { parts.add(it) }
                m?.runtime?.takeIf { it > 0 }?.let { parts.add("$it Min") }
                (item.quality)?.let { parts.add(it) }
                m?.genres?.take(3)?.let { parts.addAll(it) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    m?.score()?.let { sc ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(AIcon.Star, DS.Gold, size = 16.dp)
                            Text(raw = true, text = String.format(java.util.Locale.US, "%.1f", sc), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Text(raw = true, text = parts.joinToString("  ·  "), color = Muted, fontSize = 14.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                val body = when {
                    item.kind == Kind.LIVE && nowE != null -> hhmm(nowE.start) + " – " + hhmm(nowE.end) + "  " + nowE.title
                    item.kind == Kind.LIVE -> item.group
                    else -> m?.overview.orEmpty()
                }
                if (body.isNotBlank()) Text(raw = true, text = body, color = Color(0xFFD5D8E2), fontSize = 15.sp, maxLines = 4, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
    }
}

/** Compact chip for the quick filters, suggestions and last searches above the keyboard (no check mark). */
@Composable
private fun MiniChip(label: String, selected: Boolean, modifier: Modifier = Modifier, raw: Boolean = false, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Box(
        modifier.height(32.dp).clip(shape)
            .background(if (f) DS.FocusFill else if (selected) DS.Overlay3 else Surf)
            .border(DS.Border, if (f) DS.FocusFill else if (selected) Color(0x99FFFFFF) else DS.Hairline, shape)
            .onFocusChanged { f = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (f) DS.FocusInk else Color.White, fontSize = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1, raw = raw)
    }
}

/**
 * Runs one search source in the background. A source that fails (network, bad data, out of memory) gives [fallback]
 * instead of closing the app; only cancellation (a newer keystroke) passes through.
 */
private suspend fun <T> safely(fallback: T, block: suspend kotlinx.coroutines.CoroutineScope.() -> T): T =
    try { withContext(Dispatchers.Default, block) }
    catch (e: kotlinx.coroutines.CancellationException) { throw e }
    catch (e: Throwable) { android.util.Log.w("FofSearch", "search source failed", e); fallback }
