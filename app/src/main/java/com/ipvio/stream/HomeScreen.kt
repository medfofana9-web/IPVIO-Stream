package com.ipvio.stream

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.key.key
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object SearchState {
    var query by mutableStateOf("")
    /** Quick filter: 0 all, 1 movies, 2 series, 3 live TV, 4 sports hub. Survives switching between keyboard and results. */
    var filter by mutableIntStateOf(0)
}

/** Titles of the big picture per area (tab) with the candidate ids they were picked from. */
private object HeroCache {
    val byTab = HashMap<Int, Pair<List<String>, List<Item>>>()
}

/** Last adopted genre state and trend list – kept when leaving the home screen. */
private object HomeVer {
    var value = -1
    var trend: Set<String> = emptySet()
}

private const val SORT_NEW = 0
private const val SORT_RATING = 2
private const val SORT_PLAYLIST = 8

private fun ipvioGroups(list: List<Item>): Map<String, List<Item>> {
    val map = LinkedHashMap<String, ArrayList<Item>>()
    for (item in list) {
        val m = MetaRepo.cached(item)
        val cats = if (m != null) Genres.categoriesOf(m) else emptyList()
        if (cats.isEmpty()) map.getOrPut("Sonstige") { ArrayList() }.add(item)
        else for (c in cats) map.getOrPut(c) { ArrayList() }.add(item)
    }
    val prio = listOf("Anime", "Animation")
    val sorted = map.entries.sortedWith(
        compareBy<Map.Entry<String, ArrayList<Item>>>(
            { if (it.key in prio) prio.indexOf(it.key) else if (it.key == "Sonstige") 1000 else 10 },
            { -it.value.size }
        )
    )
    return sorted.associate { it.key to it.value }
}

private fun providerGroups(list: List<Item>): Map<String, List<Item>> {
    val g = list.groupBy { it.group }
    val order = PlaylistStore.catOrder
    if (order.isEmpty()) return g
    return g.entries.sortedBy { order[it.key] ?: 1_000_000 }.associate { it.key to it.value }
}

/**
 * Everything the home screen derives from the playlist. Computed on a background thread (with 100,000+ titles
 * this takes noticeable time); the UI shows the previous state or the loading skeleton meanwhile.
 */
private class HomeData(
    val live: List<Item>, val movies: List<Item>, val series: List<Item>,
    val liveP: Map<String, List<Item>>, val movieP: Map<String, List<Item>>, val seriesP: Map<String, List<Item>>,
    val movieG: Map<String, List<Item>>, val seriesG: Map<String, List<Item>>,
    val topM: List<Item>, val topS: List<Item>, val byId: Map<String, Item>, val trendItems: List<Item>,
    val genresOk: Boolean
)

private fun buildHome(all: List<Item>, ipvioWanted: Boolean, trend: Set<String>): HomeData {
    val live = ArrayList<Item>(); val movies0 = ArrayList<Item>(); val series = ArrayList<Item>()
    for (i in all) when (i.kind) { Kind.LIVE -> live.add(i); Kind.MOVIE -> movies0.add(i); Kind.SERIES -> series.add(i) }
    // Show the same movie in several versions (HD, 4K, …) only once; the version is chosen on the detail page (source)
    val movies = PlaylistLogic.firstPerKey(movies0) { it.mk + "|" + it.lang }
    val genresOk = MetaRepo.enabled() && Classifier.supported(movies.size + series.size)
    val ipvio = ipvioWanted && genresOk
    val movieP = providerGroups(movies)
    val seriesP = providerGroups(series)
    val trendItems = if (trend.isEmpty()) emptyList() else (movies.asSequence() + series.asSequence()).filter { Trending.norm(it.title) in trend }.toList()
    return HomeData(
        live, movies, series, providerGroups(live), movieP, seriesP,
        if (ipvio) ipvioGroups(movies) else movieP, if (ipvio) ipvioGroups(series) else seriesP,
        topRated(movies), topRated(series), all.associateBy { it.id }, trendItems, genresOk
    )
}

/** Filtered lists per area, so the same list object comes back and [HomeCache] can recognize it (no full recompute on every tab switch). */
private object AreaCache {
    private val m = HashMap<String, Pair<List<Item>, List<Item>>>()
    @Synchronized fun get(all: List<Item>, tab: Int, k: Int): List<Item> {
        val key = "$tab|$k"
        m[key]?.let { if (it.first === all) return it.second }
        return AreaSource.filter(all, tab, k).also { m[key] = all to it }
    }
}

/** Last result: when returning to the home screen (e.g. from the detail page) nothing has to be recomputed. */
private object HomeCache {
    private var all: List<Item>? = null
    private var order: Map<String, Int>? = null
    private var want = false
    private var ver = -1
    private var trend: Set<String>? = null
    private var data: HomeData? = null

    @Synchronized fun get(all: List<Item>, order: Map<String, Int>, want: Boolean, ver: Int, trend: Set<String>): HomeData? =
        if (all === this.all && order === this.order && want == this.want && ver == this.ver && trend === this.trend) data else null

    @Synchronized fun put(all: List<Item>, order: Map<String, Int>, want: Boolean, ver: Int, trend: Set<String>, d: HomeData) {
        this.all = all; this.order = order; this.want = want; this.ver = ver; this.trend = trend; data = d
    }

    /** For the wait after a change: better show the previous state than an empty skeleton. */
    @Synchronized fun last(): HomeData? = data
}

private val EMPTY_HOME = HomeData(
    emptyList(), emptyList(), emptyList(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(),
    emptyList(), emptyList(), emptyMap(), emptyList(), false
)

private fun topRated(list: List<Item>): List<Item> =
    list.mapNotNull { i ->
        val m = MetaRepo.cached(i)
        val sc = m?.score()
        if (m != null && (m.votes >= 300 || m.tmdbId <= 0) && sc != null) i to sc else null
    }.sortedByDescending { it.second }.map { it.first }

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    tab: Int, onTab: (Int) -> Unit, states: List<LazyListState>,
    onOpen: (Item) -> Unit, onPlayNow: (Item) -> Unit,
    onResume: (Entry, Item) -> Unit, onBrowse: (BrowseReq) -> Unit,
    onCategories: (CatReq) -> Unit, onAdd: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val allRaw = PlaylistStore.channels
    // Movies/series: playlist selection at the top (with several shown playlists) – only titles of this playlist
    val areaK = if (tab in 1..2) AreaSource.get(tab) else -1
    val all = remember(allRaw, areaK, tab) { if (areaK < 0) allRaw else AreaCache.get(allRaw, tab, areaK) }
    val order = PlaylistStore.catOrder
    // Adopt genre progress at most every 45 s: every adoption recomputes rows and top lists
    var ver by remember { mutableIntStateOf(HomeVer.value.takeIf { it >= 0 } ?: Classifier.version) }
    // New genre results rebuild the rows: not in the middle of scrolling
    LaunchedEffect(Unit) { while (true) { delay(45_000); if (ver != Classifier.version) { Busy.quiet(); ver = Classifier.version; HomeVer.value = ver } } }
    var trend by remember { mutableStateOf(HomeVer.trend) }
    LaunchedEffect(all.isNotEmpty()) { if (all.isNotEmpty()) { trend = Trending.titles(); HomeVer.trend = trend } }
    val wantIpvio = AppSettings.ipvioFilter
    val cached = HomeCache.get(all, order, wantIpvio, ver, trend)
    // When inputs change, the previous state stays visible until the new result (produceState keeps the value)
    val dataState = produceState(cached, all, order, wantIpvio, ver, trend) {
        value = HomeCache.get(all, order, wantIpvio, ver, trend)
            ?: withContext(Dispatchers.Default) { buildHome(all, wantIpvio, trend) }.also { HomeCache.put(all, order, wantIpvio, ver, trend, it); MainSampler.mark("home: rows built") }
    }
    // While recomputing: show the previous state, the loading skeleton only the very first time
    val prev = HomeCache.last()
    val data = dataState.value ?: prev?.takeIf { it.byId.isNotEmpty() && all.isNotEmpty() } ?: EMPTY_HOME
    val computing = dataState.value == null && prev == null && all.isNotEmpty()
    val live = data.live
    SportPlan.live = live
    val movies = data.movies
    val series = data.series
    val movieP = data.movieP
    val seriesP = data.seriesP
    val liveP = data.liveP
    val movieG = data.movieG
    val seriesG = data.seriesG
    val topM = data.topM
    val topS = data.topS
    val byId = data.byId
    val cardW = AppSettings.cardW
    // derivedStateOf: the lists are rebuilt only when history / favorites / My list really change, not on every
    // recomposition; the same list object lets the rows below skip instead of recomposing all their cards
    // Catalog titles (addons) are not in the playlist: they come from CatalogItems
    remember { CatalogItems.init(ctx) }
    val lk: (String) -> Item? = { id -> byId[id] ?: CatalogItems.get(id) }
    val hist by remember(byId) { derivedStateOf { History.ids.mapNotNull { lk(it) } } }
    val watchList by remember(byId) { derivedStateOf { Library.watch.mapNotNull { lk(it) } } }
    val favLive by remember(byId) { derivedStateOf { Library.favs.mapNotNull { byId[it] }.filter { it.kind == Kind.LIVE } } }
    // Row lists that were built anew inside the rows on every recomposition
    val newMovies = remember(movies) { movies.takeLast(40).reversed() }
    val newSeries = remember(series) { series.takeLast(40).reversed() }
    val histTop = remember(hist) { hist.take(30) }
    // Fof TV: Netflix-style rows from TMDB (trending and genres), matched to the playlist by title and year
    // Fof TV: one catalog for the whole app – Cinemeta (current popular titles), not the provider's lists
    remember { Catalog.init(ctx) }
    val cmPopM by produceState(Catalog.cached(Catalog.popular(Kind.MOVIE))) { value = Catalog.items(Catalog.popular(Kind.MOVIE)) }
    val cmPopS by produceState(Catalog.cached(Catalog.popular(Kind.SERIES))) { value = Catalog.items(Catalog.popular(Kind.SERIES)) }
    val trendItems = remember(cmPopM, cmPopS) { (0 until 20).flatMap { listOfNotNull(cmPopM.getOrNull(it), cmPopS.getOrNull(it)) } }
    val trendTop = remember(trendItems) { trendItems.take(10) }
    LaunchedEffect(trendTop) { Top10.ids = trendTop.mapTo(HashSet()) { it.id } }
    LaunchedEffect(Unit) { FofHome.apply(ctx) }
    val wlVod = remember(watchList) { watchList.filter { it.kind != Kind.LIVE } }
    // Live TV opens faster: genres and quality bundling of all channels are prepared in the background (both keep their result)
    LaunchedEffect(live) {
        if (live.isEmpty()) return@LaunchedEffect
        delay(2500)
        withContext(Dispatchers.Default) {
            runCatching { LiveGenre.split(live) }
            if (General.bundleLive) runCatching { LiveBundleLogic.bundle(live) }
            runCatching { SportWatch.prewarm(live) }
        }
    }
    // "Because you watched X": similar titles (TMDB) of the last started movie/series, matched against the own playlist
    val becauseSrc = hist.firstOrNull { it.kind != Kind.LIVE }
    val because by produceState<Pair<Item, List<Item>>?>(null, becauseSrc?.id, byId.size) {
        val src = becauseSrc ?: return@produceState
        val m = MetaRepo.extended(ctx, src) ?: return@produceState
        if (m.tmdbId <= 0) return@produceState
        val c = CreditsRepo.credits(ctx, m) ?: return@produceState
        val seen = History.ids.toSet()
        val l = withContext(Dispatchers.Default) {
            LibMatch.matches(PlaylistStore.channels, c.similar, 40).filter { it.id != src.id && it.id !in seen && it.kind != Kind.LIVE }
        }
        if (l.size >= 4) value = src to l.take(20)
    }

    // Check new episodes of favorite series (bell)
    LaunchedEffect(byId.size, Library.favs.size) {
        if (byId.isNotEmpty()) NewEpisodes.check(ctx, Library.favs.mapNotNull { byId[it] }.filter { it.kind == Kind.SERIES })
    }

    SideEffect { NavState.tab = tab; ContentFocus.tab = if (tab in 0 until MenuLogic.COUNT) tab else -1 }

    // Header (search, reminders, profile) and quick access as in the design
    var picker by remember { mutableStateOf(false) }
    var profileMenu by remember { mutableStateOf(false) }
    var joinParty by remember { mutableStateOf(false) }
    var surprise by remember { mutableStateOf(false) }
    val ready = remember { mutableStateListOf<Item>() }
    // Also use the sizes of movies/series as a key: if the computed home screen arrives only after the list (and without trend titles,
    // i.e. the same empty trend list), the selection stayed empty and the big picture was missing.
    // Big picture: the most popular titles of the catalog right now
    val candidates = remember(cmPopM, cmPopS, trendItems, tab, KidsMode.on) {
        // Kids profile: the big picture only shows family and animation titles
        fun k(l: List<Item>) = if (KidsMode.on) l.filter { KidsMode.ok(it) } else l
        when (tab) {
            1 -> k(cmPopM).take(15)
            2 -> k(cmPopS).take(15)
            else -> k(trendItems).take(16)
        }
    }
    // Movies/series: tabs at the top next to the search (instead of the big buttons under the picture)
    val isVod = tab == 1 || tab == 2
    val vodBase = if (tab == 1) movies else series
    val vodTop = if (tab == 1) topM else topS
    val vodNoun = if (tab == 1) "Movies" else "TV Shows"
    val vodK = if (tab == 1) Kind.MOVIE else Kind.SERIES
    val openCats: () -> Unit = {
        scope.launch {
            // Catalog genres (Cinemeta); own imported lists (Letterboxd, Trakt, MyAnimeList) come first
            val g = Catalog.genreMap(vodK)
            val imported = ImportedLists.groups(ctx, vodK, byId)
            onCategories(CatReq("$vodNoun · Categories", imported + g, imported + g, SORT_PLAYLIST))
        }
    }
    val openList: (Catalog.Spec, String, Int) -> Unit = { spec, title, pages ->
        scope.launch { onBrowse(BrowseReq(title, Catalog.items(spec, pages), SORT_PLAYLIST)) }
    }
    // One header button per imported service (Letterboxd, Trakt, MyAnimeList) that has titles in this area
    val importedTabs = if (!isVod) emptyList() else remember(tab, byId) {
        val g = ImportedLists.groups(ctx, if (tab == 1) Kind.MOVIE else Kind.SERIES, byId)
        ImportedLists.SOURCES.mapNotNull { src ->
            val l = g.filter { it.key.startsWith("$src · ") }.values.flatten().distinctBy { it.id }
            if (l.isEmpty()) null else Triple(src, l, "tb:imp$src$tab")
        }
    }
    // Asked by the View settings' live preview (consumed at once so a later visit does not open it again)
    LaunchedEffect(AppNav.openCatsSignal) { if (AppNav.openCatsSignal > 0) { AppNav.openCatsSignal = 0; if (tab == 1) openCats() } }
    LaunchedEffect(AppNav.openBrowseSignal) {
        if (AppNav.openBrowseSignal > 0) { AppNav.openBrowseSignal = 0; if (tab == 1) openList(Catalog.popular(vodK), "All $vodNoun", 6) }
    }
    // Asked by the View settings preview: bring the home rows into view (they are far down the list)
    LaunchedEffect(AppNav.homeScrollKey) {
        val want = AppNav.homeScrollKey
        if (want.isEmpty()) return@LaunchedEffect
        AppNav.homeScrollKey = ""
        if (tab != 0) return@LaunchedEffect
        val st = states[0]
        if (want == "top") { st.scrollToItem(0); return@LaunchedEffect }
        val keys = want.split("|")
        repeat(25) {
            val hit = st.layoutInfo.visibleItemsInfo.filter { it.key in keys }.minByOrNull { it.index }
            if (hit != null) { st.scrollToItem(hit.index); return@LaunchedEffect }
            if (!st.canScrollForward) return@LaunchedEffect
            st.animateScrollBy(700f)
        }
    }
    val navTabs = if (!isVod) emptyList() else listOf(
        HeaderTab("Kategorien", AIcon.Categories, "tb:cats$tab") { openCats() },
        HeaderTab("Top rated", AIcon.Star, "tb:top$tab") { openList(Catalog.featured(vodK), "Top rated · $vodNoun", 4) },
        HeaderTab("Recently added", AIcon.Sparkle, "tb:new$tab") { openList(Catalog.newest(vodK), "Recently added · $vodNoun", 4) },
        HeaderTab("All $vodNoun", AIcon.Grid, "tb:all$tab") { openList(Catalog.popular(vodK), "All $vodNoun", 6) }
    ) + importedTabs.map { (src, l, id) -> HeaderTab(src, AIcon.Download, id) { onBrowse(BrowseReq("$src · $vodNoun", l, SORT_NEW)) } }
    var inHeader by remember { mutableStateOf(false) }
    // Mac look: the big picture carries the area's links itself (next to Genres), so the header over it has no tabs
    val headerFor: @Composable (Modifier, List<HeaderTab>) -> Unit = { m, hTabs ->
        HeaderBar(
            m.onFocusChanged { inHeader = it.hasFocus },
            onSearch = { onTab(4) },
            onReminder = { r -> byId[r.chId]?.let(onOpen) },
            onNewEpisode = { id -> byId[id]?.let(onOpen) },
            // Only one profile: go directly to the profile page of settings (before: general settings)
            onProfile = { profileMenu = true },
            tabs = hTabs,
            areaTab = tab,
            // Round 20: the View button is gone (now in Settings > View), only bell and profile at the top right
            showView = false,
            // Without a big picture (no title images yet): the first tab is the target from the sidebar
            firstIsContent = isVod && ready.isEmpty()
        )
    }
    val header: @Composable (Modifier) -> Unit = { m -> headerFor(m, emptyList()) }
    val headerTabs: @Composable (Modifier) -> Unit = { m -> headerFor(m, navTabs) }
    // Tiles with real images from the own library (round 15): scene image of a top title per area,
    // for favorites and My list a title from it, for Live TV the logos of favorite channels.
    val nowChannels = remember(favLive, hist, live) {
        (favLive + hist.filter { it.kind == Kind.LIVE }).distinctBy { it.id }.ifEmpty { live.take(20) }
    }
    val favVod by remember(byId) { derivedStateOf { Library.favs.mapNotNull { lk(it) }.filter { it.kind != Kind.LIVE } } }
    val tileImg by produceState(emptyMap<Int, String>(), topM.size, topS.size, favVod.size, watchList.size) {
        val used = HashSet<String>()
        val out = HashMap<Int, String>()
        suspend fun pick(id: Int, list: List<Item>) {
            for (c in list.take(14)) {
                val u = MetaRepo.basic(ctx, c)?.backdrop
                if (u != null && used.add(u)) { out[id] = u; value = HashMap(out); return }
            }
        }
        pick(1, topM); pick(2, topS)
        pick(MenuLogic.FAVS, favVod.ifEmpty { topM.drop(3) })
        pick(MenuLogic.MYLIST, watchList.filter { it.kind != Kind.LIVE }.ifEmpty { topS.drop(3) })
    }
    val liveLogos = nowChannels.mapNotNull { it.logo }.take(3)
    val tiles = listOf(
        QuickTile("Live TV", "${live.size} channels", 3, Color(0xFF6B3A0E), Color(0xFF2B1708), Color(0xFFF59E0B), logos = liveLogos) { onTab(3) },
        QuickTile("Filme", "${movies.size} movies", 1, Color(0xFF1E3A8A), Color(0xFF0F1B45), Color(0xFF60A5FA), image = tileImg[1]) { onTab(1) },
        QuickTile("TV Shows", "${series.size} TV shows", 2, Color(0xFF4C1D95), Color(0xFF22104A), Color(0xFFC084FC), image = tileImg[2]) { onTab(2) },
        QuickTile("Favoriten", "Your favorites", 7, Color(0xFF7F1D1D), Color(0xFF3A0B0B), Color(0xFFFB7185), image = tileImg[MenuLogic.FAVS]) { onTab(MenuLogic.FAVS) },
        QuickTile("Meine Liste", "${watchList.count { it.kind != Kind.LIVE }} titles", 8, Color(0xFF134E4A), Color(0xFF082725), Color(0xFF2DD4BF), image = tileImg[MenuLogic.MYLIST]) { onTab(MenuLogic.MYLIST) }
    )

    LaunchedEffect(candidates) {
        // Switching between Home / Movies / Series: the big picture of this area is taken over at once instead of
        // vanishing and being looked up title by title again
        val ids = candidates.map { it.id }
        val hit = HeroCache.byTab[tab]
        if (hit != null && hit.first == ids) {
            if (ready.toList() != hit.second) Snapshot.withMutableSnapshot {
                ready.clear(); ready.addAll(hit.second)
            }
            return@LaunchedEffect
        }
        // Metadata retrieval may read the disk or fetch artwork. Keeping it off
        // the UI thread avoids pauses during remote navigation, and replacing
        // the candidates in one snapshot avoids a blank hero between cards.
        val next = withContext(Dispatchers.IO) {
            val found = ArrayList<Item>(6)
            for (c in candidates) {
                val m = Catalog.meta(c) ?: MetaRepo.basic(ctx, c)
                if (m?.backdrop != null) found.add(c)
                if (found.size >= 6) break
            }
            found.toList()
        }
        if (next != ready.toList()) Snapshot.withMutableSnapshot {
            ready.clear()
            ready.addAll(next)
        }
        HeroCache.byTab[tab] = ids to next
    }
    // Switched here from another screen via the sidebar (e.g. search): focus the content
    LaunchedEffect(Unit) {
        if (AppNav.focusTab == tab && LastFocus.pending == null) {
            AppNav.focusTab = -1
            repeat(10) {
                delay(150)
                if (ContentFocus.req.tryFocus()) return@LaunchedEffect
            }
        }
    }
    // Returning from playback preserves the row's LazyListState and lets the
    // exact card's restoreFocus() request focus. Do not clear that request
    // after a fixed delay or jump focus back to the hero.
    var inSidebar by remember { mutableStateOf(false) }
    val sideFocus = remember { FocusRequester() }
    // App start: focus on "Watch now" instead of the sidebar. The top picture appears only once playlist and info are loaded
    // (can take a few seconds); so it retries for up to 15 s, and the marker is set only on success.
    // After 3 s only while focus is still in the sidebar (if the user has already chosen, nothing is stolen).
    LaunchedEffect(ready.isNotEmpty(), tab) {
        if (tab != 0 || HomeFlags.heroDone || LastFocus.pending != null) return@LaunchedEffect
        var waited = 0
        while (waited < 45000) {
            delay(150); waited += 150
            if (ready.isEmpty() && waited < 6000) continue
            if (waited > 3000 && !inSidebar) return@LaunchedEffect
            if (runCatching { ContentFocus.req.requestFocus() }.getOrDefault(false)) { HomeFlags.heroDone = true; return@LaunchedEffect }
        }
    }
    // Quit: back twice within 2.5 s. Independent of where the focus currently is (otherwise the second
    // back led to the home screen again instead of quitting).
    var lastBack by remember { mutableLongStateOf(0L) }
    fun exitApp() {
        val act = generateSequence<android.content.Context>(ctx) { (it as? android.content.ContextWrapper)?.baseContext }
            .filterIsInstance<android.app.Activity>().firstOrNull()
        act?.finishAffinity()
    }
    BackHandler(enabled = !inSidebar && all.isNotEmpty()) {
        val now = System.currentTimeMillis()
        if (tab == 0 && General.confirmExit && now - lastBack < 2500) exitApp()
        // Round 17: Back goes up exactly one level – further down the home screen first up to "Watch now",
        // only from there into the sidebar (like in Movies/Series)
        else if (tab == 0 && states[0].firstVisibleItemIndex > 0) scope.launch {
            runCatching { states[0].scrollToItem(0) }
            repeat(8) {
                delay(60)
                if (runCatching { ContentFocus.req.requestFocus() }.getOrDefault(false)) return@launch
            }
            runCatching { sideFocus.requestFocus() }
        }
        else runCatching { sideFocus.requestFocus() }
    }
    // Movies/series: Back from the rows leads first to the tabs at the top, from there into the sidebar
    BackHandler(enabled = !inSidebar && !inHeader && isVod && all.isNotEmpty()) {
        scope.launch {
            runCatching { states[tab].scrollToItem(0) }
            repeat(8) {
                delay(60)
                if (runCatching { HeaderFocus.firstTab.requestFocus() }.getOrDefault(false)) return@launch
            }
            runCatching { sideFocus.requestFocus() }
        }
    }
    BackHandler(enabled = inSidebar && tab != 0) { onTab(0) }
    BackHandler(enabled = inSidebar && tab == 0 && General.confirmExit) {
        val now = System.currentTimeMillis()
        if (now - lastBack < 2500) exitApp()
        else { lastBack = now; android.widget.Toast.makeText(ctx, Lang.tr("Press Back again to exit"), android.widget.Toast.LENGTH_SHORT).show() }
    }

    // Taste setup of this profile: liked titles feed "Because you like …", liked genres come first
    val tasteSeeds = remember(Profiles.active) { Taste.titles(ctx).mapNotNull { CatalogItems.get(it) }.shuffled() }
    val likedGenres = remember(Profiles.active) { Taste.genres(ctx) }
    val tasteTop = remember(Profiles.active) { Taste.topGenres(ctx) }
    val tastePeople = remember(Profiles.active) { Taste.people(ctx) }
    fun tasteOrder(l: List<Catalog.Spec>) = l.sortedBy { sp -> likedGenres.indexOfFirst { it.equals(sp.title, true) }.let { if (it < 0) 99 else it } }
    // A title watched on another TV (or from another source) stays in Continue watching even when this TV's playlist has
    // it under another id or not at all: exact title + kind match first, else the saved entry itself (ResumeMatch)
    // Top picture's main button: resume a started title at its saved spot, otherwise play it
    val heroPlay: (Item) -> Unit = { it -> WatchStore.entries.firstOrNull { e -> e.itemId == it.id && !e.next && e.pos > 60_000 }?.let { e -> onResume(e, it) } ?: onPlayNow(it) }
    val resumeList by remember(byId) { derivedStateOf { WatchStore.entries.map { e -> e to (lk(e.itemId) ?: CatalogItems.get(e.itemId) ?: ResumeMatch.find(e, byId)) } } }
    // Movies/series tab: the same lists filtered by kind, kept between recompositions
    val vodKind = if (tab == 1) Kind.MOVIE else Kind.SERIES
    val resumeK = remember(resumeList, vodKind) { resumeList.filter { it.second.kind == vodKind } }
    val watchK = remember(watchList, vodKind) { watchList.filter { it.kind == vodKind } }
    val histK = remember(hist, vodKind) { hist.filter { it.kind == vodKind } }
    val trendK = remember(trendItems, vodKind) { trendItems.filter { it.kind == vodKind } }
    val reload: () -> Unit = { PlaylistStore.savedUrl(ctx)?.let { u -> scope.launch { PlaylistStore.load(ctx, u, false) } } }

    // Round 17b (Cinematic Remake): background and info follow the selected title once the big picture has scrolled away
    val bbTab = tab in 0..2
    val bbState = states[tab.coerceIn(0, states.lastIndex)]
    // Round 18: even when the big picture is still partly visible (e.g. first row "Popular series"), the background shows
    // the info of the selected title – and the big picture (slideshow) pauses.
    // Info at the top only for the normal title rows (from "Top 10 today" or "Newly added"), not for tiles, Live TV, Continue watching,
    // My list and Recently watched. And only if the row is low enough not to cover the info.
    val listTop = remember { floatArrayOf(0f) }
    // Nuvio look: Continue Watching shows its title at the top too
    val noBb = remember(FofPrefs.cleanLook) { setOf("hero", "top", "tiles", "chnow", "hero1", "hero2", "top1", "top2", "wl1", "wl2", "hr1", "hr2", "hint1", "hint2") + (if (FofPrefs.cleanLook) emptySet() else setOf("resume", "res1", "res2")) }
    val bbOnState = remember(tab) {
        derivedStateOf {
            if (!bbTab || !Billboard.cardFocus || Billboard.focused == null) return@derivedStateOf false
            val co = Billboard.focusCo
            if (co == null || !co.isAttached) return@derivedStateOf false
            val li = bbState.layoutInfo
            val y = co.positionInRoot().y - listTop[0]
            val row = li.visibleItemsInfo.lastOrNull { it.offset <= y } ?: return@derivedStateOf false
            (row.key as? String) !in noBb && row.index > 0 &&
                row.offset >= (li.viewportEndOffset - li.viewportStartOffset) * 0.33f
        }
    }
    // Read only in effects and in BillboardLayer: reading it here would recompose the whole home screen
    LaunchedEffect(bbOnState) { snapshotFlow { bbOnState.value }.collect { Billboard.active = it } }
    LaunchedEffect(inSidebar) { if (inSidebar) Billboard.cardFocus = false }
    LaunchedEffect(tab) { Billboard.cardFocus = false }
    val bbItemState = remember { mutableStateOf<Item?>(Billboard.focused) }
    // 17c: switch only after a short dwell (no big images are loaded while browsing quickly)
    // snapshotFlow instead of LaunchedEffect(Billboard.focused): reading the focused title here recomposed the whole home screen
    // (all visible rows and cards) on every move of the highlight
    LaunchedEffect(Unit) {
        snapshotFlow { Billboard.focused }.collectLatest { f -> if (bbItemState.value != null) delay(450); bbItemState.value = f }
    }
    val bbAnim = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(bbOnState) {
        snapshotFlow { bbOnState.value && bbItemState.value != null }.collectLatest { on ->
            val t = if (on) 1f else 0f
            if (Look.motion == 0) bbAnim.snapTo(t) else bbAnim.animateTo(t, tween(520))
        }
    }
    // Read only in draw / layer lambdas and in BillboardLayer (no recomposition per animation frame)
    val bbA: () -> Float = remember(bbAnim) { { bbAnim.value } }
    // Clean look: the selected row settles in the upper third (like Nuvio); billboard mode keeps it in the middle
    val pivotSpec = remember(tab, FofPrefs.cleanLook) { PivotSpec(if (FofPrefs.cleanLook) 0.45f else 0.5f) { states[tab.coerceIn(0, states.lastIndex)] } }
    Box(Modifier.fillMaxSize()) {
        if (bbTab) BillboardLayer(bbA, bbItemState, Modifier.padding(start = SideStart + dpx(56), top = dpx(64) + (if (SidebarPrefs.pos == SidebarPrefs.TOP) TopBarH else 0.dp)))
        // Keep watching: something played in the last hour (here, on the Mac or another TV) – one press resumes it
        if (tab == 0) {
            val kw = remember(resumeList) {
                resumeList.firstOrNull()?.takeIf { (e, _) -> !e.next && e.pos > 60_000 && e.dur > e.pos + 120_000 && System.currentTimeMillis() - e.ts < 60 * 60_000L }
            }
            if (kw != null && !KeepWatching.shown) KeepWatchingCard(kw.first, kw.second, Modifier.zIndex(2f).align(Alignment.BottomEnd)) { onResume(kw.first, kw.second) }
        }
        BoxWithConstraints(Modifier.fillMaxSize().sidebarPad()) {
            // Card widths and height of the big picture from the available space (as in the design: everything at a glance)
            val grid = remember(maxWidth, Design.k, General.cardSize) { cardGrid(maxWidth - RowPadX * 2) }
            // Design: big picture 420 px high (start), 345 px (movies/series)
            val heroH = dpx(if (tab == 0) 540 else 450)
            // Settings always opens, also without a playlist (updates, account, playlist setup)
            val noPlaylistScreen = tab != MenuLogic.SETTINGS
            when {
                // Download/parse without a shown list: loading screen with progress (and possibly language selection)
                noPlaylistScreen && PlaylistStore.loading && all.isEmpty() && PlaylistStore.heavy -> PlaylistLoadingOverlay()
                noPlaylistScreen && ((PlaylistStore.loading && all.isEmpty()) || computing) -> SkeletonHome()
                noPlaylistScreen && all.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        BrandMark(Modifier.size(72.dp))
                        val err = PlaylistStore.error
                        // Everything hidden (language filter does not fit this playlist): be able to get it back directly
                        val allHidden = err == null && PlaylistStore.langCounts.isNotEmpty() && PlaylistStore.langCounts.keys.all { it in LangFilter.hidden }
                        Text(
                            when { err != null -> "The playlist could not be loaded"; allHidden -> "All languages in this playlist are hidden"; else -> "No channels yet" },
                            color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold
                        )
                        if (err != null) {
                            Text(err, color = Muted, fontSize = 16.sp)
                            Text("Check your internet connection, playlist URL, and login details.", color = Muted, fontSize = 16.sp)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (allHidden) PillButton("Show all languages", light = true) { LangFilter.setAndApply(ctx, emptySet()) }
                            if (err != null) PillButton("Try again", light = true) { reload() }
                            PillButton("Add playlist", light = err == null && !allHidden) { onAdd() }
                        }
                    }
                }
                tab == 3 -> LiveTab(live, liveP, favLive, onOpen, onPlayNow)
                tab == MenuLogic.SPORT -> SportHub(live) { ch, l -> ZapContext.list = l; ZapContext.key = null; onOpen(ch) }
                // The selected row stays in the lower half (PivotSpec); rows above are hidden so that at the top
                // title and info of the selected title are free (billboard)
                else -> CompositionLocalProvider(
                    LocalCardGrid provides grid, LocalScrollTop provides { states[tab].scrollToItem(0) },
                    androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides (if (bbTab) pivotSpec else SmoothSpec)
                ) { LazyColumn(Modifier.fillMaxSize().onGloballyPositioned { listTop[0] = it.positionInRoot().y }.drawWithContent {
                    // While the info is at the top (also while fading out): everything above the selected row is gone, the big picture always.
                    val ct = if (bbTab && bbA() > 0.01f) {
                        val li = states[tab].layoutInfo
                        val co = Billboard.focusCo
                        var edge = size.height * 0.5f - dpx(140).toPx()
                        if (Billboard.focused != null && co != null && co.isAttached) {
                            val y = co.positionInRoot().y - listTop[0]
                            val row = li.visibleItemsInfo.lastOrNull { it.offset <= y }
                            if (row != null && row.index > 0) edge = row.offset.toFloat()
                        }
                        li.visibleItemsInfo.firstOrNull { it.index == 0 }?.let { edge = maxOf(edge, (it.offset + it.size).toFloat()) }
                        edge.coerceIn(0f, size.height * 0.75f)
                    } else 0f
                    if (ct > 1f) clipRect(top = ct) { this@drawWithContent.drawContent() } else drawContent()
                }, state = states[tab], contentPadding = PaddingValues(bottom = dpx(420))) {
                    when (tab) {
                        0 -> {
                            // Order and selection of the blocks: Settings > Customize home (HomeLayout)
                            if (HomeEdit.active) item(key = "editTop") {
                                Row(Modifier.padding(start = RowPadX + dpx(2), top = dpx(12)), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    PillButton("Fertig", light = true, icon = AIcon.Check) { HomeEdit.active = false }
                                    Text("Bearbeiten: Über jeder Reihe kannst du sie verschieben oder ausblenden.", color = Muted, fontSize = 15.sp)
                                }
                            }
                            if (ready.isNotEmpty() && HomeLayout.shown("hero") && !FofPrefs.cleanLook) item(key = "hero") { Hero(ready.toList(), heroH, true, onOpen, heroPlay, header, { heroScroll(states[0]) }) { scope.launch { states[0].animateScrollToItem(0) } } }
                            else {
                                item(key = "top") { header(Modifier) }
                                // Nuvio look: room at the top for the selected title (info and picture), rows start below it
                                if (FofPrefs.cleanLook) item(key = "nvspace") { Spacer(Modifier.height(dpx(300))) }
                            }
                            val wl = wlVod
                            for (blockId in HomeLayout.order) {
                                if (blockId == "hero") continue
                                val editing = HomeEdit.active
                                if (editing) item(key = "eb$blockId") { HomeEditBar(ctx, blockId) }
                                if (!HomeLayout.shown(blockId)) continue
                                when (blockId) {
                                    "tiles" -> { if (!FofPrefs.cleanLook) item(key = "tiles") { QuickTiles(tiles) } }
                                    "stremio" -> {
                                        // Kids profile: rows made for kids first (the other rows keep only their family titles)
                                        if (KidsMode.on) listOf(
                                            Catalog.Spec("Animated Movies", "movie", "top", "Animation"), Catalog.Spec("Cartoons & Animated Shows", "series", "top", "Animation"),
                                            Catalog.Spec("Family Movie Night", "movie", "top", "Family"), Catalog.Spec("Family Shows", "series", "top", "Family")
                                        ).forEach { ks -> item(key = "kids" + ks.type + ks.genre) { CatalogRow(ks, cardW, onOpen, onBrowse) } }
                                        val topIds = trendTop.mapTo(HashSet()) { it.id }
                                        // "Because you watched …": the last catalog title you watched
                                        val seed = History.ids.firstNotNullOfOrNull { id -> CatalogItems.get(id) }
                                        if (seed != null) item(key = "because" + seed.id) {
                                            ExtraRow("Because you watched " + seed.title, "because:" + seed.id.removePrefix("stremio:"), cardW, onOpen, onBrowse, style = CARD_SPOT) { TmdbExtra.because(seed) }
                                        }
                                        // Picked in the taste setup: up to two "Because you like …" rows
                                        tasteSeeds.filter { it.id != seed?.id }.take(2).forEach { ts ->
                                            item(key = "like" + ts.id) {
                                                ExtraRow("Because you like " + ts.title, "because:" + ts.id.removePrefix("stremio:"), cardW, onOpen, onBrowse) { TmdbExtra.because(ts) }
                                            }
                                        }
                                        // From this profile's own setup answers (nothing for a profile that skipped it)
                                        if (tasteTop.isNotEmpty()) {
                                            item(key = "taste:picked") { ExtraRow("We think you'll love this", "taste:picked:" + Profiles.active, cardW, onOpen, onBrowse, style = CARD_SPOT) { TasteRows.picked(ctx, tasteTop) } }
                                            item(key = "taste:movies") { ExtraRow("Movies We Think You'll Love", "taste:movies:" + Profiles.active, cardW, onOpen, onBrowse) { TasteRows.forKind(ctx, Kind.MOVIE, tasteTop) } }
                                            item(key = "taste:shows") { ExtraRow("TV Shows Picked for You", "taste:shows:" + Profiles.active, cardW, onOpen, onBrowse) { TasteRows.forKind(ctx, Kind.SERIES, tasteTop) } }
                                        }
                                        // Actors and creators picked in the setup: what they are known for (TMDB)
                                        tastePeople.take(2).forEach { (pid, pn) ->
                                            item(key = "person:$pid") { ExtraRow("Because you love $pn", "person:$pid", cardW, onOpen, onBrowse) { TmdbExtra.personTitles(pid) } }
                                        }
                                        Catalog.home().forEach { sp -> item(key = "cm" + sp.title) { CatalogRow(sp, cardW, onOpen, onBrowse, topIds) } }
                                        if (tasteTop.isNotEmpty()) item(key = "taste:new") { ExtraRow("Something New for You", "taste:new:" + Profiles.active, cardW, onOpen, onBrowse) { TasteRows.discover(ctx, tasteTop) } }
                                        // Curated discovery rows
                                        item(key = "cur:acclaimed") { CatalogRow(Catalog.Spec("Critically Acclaimed Movies", "movie", "imdbRating"), cardW, onOpen, onBrowse, topIds) }
                                        // Hidden gems: highly rated titles beyond the most famous ones (the top of the rating list and today's popular titles are left out)
                                        item(key = "cur:gems") { ExtraRow("Hidden Gems for You", "cur:gems", cardW, onOpen, onBrowse) {
                                            val famous = (Catalog.cached(Catalog.popular(Kind.MOVIE)) + Catalog.cached(Catalog.popular(Kind.SERIES))).map { it.id }.toSet()
                                            val m = Catalog.items(Catalog.featured(Kind.MOVIE), 4).drop(40); val s = Catalog.items(Catalog.featured(Kind.SERIES), 4).drop(40)
                                            (0 until maxOf(m.size, s.size)).flatMap { listOfNotNull(m.getOrNull(it), s.getOrNull(it)) }.filter { it.id !in famous }.distinctBy { it.id }.take(30) } }
                                        item(key = "cur:binge") { CatalogRow(Catalog.Spec("Binge-worthy TV Shows", "series", "imdbRating"), cardW, onOpen, onBrowse, topIds) }
                                        item(key = "cur:short") { ExtraRow("Quick Watches: Under 100 Minutes", "cur:short", cardW, onOpen, onBrowse) {
                                            Catalog.items(Catalog.popular(Kind.MOVIE), 3).filter { Catalog.meta(it)?.runtime?.let { r -> r in 60..100 } == true } } }
                                        item(key = "cur:classic") { val y = remember { listOf(1975, 1985, 1990, 1995, 1999).random() }
                                            CatalogRow(Catalog.Spec("Timeless Classics", "movie", "year", y.toString()), cardW, onOpen, onBrowse, topIds) }
                                        // What just came out on the big streaming services
                                        TmdbExtra.SERVICES.take(4).forEach { sv ->
                                            item(key = "new" + sv.id) { ExtraRow("New on " + sv.name, "new:" + sv.id, cardW, onOpen, onBrowse) { TmdbExtra.newOn(sv) } }
                                        }
                                        item(key = "collections") { CollectionsRow(onBrowse) }
                                    }
                                    "sportlive" -> { item(key = "sportlive") { LiveSportsRow(live, onOpen) { onTab(MenuLogic.SPORT) } } }
                                    "clock" -> { item(key = "clock") { ClockWidget() } }
                                    // Continue watching directly under the tiles: whoever watches a lot wants to continue in few clicks
                                    "resume" -> { if (resumeList.isNotEmpty()) item(key = "resume") { ResumeRow(resumeList, cardW, onResume, onOpen) } }
                                    "favch" -> { if (favLive.isNotEmpty()) item(key = "favch") { ItemsRow("My Channels", favLive, cardW, onOpen) { onTab(3) } } }
                                    "chnow" -> { if (nowChannels.isNotEmpty()) item(key = "chnow") { ChannelNowRow("Live TV", nowChannels, onOpen) { onTab(3) } } }
                                    // Top 10 always as whole covers (independent of the picture setting)
                                    "trend" -> { if (trendItems.size >= 3) item(key = "trend") {
                                        ItemsRow("Top 10 Movies & Shows Today", trendTop, cardW, onOpen, style = CARD_TOP10) { onBrowse(BrowseReq("Im Trend", trendItems, SORT_PLAYLIST)) }
                                    } }
                                    "because" -> { /* Fof TV: based on the provider's titles, not shown */ }
                                    "wl" -> { if (wl.isNotEmpty()) item(key = "wl") { ItemsRow("Meine Liste", wl, cardW, onOpen) { onTab(MenuLogic.MYLIST) } } }
                                    // Shows you follow that got new episodes this week (newest first)
                                    "newep" -> {
                                        val ne = NewEpisodes.list.sortedByDescending { it.added }.mapNotNull { n -> lk(n.id) ?: CatalogItems.get(n.id) }.distinctBy { it.id }
                                        if (ne.isNotEmpty()) item(key = "newep") { ItemsRow("New Episodes This Week", ne, cardW, onOpen) { onTab(2) } }
                                    }
                                    "hist" -> { if (hist.isNotEmpty()) item(key = "hist") { ItemsRow("Zuletzt geschaut", histTop, cardW, onOpen) { onBrowse(BrowseReq("Zuletzt geschaut", hist, SORT_PLAYLIST)) } } }
                                    "newm" -> { if (movies.isNotEmpty()) item(key = "newm") {
                                        ItemsRow("Recently Added · Movies", newMovies, cardW, onOpen) { onBrowse(BrowseReq("Recently Added · Movies", movies, SORT_NEW)) }
                                    } }
                                    "topm" -> { if (topM.size >= 8) item(key = "topm") {
                                        ItemsRow("Top bewertet · Filme", topM, cardW, onOpen) { onBrowse(BrowseReq("Top bewertet · Filme", topM, SORT_RATING)) }
                                    } }
                                    "news" -> { if (series.isNotEmpty()) item(key = "news") {
                                        ItemsRow("Recently Added · TV Shows", newSeries, cardW, onOpen) { onBrowse(BrowseReq("Recently Added · TV Shows", series, SORT_NEW)) }
                                    } }
                                    "tops" -> { if (topS.size >= 8) item(key = "tops") {
                                        ItemsRow("Top Rated · TV Shows", topS, cardW, onOpen) { onBrowse(BrowseReq("Top Rated · TV Shows", topS, SORT_RATING)) }
                                    } }
                                    "sg" -> tasteOrder(Catalog.genres(Kind.SERIES)).take(4).forEach { sp -> item(key = "cms" + sp.title) { CatalogRow(sp.copy(title = "Series · " + sp.title), cardW, onOpen, onBrowse) } }
                                    "mg" -> tasteOrder(Catalog.genres(Kind.MOVIE)).take(4).forEach { sp -> item(key = "cmm" + sp.title) { CatalogRow(sp.copy(title = "Movies · " + sp.title), cardW, onOpen, onBrowse) } }
                                }
                            }
                            // Build own home screen: entry at the end of the page
                            item(key = "editHome") {
                                Row(Modifier.padding(start = RowPadX + dpx(2), top = dpx(24))) {
                                    SmallPill("Customize Home", icon = AIcon.Sliders) { HomeEdit.active = true; scope.launch { states[0].animateScrollToItem(0) } }
                                }
                            }
                            if (movies.isEmpty() && series.isEmpty()) item { EmptyHint("This playlist has no movies or TV shows. Live TV is available on the left.") }
                        }
                        1, 2 -> {
                            val isM = tab == 1
                            val base = if (isM) movies else series
                            val top = if (isM) topM else topS
                            val noun = if (isM) "Filme" else "TV Shows"
                            val kind = if (isM) Kind.MOVIE else Kind.SERIES
                            val gSpecs = Catalog.genres(kind)
                            val catName = if (isM) "Movies" else "TV Shows"
                            val pickGenre: (Int) -> Unit = { i -> gSpecs.getOrNull(i)?.let { sp -> scope.launch { onBrowse(BrowseReq(catName + " · " + sp.title, Catalog.items(sp, 4), SORT_PLAYLIST)) } } }
                            if (ready.isNotEmpty()) item(key = "hero$tab") { Hero(ready.toList(), heroH, false, onOpen, heroPlay, header, { heroScroll(states[tab]) },
                                category = catName, genres = gSpecs.map { it.title }, onGenre = pickGenre, links = navTabs) { scope.launch { states[tab].animateScrollToItem(0) } } }
                            else item(key = "top$tab") { headerTabs(Modifier) }
                            // Netflix category order: Continue Watching, Top 10 of this kind, My List, a spotlight row, then genres
                            val rl = resumeK
                            if (rl.isNotEmpty()) item(key = "res$tab") { ResumeRow(rl, cardW, onResume, onOpen) }
                            val t10 = trendK.take(10)
                            if (t10.size >= 3) item(key = "t10$tab") { ItemsRow("Top 10 $catName Today", t10, cardW, onOpen, style = CARD_TOP10) { onBrowse(BrowseReq("Top 10 $catName Today", trendK, SORT_PLAYLIST)) } }
                            val wl = watchK
                            if (wl.isNotEmpty()) item(key = "wl$tab") { ItemsRow("Meine Liste", wl, cardW, onOpen) { onBrowse(BrowseReq("Meine Liste · $noun", wl, SORT_PLAYLIST)) } }
                            if (tasteTop.isNotEmpty()) item(key = "spot$tab") {
                                ExtraRow(if (isM) "Movies We Think You'll Love" else "TV Shows Picked for You", "taste:" + (if (isM) "movies:" else "shows:") + Profiles.active, cardW, onOpen, onBrowse, style = CARD_SPOT) { TasteRows.forKind(ctx, kind, tasteTop) }
                            }
                            val recent = histK
                            if (recent.isNotEmpty()) item(key = "hr$tab") { ItemsRow("Zuletzt geschaut", recent, cardW, onOpen) { onBrowse(BrowseReq("Zuletzt geschaut · $noun", recent, SORT_PLAYLIST)) } }
                            // Fof TV: the Stremio addons are the catalog here, not the provider's categories
                            Catalog.page(kind).forEach { sp -> item(key = "cp$tab" + sp.title) { CatalogRow(sp, cardW, onOpen, onBrowse) } }
                        }
                        MenuLogic.FAVS -> {
                            // Round 14: besides channels also movies and series (detail page > "Add to favorites")
                            val favM = Library.favs.mapNotNull { lk(it) }.filter { it.kind == Kind.MOVIE }
                            val favS = Library.favs.mapNotNull { lk(it) }.filter { it.kind == Kind.SERIES }
                            item(key = "favtop") { header(Modifier) }
                            if (favLive.isEmpty() && favM.isEmpty() && favS.isEmpty()) {
                                item(key = "favh") { RowTitle("Favoriten", null, null, onAll = null) }
                                item(key = "fave") {
                                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                        EmptyState(AIcon.Heart, "No favorites yet", "Hold OK on a channel, or use Add to Favorites on a movie or TV show detail page.",
                                            action = "Zu Live TV", actionModifier = Modifier.focusRequester(ContentFocus.req)) { onTab(3) }
                                    }
                                }
                            }
                            if (favLive.isNotEmpty()) {
                                item(key = "favh") { RowTitle("Favorites · Channels", null, null, onAll = null) }
                                item(key = "favg") { ChannelDetailRows(favLive, ContentFocus.req, onOpen) }
                            }
                            if (favM.isNotEmpty()) {
                                item(key = "favmh") { RowTitle("Favoriten · Filme", null, null, onAll = null) }
                                item(key = "favm") { CardGridRows(favM, if (General.cardArt == 1) CARD_LAND else CARD_POSTER, if (favLive.isEmpty()) ContentFocus.req else null, onOpen) }
                            }
                            if (favS.isNotEmpty()) {
                                item(key = "favsh") { RowTitle("Favorites · TV Shows", null, null, onAll = null) }
                                item(key = "favs") { CardGridRows(favS, if (General.cardArt == 1) CARD_LAND else CARD_POSTER, if (favLive.isEmpty() && favM.isEmpty()) ContentFocus.req else null, onOpen) }
                            }
                            val favCats = CatFavs.list.filter { it in liveP }
                            if (favCats.isNotEmpty()) favCats.forEach { g ->
                                item(key = "fc$g") { ItemsRow(g, liveP[g].orEmpty(), cardW, onOpen) { onTab(3) } }
                            }
                        }
                        MenuLogic.MYLIST -> {
                            item(key = "mltop") { header(Modifier) }
                            val wm = watchList.filter { it.kind == Kind.MOVIE }
                            val ws = watchList.filter { it.kind == Kind.SERIES }
                            if (wm.isEmpty() && ws.isEmpty()) {
                                item(key = "mlh") { RowTitle("Meine Liste", null, null, onAll = null) }
                                item(key = "mle") {
                                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                        EmptyState(AIcon.Plus, "Your list is empty", "Choose My List on a movie or TV show page.",
                                            action = "Filme entdecken", actionModifier = Modifier.focusRequester(ContentFocus.req)) { onTab(1) }
                                    }
                                }
                            }
                            if (wm.isNotEmpty()) {
                                item(key = "mlmh") { RowTitle("Meine Liste · Filme", null, null, onAll = null) }
                                item(key = "mlm") { CardGridRows(wm, CARD_POSTER, ContentFocus.req, onOpen) }
                            }
                            if (ws.isNotEmpty()) {
                                item(key = "mlsh") { RowTitle("My List · TV Shows", null, null, onAll = null) }
                                item(key = "mls") { CardGridRows(ws, CARD_POSTER, if (wm.isEmpty()) ContentFocus.req else null, onOpen) }
                            }
                        }
                        MenuLogic.VERLAUF -> {
                            // Recently watched; for series only the series title (the history stores one line per series)
                            item(key = "vltop") { header(Modifier) }
                            val vm = hist.filter { it.kind == Kind.MOVIE }
                            val vs = hist.filter { it.kind == Kind.SERIES }
                            if (vm.isEmpty() && vs.isEmpty()) {
                                item(key = "vlh") { RowTitle("Verlauf", null, null, onAll = null) }
                                item(key = "vle") {
                                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                        EmptyState(AIcon.History, "Nothing watched yet", "Movies and shows you start will appear here.",
                                            action = "Filme entdecken", actionModifier = Modifier.focusRequester(ContentFocus.req)) { onTab(1) }
                                    }
                                }
                            }
                            if (vm.isNotEmpty()) {
                                item(key = "vlmh") { RowTitle("Verlauf · Filme", null, null, onAll = null) }
                                item(key = "vlm") { CardGridRows(vm, CARD_POSTER, ContentFocus.req, onOpen) }
                            }
                            if (vs.isNotEmpty()) {
                                item(key = "vlsh") { RowTitle("History · TV Shows", null, null, onAll = null) }
                                item(key = "vls") { CardGridRows(vs, CARD_POSTER, if (vm.isEmpty()) ContentFocus.req else null, onOpen) }
                            }
                        }
                        else -> {
                            item(key = "settop") { header(Modifier) }
                            item { SettingsHub(onAdd, reload) }
                        }
                    }
                } }
            }
            // Design settings: the preview stays fixed at the top right, the settings scroll away below
            if (DesignPreview.visible) DesignPreviewPanel(Modifier.align(Alignment.TopEnd).padding(top = dpx(84), end = DS.PageX))
        }
        // Profile circle: who is watching, and the ways to switch or manage (Netflix style)
        if (profileMenu) {
            BackHandler { profileMenu = false }
            Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable { profileMenu = false }, contentAlignment = Alignment.TopEnd) {
                Column(
                    Modifier.padding(top = dpx(84), end = DS.PageX).width(dpx(380)).glass(RoundedCornerShape(DS.RadiusL), 2)
                        .clickable(enabled = false) {}.padding(dpx(14)),
                    verticalArrangement = Arrangement.spacedBy(dpx(4))
                ) {
                    val me = Profiles.list.firstOrNull { it.id == Profiles.active }?.let { Profiles.displayName(it) }.orEmpty()
                    Row(Modifier.padding(dpx(10)), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(14))) {
                        ActiveAvatar(dpx(52))
                        Column {
                            Text("Watching as", color = Muted, fontSize = spx(14), raw = true)
                            Text(me, color = Color.White, fontSize = spx(22), fontWeight = FontWeight.Bold, raw = true, maxLines = 1)
                            if (FofAccount.signedInState) Text(FofAccount.email.orEmpty(), color = Muted, fontSize = spx(13), raw = true, maxLines = 1)
                        }
                    }
                    val first = remember { FocusRequester() }
                    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
                    QuickRow(QuickMenu.Action("Switch profile", AIcon.Person) { profileMenu = false; picker = true }, Modifier.focusRequester(first)) { profileMenu = false; picker = true }
                    QuickRow(QuickMenu.Action("Manage profiles", AIcon.Edit) { }, Modifier) { profileMenu = false; picker = true }
                    QuickRow(QuickMenu.Action("Profile settings", AIcon.Person) { }, Modifier) { profileMenu = false; SettingsNav.openPage = SettingsNav.PROFILES; onTab(MenuLogic.SETTINGS) }
                    QuickRow(QuickMenu.Action("Account & devices", AIcon.Gear) { }, Modifier) { profileMenu = false; SettingsNav.openPage = SettingsNav.ACCOUNT; onTab(MenuLogic.SETTINGS) }
                    QuickRow(QuickMenu.Action("Surprise me", AIcon.Sparkle) { }, Modifier) { profileMenu = false; surprise = true }
                    if (FofAccount.signedInState) QuickRow(QuickMenu.Action("Join a watch party", AIcon.Play) { }, Modifier) { profileMenu = false; joinParty = true }
                    if (FofAccount.signedInState) QuickRow(QuickMenu.Action("Sign out", AIcon.Close) { }, Modifier) { profileMenu = false; FofAccount.signOut(ctx); Profiles.restartToPicker(ctx) }
                }
            }
        }
        if (joinParty) { BackHandler { joinParty = false }; JoinPartyDialog { joinParty = false } }
        if (surprise) SurpriseMeDialog(onOpen, heroPlay) { surprise = false }
        if (picker) {
            BackHandler { picker = false }
            ProfilePicker { id -> picker = false; if (id != Profiles.active) Profiles.switchTo(ctx, id) else if (Taste.needed(ctx)) Profiles.reopen(ctx) }
        }
        Sidebar(
            tab,
            { id -> scope.launch { states.getOrNull(id)?.scrollToItem(0) }; onTab(id) },
            Modifier.align(SidebarPrefs.align()), sideFocus
        ) { inSidebar = it }
    }
}

@Composable
private fun EmptyHint(text: String) {
    // Round 17: notice with icon, larger and calmer (instead of a small gray line)
    Row(Modifier.padding(horizontal = DS.PageX, vertical = dpx(20)), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(14))) {
        AppIcon(AIcon.Info, Accent2, size = dpx(26))
        Text(text, color = Tok.TextMid, fontSize = spx(19))
    }
}

object PillMemory {
    var key: String? = null
}

/** A row from TMDB (needs the TMDB key); left out while empty, so it never leaves a gap. */
@Composable
private fun ExtraRow(title: String, key: String, cardW: Int, onOpen: (Item) -> Unit, onBrowse: (BrowseReq) -> Unit, style: Int = -1, load: suspend () -> List<Item>) {
    // null = still loading: the row keeps its place (a row popping in later pushed the screen while moving through it)
    val list by produceState(TmdbExtra.cached(key).takeIf { it.size >= 3 }, key) { value = runCatching { load() }.getOrDefault(emptyList()) }
    val l = list
    if (l == null) { Spacer(Modifier.fillMaxWidth().height(dpx(300))); return }
    if (l.size < 3) return
    ItemsRow(title, l, cardW, onOpen, style = style) { onBrowse(BrowseReq(title, l, SORT_PLAYLIST)) }
}

/** Collections (sagas): wide picture cards; OK opens the movies in order. */
@Composable
private fun CollectionsRow(onBrowse: (BrowseReq) -> Unit) {
    if (!TmdbExtra.enabled()) return
    val scope = rememberCoroutineScope()
    Column {
        Text(raw = true, text = "Collections", color = Tok.TextHi, fontSize = spx(24), fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = RowPadX, bottom = dpx(10)))
        LazyRow(contentPadding = PaddingValues(horizontal = RowPadX, vertical = dpx(10)), horizontalArrangement = Arrangement.spacedBy(dpx(18))) {
            items(TmdbExtra.COLLECTIONS, key = { "coll" + it.id }) { c ->
                val art by produceState<String?>(null, c.id) { value = TmdbExtra.collectionArt(c) }
                var f by remember { mutableStateOf(false) }
                val sc by animateFloatAsState(if (f) focusScale(1.05f) else 1f, tween(150), label = "coll")
                val shape = RoundedCornerShape(dpx(12))
                Box(
                    Modifier.size(dpx(380), dpx(214)).graphicsLayer { scaleX = sc; scaleY = sc }.focusGlow(f, shape).clip(shape)
                        .background(Color(0xFF16161A)).border(if (f) 2.dp else 1.dp, if (f) Color.White else Color(0x1AFFFFFF), shape)
                        .onFocusChanged { f = it.isFocused }.restoreFocus("coll" + c.id)
                        .clickable { scope.launch { val l = TmdbExtra.collection(c); if (l.isNotEmpty()) onBrowse(BrowseReq(c.name, l, SORT_PLAYLIST)) } }
                ) {
                    if (art != null) AsyncImage(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, 0.5f to Color.Transparent, 1f to Color(0xCC000000))))
                    Text(raw = true, text = c.name, color = Color.White, fontSize = spx(22), fontWeight = FontWeight.Bold, maxLines = 1,
                        modifier = Modifier.align(Alignment.BottomStart).padding(dpx(16)))
                }
            }
        }
    }
}

/** One catalog row: shown at once from the saved list, refreshed in the background; keeps its height while loading. */
@Composable
private fun CatalogRow(spec: Catalog.Spec, cardW: Int, onOpen: (Item) -> Unit, onBrowse: (BrowseReq) -> Unit, exclude: Set<String> = emptySet()) {
    val scope = rememberCoroutineScope()
    // null = still loading (the row keeps its place); empty after loading (no network) = the row is left out
    val got by produceState(Catalog.cached(spec).ifEmpty { null }, spec) { value = Catalog.items(spec) }
    // Titles already in Top 10 right above are not repeated
    val list = remember(got, exclude) { got?.let { l -> if (exclude.isEmpty()) l else l.filter { it.id !in exclude } } }
    if (list == null) { Spacer(Modifier.fillMaxWidth().height(dpx(300))); return }
    if (list.isEmpty()) return
    ItemsRow(spec.title, list, cardW, onOpen) { scope.launch { onBrowse(BrowseReq(spec.title, Catalog.items(spec, 4), SORT_PLAYLIST)) } }
}

@Composable
private fun ItemsRow(
    title: String, list: List<Item>, cardW: Int, onOpen: (Item) -> Unit,
    firstReq: FocusRequester? = null, style: Int = -1,
    upReq: FocusRequester? = null, leftReq: FocusRequester? = null, onAll: () -> Unit
) {
    // After "Show all" and back: focus the link of this row again
    val key = NavState.tab.toString() + "|" + title
    val headerReq = remember { FocusRequester() }
    val firstCard = firstReq ?: remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (PillMemory.key == key) {
            delay(250)
            runCatching { headerReq.requestFocus() }
            PillMemory.key = null
        }
    }
    // Picture type from "View" (default: cover); Top 10 always cover
    val st = if (style >= 0) style else if (General.cardArt == 1) CARD_LAND else CARD_POSTER
    CardRow(title, list, st, onOpen, { PillMemory.key = key; onAll() }, RowStates.get("${NavState.tab}|$title"), headerReq, firstCard,
        upReq = upReq, leftReq = leftReq)
}

@Composable
private fun ResumeRow(list: List<Pair<Entry, Item>>, cardW: Int, onResume: (Entry, Item) -> Unit, onOpen: (Item) -> Unit) {
    val ctx = LocalContext.current
    Column {
        val g = LocalCardGrid.current
        Row(Modifier.padding(start = dpx(29), top = dpx(26), end = dpx(28)), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(dpx(16))) {
            // Same as the Mac app: "Continue Watching for <profile>" in English, the plain title in other languages
            val who = Profiles.activeName()
            Text(raw = true, text = Lang.tr("Weiterschauen") + if (Lang.code() == "en" && who.isNotBlank()) " for $who" else "",
                color = Color(0xFFE5E5E5), fontSize = spx(23), fontWeight = FontWeight.Bold)
        }
        LazyRow(
            state = RowStates.get("${NavState.tab}|resume"),
            contentPadding = PaddingValues(start = RowPadX, end = RowPadX, top = dpx(12), bottom = dpx(10)),
            horizontalArrangement = Arrangement.spacedBy(g.gap)
        ) {
            // The identity of a Continue Watching card is its title and
            // episode, not its current position in the row. A progress sync
            // must not make its focused neighbor inherit the old card state.
            items(list, key = { pair ->
                pair.second.id + ":" + pair.first.season + ":" + pair.first.ep
            }) { pair ->
                val e = pair.first
                val item = pair.second
                val se = if (e.season > 0 || e.ep > 0) e.season to e.ep else ResumeLogic.seasonEp(e.title)
                val multi = item.kind == Kind.SERIES && item.episodes.any { it.season != (se?.first ?: 1) }
                val epText = se?.let { if (multi) "S${it.first} · Episode ${it.second}" else "Episode ${it.second}" }
                val sub = when {
                    item.kind != Kind.SERIES -> "bei " + (e.pos / 60000) + " Min"
                    e.next && epText != null -> "Als Nächstes: $epText"
                    epText != null -> epText
                    else -> e.title.removePrefix(item.title).trim()
                }
                val badge = when {
                    item.kind == Kind.SERIES && NewEpisodes.list.any { it.id == item.id } -> "New Episode"
                    e.next -> "Next Up"
                    else -> null
                }
                ResumeCard(
                    item, se, badge, sub, if (e.next || e.dur <= 0) 0f else (e.pos.toFloat() / e.dur).coerceIn(0f, 1f),
                    // Hold OK: resume, start over, details, My List, remove from Continue watching (QuickMenu)
                    Modifier.width(g.wide).restoreFocus(item.id).quickActions(item, { onOpen(item) }, e) { en -> onResume(en, item) }
                ) { onResume(e, item) }
            }
        }
    }
}

/**
 * Continue watching card (Nuvio style): the episode's own picture (or the movie's scene image), "S2 E9", the show in bold
 * and the episode title, a "Next Up" / "New Episode" badge, a thin progress line. Episode pictures are blurred while
 * spoiler protection is on and the episode is not watched yet.
 */
@Composable
private fun ResumeCard(item: Item, se: Pair<Int, Int>?, badge: String?, sub: String, progress: Float, modifier: Modifier, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val m = rememberMeta(item)
    // Episode picture and title: Cinemeta for catalog series (loaded once per series)
    val ep by produceState<EpMeta?>(null, item.id, se) {
        if (item.kind != Kind.SERIES || se == null) return@produceState
        var map = LazySeries.cmEpisodes[item.id]
        if (map == null && item.id.startsWith("stremio:")) { runCatching { LazySeries.load(ctx, item) }; map = LazySeries.cmEpisodes[item.id] }
        value = map?.get(se.first)?.firstOrNull { it.num == se.second }
    }
    var f by remember { mutableStateOf(false) }
    val clean = FofPrefs.cleanLook
    val sc by animateFloatAsState(if (f) focusScale(if (clean) 1.04f else 1.06f) else 1f, tween(150), label = "res")
    val shape = RoundedCornerShape(DS.RadiusM)
    val still = ep?.still
    // Grey tiles fix: try every picture in turn (episode still, scene, poster, logo, then Cinemeta's pictures by IMDb id)
    // and move on when one fails to load; with nothing left, a title card instead of an empty grey box
    val tt = item.id.removePrefix("stremio:").takeIf { StremioLogic.isImdb(it) } ?: m?.imdbId?.takeIf { StremioLogic.isImdb(it) }
    val cands = remember(still, m?.backdrop, m?.poster, item.logo, tt) {
        listOfNotNull(still, m?.backdrop?.let { cardBackdrop(it) }, tt?.let { "https://images.metahub.space/background/medium/$it/img" },
            m?.poster?.let { cardPoster(it) }, tt?.let { "https://images.metahub.space/poster/medium/$it/img" }, item.logo).distinct()
    }
    var ci by remember(cands) { mutableIntStateOf(0) }
    val img = cands.getOrNull(ci)
    // Automatic problem report: Continue Watching card with no picture at all
    LaunchedEffect(ci, cands.size, m) { if (m != null && ci >= cands.size) AutoReport.issue(ctx, "picture", "Continue Watching: '" + item.title + "' (" + item.id.take(40) + ")") }
    Box(
        modifier.graphicsLayer { scaleX = sc; scaleY = sc }.billboardFocus(item) { f = it }.clickable(onClick = onClick)
            .aspectRatio(16f / 9f).clip(shape).background(Color(0xFF16161A))
            .border(if (f) 3.dp else 1.dp, if (f) Color.White else Color(0x14FFFFFF), shape)
    ) {
        if (img == null) ArtPlaceholder(item, Modifier.fillMaxSize(), showTitle = false)
        else {
            val blur = img == still && FofPrefs.spoilerPics && badge != null
            if (blur) {
                val req = remember(still) { coil.request.ImageRequest.Builder(ctx).data(still).size(32, 18).build() }
                AsyncImage(req, item.title, Modifier.fillMaxSize().then(if (Lite.on) Modifier else Modifier.blur(14.dp)), contentScale = ContentScale.Crop, onError = { ci++ })
            } else AsyncImage(img, item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, onError = { ci++ })
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color(0xE6000000))))
        if (badge != null) Box(
            Modifier.align(Alignment.TopEnd).padding(dpx(10)).clip(RoundedCornerShape(6.dp))
                .background(if (badge == "New Episode") Color(0xFF2563EB) else Color(0xCC000000)).padding(horizontal = 8.dp, vertical = 3.dp)
        ) { Text(raw = true, text = badge, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
        Column(Modifier.align(Alignment.BottomStart).padding(start = dpx(14), end = dpx(14), bottom = dpx(if (progress > 0f) 16 else 12))) {
            if (se != null && item.kind == Kind.SERIES) Text(raw = true, text = "S${se.first} E${se.second}", color = Color(0xCCFFFFFF), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(raw = true, text = item.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val line = if (item.kind == Kind.SERIES) ep?.name ?: sub else sub
            Text(raw = true, text = line, color = Color(0xB3FFFFFF), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (progress > 0f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x40FFFFFF))) {
            Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(Color(0xFFE60023)))
        }
    }
}

/** Wide landscape card (backdrop) with title and progress bar, like "Continue watching" in Netflix. */
@Composable
private fun WideCard(item: Item, sub: String?, progress: Float, modifier: Modifier, onClick: () -> Unit) {
    val m = rememberMeta(item)
    var f by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(if (f) focusScale(1.06f) else 1f, label = "wide")
    val shape = RoundedCornerShape(DS.RadiusM)
    val img = m?.backdrop ?: m?.poster ?: item.logo
    Column(modifier.graphicsLayer { scaleX = sc; scaleY = sc }.billboardFocus(item) { f = it }.clickable(onClick = onClick)) {
        Box(
            (if (FofPrefs.cleanLook) Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(shape).background(Surf2)
                .then(if (f) Modifier.border(3.dp, Color.White, shape) else Modifier)
            else Modifier.fillMaxWidth().aspectRatio(16f / 9f).focusGlow(f, shape).clip(shape)
                .background(Brush.linearGradient(listOf(colorFor(item).copy(alpha = .75f), Surf2)))
                .border(1.dp, FofGlass.edge(false), shape))
        ) {
            if (img != null) AsyncImage(img, item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xDD000000))))
            if (!FofPrefs.cleanLook) AppIcon(AIcon.Play, Color.White, Modifier.align(Alignment.Center), 30.dp)
            Text(raw = true, text = item.title, color = Color.White, fontSize = if (FofPrefs.cleanLook) spx(22) else 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, end = 12.dp, bottom = if (progress > 0f) 14.dp else 10.dp))
            if (progress > 0f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(5.dp).background(Color(0x66FFFFFF))) {
                Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(Accent2))
            }
        }
        if (sub != null) Text(sub, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp, start = 4.dp))
    }
}

/** One of the area's lists as an outlined button next to "Genres" in the big picture (Mac look). */
@Composable
private fun HeroLink(t: HeaderTab) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(dpx(4))
    Box(
        Modifier.height(dpx(40)).clip(shape)
            .background(if (f) Color.White else Color(0x99000000))
            .border(1.dp, if (f) Color.White else Color(0x66FFFFFF), shape)
            .onFocusChanged { f = it.isFocused }.clickable(onClick = t.onClick)
            .padding(horizontal = dpx(16)),
        contentAlignment = Alignment.Center
    ) { Text(t.label, color = if (f) Color.Black else Color.White, fontSize = spx(17), fontWeight = FontWeight.SemiBold, maxLines = 1) }
}

/** Second button in the big picture ("More info" / "To list"): target for Left from "Show all" of the first row. */
object HeroFocus {
    val second = FocusRequester()
    /** The big picture's buttons have the focus (the Keep watching card only takes the focus from there). */
    @Volatile var has = false
}

/** How far the big picture is pushed up (for the parallax effect); 0 when it is no longer at the top. */
private fun heroScroll(st: LazyListState): Int = if (st.firstVisibleItemIndex == 0) st.firstVisibleItemScrollOffset else 0

@Composable
private fun Hero(
    featured: List<Item>, heroH: Dp, home: Boolean, onOpen: (Item) -> Unit, onPlay: (Item) -> Unit,
    header: @Composable (Modifier) -> Unit, scrollY: () -> Int = { 0 },
    category: String? = null, genres: List<String> = emptyList(), onGenre: (Int) -> Unit = {},
    links: List<HeaderTab> = emptyList(), onFocusTop: () -> Unit
) {
    val ctx = LocalContext.current
    var idx by remember { mutableIntStateOf(0) }
    // Trailer running in the card: the slideshow waits until it stops
    var trailerOn by remember { mutableStateOf(false) }
    var heroHasFocus by remember { mutableStateOf(false) }
    // Read through derivedStateOf: reading the scroll offset directly would recompose the hero on every scroll frame
    val atTop by remember { derivedStateOf { scrollY() == 0 } }
    LaunchedEffect(featured.size, General.heroSec) {
        // Changes only while the big picture is fully in view: swapping a full-size picture while you move through the
        // rows below made scrolling stutter on Fire TV
        while (featured.size > 1 && General.heroSec > 0) { delay(General.heroSec * 1000L); if (!Billboard.active && !trailerOn && atTop) idx = (idx + 1) % featured.size }
    }
    val item = featured[idx.coerceIn(0, featured.lastIndex)]
    val trailerKey = rememberFullMeta(item, rememberMeta(item))?.trailerKey
    // Very long crossfades render two large backdrops at once. On a Fire
    // Stick-class GPU that can produce dropped frames during D-pad movement.
    val heroFadeMs = remember(ctx, Look.motion, Lite.on) {
        // Light mode: the picture changes at once (a crossfade draws two full-size pictures for a third of a second)
        // Light mode: a short crossfade instead of a cut (the pictures changing at once looked jumpy)
        if (Look.motion == 0) 0 else if (Lite.on || Mem.tier(ctx) == 0) 220 else 320
    }

    // Picture across the full width (also behind the header), faded into the background at the left and bottom.
    // Round 14: without arrows; the description has more width (up to about the middle of the picture) and is no longer cut off (max. 250 characters).
    // Round 17c: parallax while scrolling (the constant zoom was removed for performance)
    // TryBox-style: the header sits above, the picture is a rounded card inset from the edges with the artwork's
    // color glowing softly behind it (one ambient light, no full-bleed picture under the header).
    // Same as the Mac app: a rounded card 18 from the top and 28 from the sides, no glow behind it
    val cardShape = RoundedCornerShape(dpx(22))
    val cardTop = dpx(18)
    Box(Modifier.fillMaxWidth().height(heroH + dpx(40)).background(Bg).clipToBounds().onFocusChanged { heroHasFocus = it.hasFocus; HeroFocus.has = it.hasFocus; if (it.hasFocus) Billboard.cardFocus = false }) {
        // Performance (Fire TV / onn): glow, halo, picture and gradients are ONE cached layer – painted once per title,
        // then only composited. No shadow and no inner parallax (both forced a full repaint on every scroll frame).
        Box(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }) {
            Box(Modifier.fillMaxSize().padding(start = dpx(28), end = dpx(28), top = cardTop, bottom = dpx(10))
                .clip(cardShape).background(Color(0xFF111111))) {
                Crossfade(item, animationSpec = tween(heroFadeMs), label = "heroBg") { c ->
                    val m = rememberMeta(c)
                    // Light mode: the 780-px picture (a third of the pixels to download and decode)
                    m?.backdrop?.let { AsyncImage(if (Lite.on) cardBackdrop(it) else it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopEnd) }
                }
                // Netflix: the trailer fades in over the picture after a few seconds on the same title (muted, never focused)
                HeroTrailer(trailerKey, heroHasFocus && atTop, Modifier.fillMaxSize()) { trailerOn = it }
                // Light shading only where the text sits (bottom left), the picture stays bright
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color(0xBF000000), 0.35f to Color(0x59000000), 0.6f to Color.Transparent)))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0xD9000000))))
            }
        }
        header(Modifier.align(Alignment.TopStart))
        // Netflix category page: big "Movies" / "TV Shows" with a Genres menu at the top of the picture
        if (category != null) Row(
            Modifier.align(Alignment.TopStart).padding(start = dpx(72), top = cardTop + dpx(26)),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(26))
        ) {
            Text(raw = true, text = category, color = Color.White, fontSize = spx(44), fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
            if (genres.isNotEmpty()) {
                var open by remember { mutableStateOf(false) }
                var gf by remember { mutableStateOf(false) }
                Box {
                    Row(
                        Modifier.height(dpx(40)).clip(RoundedCornerShape(dpx(4)))
                            .background(if (gf) Color.White else Color(0x99000000))
                            .border(1.dp, if (gf) Color.White else Color(0xE6FFFFFF), RoundedCornerShape(dpx(4)))
                            .onFocusChanged { gf = it.isFocused }.clickable { open = true }
                            .padding(horizontal = dpx(16)),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(12))
                    ) {
                        Text("Genres", color = if (gf) Color.Black else Color.White, fontSize = spx(17), fontWeight = FontWeight.SemiBold)
                        AppIcon(AIcon.Down, if (gf) Color.Black else Color.White, size = dpx(14))
                    }
                    if (open) OptionPopup(genres, -1, { open = false }) { i -> open = false; onGenre(i) }
                }
            }
            // The area's other lists (Categories, Top rated, Recently added, All …) as the same outlined buttons
            links.forEach { t -> HeroLink(t) }
        }
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = dpx(80), bottom = dpx(if (home) 52 else 42)).widthIn(max = dpx(760)),
            verticalArrangement = Arrangement.spacedBy(dpx(10))
        ) {
            Crossfade(item, animationSpec = tween(if (heroFadeMs == 0) 0 else 180), label = "heroText") { c ->
                // with IMDb and Rotten Tomatoes rating (fetched the first time, then remembered on the device)
                val m = rememberFullMeta(c, rememberMeta(c))
                Column(verticalArrangement = Arrangement.spacedBy(dpx(if (home) 10 else 8))) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(dpx(12))
                    ) {
                        Kicker(Lang.tr(kindLabel(c.kind)))
                        qualityOf(c)?.let { PreviewQualityBadge(it) }
                    }
                    HeroTitle(c, m, if (home) 80 else 72, dpx(560), dpx(170))
                    HeroMetaLine(c, m, dots = !home)
                    m?.overview?.takeIf { it.isNotBlank() }?.let {
                        Text(raw = true, text = TitleText.short(it, 250), color = Tok.TextMid, fontSize = spx(19), lineHeight = spx(27), maxLines = 3)
                    }
                }
            }
            Row(
                Modifier.padding(top = dpx(if (home) 12 else 8)).onFocusChanged { if (it.hasFocus) onFocusTop() },
                horizontalArrangement = Arrangement.spacedBy(dpx(16))
            ) {
                val h = if (home) 52 else 46
                // Started already: the main action is Resume, with the time left (it resumes at the saved spot)
                val resumeE = WatchStore.entries.firstOrNull { it.itemId == item.id && !it.next && it.pos > 60_000 && it.dur > it.pos }
                HeroButton(if (resumeE != null) "Resume  ·  " + ((resumeE.dur - resumeE.pos) / 60_000).coerceAtLeast(1) + " min left" else "Play",
                    AIcon.Play, true, Modifier.focusRequester(ContentFocus.req), h) { onPlay(item) }
                if (home) HeroButton("More Info", AIcon.Info, false, Modifier.focusRequester(HeroFocus.second), h = h) { onOpen(item) }
                else {
                    val inList = Library.isWatch(item.id)
                    HeroButton(if (inList) "In Meiner Liste" else "View List", if (inList) AIcon.Check else AIcon.Plus, false, Modifier.focusRequester(HeroFocus.second), h = h) { Library.toggleWatch(ctx, item.id) }
                }
            }
        }
        if (featured.size > 1) Row(
            Modifier.align(Alignment.BottomEnd).padding(end = dpx(76), bottom = dpx(if (home) 56 else 46)),
            horizontalArrangement = Arrangement.spacedBy(dpx(12)), verticalAlignment = Alignment.CenterVertically
        ) {
            // Active dot fills up until the next change (shows when the picture changes)
            val prog = remember(idx) { androidx.compose.animation.core.Animatable(0f) }
            // The fill redraws the screen on every frame for the whole interval: not in light mode, and only while the big
            // picture is in view (it kept a budget box's graphics chip busy all the time, even while scrolling the rows)
            LaunchedEffect(idx, General.heroSec, Lite.on, atTop) {
                if (General.heroSec > 0 && !Lite.on && atTop) prog.animateTo(1f, tween(((1f - prog.value) * General.heroSec * 1000).toInt().coerceAtLeast(1), easing = androidx.compose.animation.core.LinearEasing))
            }
            featured.indices.forEach { i ->
                // 17c: progress only drawn (no rebuild on every frame)
                if (i == idx) Box(Modifier.width(dpx(40)).height(dpx(8)).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = .3f))) {
                    Box(Modifier.fillMaxSize().graphicsLayer {
                        scaleX = if (General.heroSec > 0 && !Lite.on) prog.value.coerceIn(0.08f, 1f) else 1f
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                    }.background(Accent1))
                }
                else Box(Modifier.width(dpx(8)).height(dpx(8)).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = .45f)))
            }
        }
    }
}

@Composable
private fun SkeletonHome() {
    val t = rememberInfiniteTransition(label = "sk")
    val a by t.animateFloat(0.04f, 0.08f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "ska")
    val col = Color.White.copy(alpha = a)
    Column(Modifier.fillMaxSize().padding(horizontal = DS.PageX).padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Box(Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(DS.RadiusXl)).background(col))
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            repeat(5) { Box(Modifier.width(150.dp).height(225.dp).clip(RoundedCornerShape(DS.RadiusM)).background(col)) }
        }
    }
}

/** Background and info of the selected title. Reads the fade value only here and in layer lambdas, so fading does not recompose the home screen. */
@Composable
private fun BillboardLayer(fade: () -> Float, item: State<Item?>, infoMod: Modifier) {
    val shown by remember { derivedStateOf { fade() > 0.01f } }
    if (!shown) return
    val bi = item.value
    BillboardBackdrop(bi, Modifier.fillMaxSize().graphicsLayer { alpha = fade() })
    bi?.let { BillboardInfo(it, infoMod.graphicsLayer { alpha = fade(); translationY = (1f - fade()) * 20.dp.toPx() }) }
}
