package com.ipvio.stream

import androidx.compose.ui.graphics.lerp
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import java.net.URLEncoder

fun openTrailer(ctx: Context, key: String?, title: String) {
    val uri = if (key != null) "https://www.youtube.com/watch?v=$key"
    else "https://www.youtube.com/results?search_query=" + URLEncoder.encode("$title Trailer", "UTF-8")
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION))
    } catch (e: Exception) {
        Toast.makeText(ctx, Lang.tr("No app is available to open the trailer."), Toast.LENGTH_LONG).show()
    }
}

fun openExternal(ctx: Context, url: String, title: String) {
    val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), "video/*").putExtra("title", title)
    try {
        ctx.startActivity(Intent.createChooser(i, "Externer Player").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION))
    } catch (e: Exception) {
        Toast.makeText(ctx, Lang.tr("No external video player is installed (such as VLC)."), Toast.LENGTH_LONG).show()
    }
}

private val QORDER = listOf("4K", "FHD", "HD")

/** Position of the first episode in the detail page list (0 = header, 1 = seasons/sections). */
/** Position of the episodes area (seasons left, episodes right) in the page list: 0 = header, 1 = episodes */
private const val EP_PANE = 1

/** Up to this length the description is always shown completely (no "Read more"). */
private const val FULL_TEXT_CHARS = 800

/**
 * Detail page. Series with a placeholder (episodes are only loaded on opening, see [LazySeries]) fetch their episodes first;
 * while that runs, the page shows a spinner, on an error "Try again".
 */
@Composable
fun DetailScreen(
    item: Item, all: List<Item>, onPlay: (String, String, Long) -> Unit,
    onPerson: (PersonRef) -> Unit, onOpenItem: (Item) -> Unit, onBack: () -> Unit,
    embedded: Boolean = false, autoFocus: Boolean = true, onTopUp: (() -> Unit)? = null,
    showActions: Boolean = true
) {
    val ctx = LocalContext.current
    var tryNo by remember(item.id) { mutableIntStateOf(0) }
    // Cinema preview without buttons (info only) does not need the episodes
    val need = LazySeries.isStub(item) && showActions
    var full by remember(item.id) { mutableStateOf(LazySeries.full(item).takeIf { it !== item }) }
    var failed by remember(item.id) { mutableStateOf(false) }
    if (need && full == null) {
        LaunchedEffect(item.id, tryNo) {
            failed = false
            // Never an endless spinner: after 15 s the retry screen shows
            val r = kotlinx.coroutines.withTimeoutOrNull(15_000) { runCatching { LazySeries.load(ctx, item) }.getOrNull() }
            if (r != null) full = r else failed = true
        }
        BackHandler(enabled = !embedded, onBack = onBack)
        val retry = remember { FocusRequester() }
        LaunchedEffect(failed) { if (failed && !embedded) { delay(120); runCatching { retry.requestFocus() } } }
        Box(Modifier.fillMaxSize().background(Bg), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (!failed) {
                    Spinner(Accent2, Modifier.size(48.dp))
                    Text("Loading episodes…", color = Muted, fontSize = 18.sp)
                } else {
                    Text(item.title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text("Could not load episodes. Check your internet connection and provider.", color = Muted, fontSize = 17.sp)
                    PillButton("Try again", Modifier.focusRequester(retry)) { tryNo++ }
                    if (!embedded) PillButton("Back", light = true) { onBack() }
                }
            }
        }
        return
    }
    DetailScreenFull(
        if (need) full ?: item else item, all, onPlay, onPerson, onOpenItem, onBack,
        embedded, autoFocus, onTopUp, showActions
    )
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun DetailScreenFull(
    item: Item, all: List<Item>, onPlay: (String, String, Long) -> Unit,
    onPerson: (PersonRef) -> Unit, onOpenItem: (Item) -> Unit, onBack: () -> Unit,
    // Cinema view: embedded below the title row (no own back button, no automatic focus, Up at the header passes on)
    embedded: Boolean = false, autoFocus: Boolean = true, onTopUp: (() -> Unit)? = null,
    // false = info only (cinema preview): no buttons, no season bar
    showActions: Boolean = true
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var heroFocused by remember { mutableStateOf(false) }
    LaunchedEffect(heroFocused) {
        // Let one scroll animation complete. The previous forced scroll after
        // 160 ms interrupted this animation and visibly snapped series cards.
        if (heroFocused && (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0)) {
            runCatching { listState.animateScrollToItem(0) }
        }
    }
    BackHandler(enabled = !embedded, onBack = onBack)
    // Catalog titles bring Cinemeta's picture and text, so the page is complete even without a TMDB key
    var meta by remember(item.id) { mutableStateOf(MetaRepo.cached(item) ?: Catalog.meta(item)) }
    var metaDone by remember(item.id) { mutableStateOf(!MetaRepo.usable(item) || item.kind == Kind.LIVE) }
    LaunchedEffect(item.id) {
        MetaRepo.extended(ctx, item)?.let { meta = it }
        metaDone = true
    }
    val credits by produceState<Credits?>(null, meta?.tmdbId) {
        val m = meta
        value = if (m != null && m.tmdbId > 0) CreditsRepo.credits(ctx, m) else null
    }
    // Match against the whole playlist in the background (an index is built the first time)
    val similar by produceState(emptyList<Item>(), credits, all) {
        val c = credits ?: return@produceState
        value = withContext(Dispatchers.Default) { LibMatch.matches(all, c.similar, 30).filter { s -> s.id != item.id } }
    }
    // TryBox-style "You Might Also Like": library matches first, then TMDB recommendations for catalog titles
    val recs by produceState(TmdbExtra.cached("because:" + item.id.removePrefix("stremio:")), item.id) {
        // After the page has opened (network and new rows during the opening animation made it stutter)
        delay(900)
        value = runCatching { TmdbExtra.because(item) }.getOrDefault(value)
    }
    val alsoLike = remember(similar, recs) { (similar + recs).filter { it.id != item.id }.distinctBy { it.id }.take(30) }
    val reviews by produceState(emptyList<TmdbExtra.Review>(), meta?.tmdbId) {
        val id = meta?.tmdbId ?: 0
        if (id > 0) { delay(1200); value = TmdbExtra.reviews(id, item.kind == Kind.SERIES) }
    }
    // The page takes on the artwork's color (TryBox: the panel glows in the title's own tone)
    val tint by produceState(0, meta?.backdrop) { value = meta?.backdrop?.let { runCatching { ArtColor.of(ctx, it) }.getOrDefault(0) } ?: 0 }
    val tintCol by androidx.compose.animation.animateColorAsState(if (tint == 0) Color.Transparent else Color(tint), tween(600), label = "tint")

    val seasons = remember(item) { item.episodes.map { it.season }.distinct().sorted() }
    // Resume point: the page opens at this season and the section with this episode
    val resume = WatchStore.entries.firstOrNull { it.itemId == item.id }
    val resumeSE = remember(resume) { resume?.let { r -> if (r.season > 0 || r.ep > 0) r.season to r.ep else ResumeLogic.seasonEp(r.title) } }
    // Back from the player: open at exactly the episode that played last (once), otherwise at the resume point
    val retSE = remember(item.id) { DetailReturn.take(item.id)?.takeIf { it.first > 0 || it.second > 0 } }
    val startSE = retSE ?: resumeSE
    var season by remember(item.id) { mutableIntStateOf(startSE?.first?.takeIf { it in seasons } ?: seasons.firstOrNull() ?: 1) }
    val eps = remember(item, season) { item.episodes.filter { it.season == season } }
    // Counter: raised after marking by hand so that checkmarks and buttons redraw
    var progVer by remember(item.id) { mutableIntStateOf(0) }
    val progress = remember(item.id, progVer) { EpProgress.of(ctx, item.id).toMap() }

    val saved = remember(item.id) { VariantPref.get(ctx, item.id) }
    var prefQ by remember(item.id) { mutableStateOf(saved.first) }
    var prefLang by remember(item.id) { mutableStateOf(saved.second) }
    var prefLabel by remember(item.id) { mutableStateOf(saved.third) }
    val allVars = remember(item) { item.episodes.flatMap { it.variants } }
    val qualities = remember(item) { QORDER.filter { q -> allVars.any { it.quality == q } } }
    val langs = remember(item) { allVars.mapNotNull { it.lang }.distinct().sorted() }
    val labels = remember(item) { allVars.map { it.label }.filter { it.isNotBlank() }.distinct() }
    val multi = item.kind == Kind.SERIES && item.episodes.any { it.variants.size > 1 }
    // Movies: the same movie in other qualities (own entries of the provider)
    val movieAlts by produceState(listOf(item), item, all) {
        if (item.kind == Kind.MOVIE) value = withContext(Dispatchers.Default) { Sources.alternatives(item, all) }
    }
    val movieQs = remember(movieAlts) { QORDER.filter { q -> movieAlts.any { it.quality == q } } }
    // Round 14: choose the source of a movie explicitly (same movie several times in the playlist, e.g. HD and 4K)
    val movieVars: List<Variant> = remember(movieAlts) { Sources.asVariants(movieAlts) }

    fun save() = VariantPref.set(ctx, item.id, prefQ, prefLang, prefLabel)
    val qCap = remember { PlaybackPrefs.cap(ctx) }
    fun pick(ep: Episode) = pickVariant(ep, prefQ, prefLang, prefLabel, qCap)
    val playUrl = if (item.kind == Kind.SERIES) eps.firstOrNull()?.let { pick(it).url }
        else movieVars.firstOrNull { it.label == prefLabel }?.url ?: item.url

    val tmdbId = meta?.tmdbId
    val imdbId = meta?.imdbId
    LaunchedEffect(Unit) { Stremio.ensure(ctx) }
    // Title without a playlist link: ask the addons ahead, so Play starts without waiting
    LaunchedEffect(item.id, season, imdbId, Stremio.addons.size) {
        if (!playUrl.isNullOrBlank() || Stremio.addons.none { it.streams } || item.kind == Kind.LIVE) return@LaunchedEffect
        val id = Stremio.imdbFor(item, imdbId ?: item.id.removePrefix("stremio:").takeIf { StremioLogic.isImdb(it) }) ?: return@LaunchedEffect
        val ep1 = eps.firstOrNull()?.num ?: 1
        // Asked ahead, so Play has its streams at once (not unlocked on Real-Debrid: that would add every viewed title to the account)
        runCatching { Stremio.streams(item.kind, id, season, ep1) }
    }
    var showStremio by remember(item.id) { mutableStateOf(false) }
    // Episode picked in a Stremio-only series: the streams popup opens at that season and episode
    var stremioAt by remember(item.id) { mutableStateOf<Pair<Int, Int>?>(null) }
    // Play / episode pressed (not the "Stremio" button): one-click play may start the best stream at once
    var stremioAuto by remember(item.id) { mutableStateOf(false) }
    var showMore by remember(item.id) { mutableStateOf(false) }
    var show247 by remember(item.id) { mutableStateOf(false) }
    var busy247 by remember(item.id) { mutableStateOf(false) }
    LaunchedEffect(item.id) {
        if (AppNav.autoStream == item.id) {
            AppNav.autoStream = null
            // Only a resume point of this very title (never a leftover from another show)
            if (AppNav.autoResumeId != item.id) { AppNav.autoResume = null; AppNav.autoResumeId = null }
            AppNav.autoResume?.let { (s, e, _) -> if (item.kind == Kind.SERIES && s > 0 && e > 0) stremioAt = s to e }
            // Addons are read in the background at start: wait for them a moment (Continue watching right after start did nothing)
            Stremio.ensure(ctx)
            var waited = 0
            while (Stremio.addons.isEmpty() && waited < 40) { kotlinx.coroutines.delay(100); waited++ }
            if (Stremio.addons.isNotEmpty()) { stremioAuto = true; showStremio = true }
        }
    }
    val isTv = meta?.isTv == true
    // Long seasons (One Piece & co.): fetch TMDB seasons to use them as sections
    val needTmdbSeasons = remember(item) {
        item.kind == Kind.SERIES && item.episodes.groupBy { it.season }.values.any { it.size > EpisodeLogic.LONG || it.first().num > 1 }
    }
    val tmdbSeasons by produceState(emptyList<EpisodeLogic.TmdbSeason>(), tmdbId, isTv) {
        val id = tmdbId
        if (needTmdbSeasons && isTv && id != null && id > 0) value = MetaRepo.tvSeasons(ctx, id)
    }
    val groups = remember(eps, season, tmdbSeasons) { EpisodeLogic.groups(eps, season, seasons.size, tmdbSeasons) }
    // On season change to the left: land in the last section; "Go to episode" in another season: jump there
    val enterAtEnd = remember { booleanArrayOf(false) }
    val pendingNum = remember { intArrayOf(-1) }
    // Season change with Right: start at episode 1 of the new season (not at the last watched)
    val startFirst = remember { booleanArrayOf(false) }
    // Position in the season where the list stands – the shown section follows from it
    var anchor by remember(item.id, season) {
        mutableIntStateOf(
            when {
                pendingNum[0] > 0 -> EpisodeLogic.indexOfNum(eps, pendingNum[0]).also { pendingNum[0] = -1 }
                enterAtEnd[0] -> (groups.lastOrNull()?.from ?: 0).also { enterAtEnd[0] = false }
                startFirst[0] -> 0.also { startFirst[0] = false }
                startSE != null && startSE.first == season -> EpisodeLogic.indexOfNum(eps, startSE.second)
                else -> 0
            }.coerceAtLeast(0)
        )
    }
    val gi = EpisodeLogic.groupOf(groups, anchor)
    val group = groups.getOrNull(gi)
    val shown = remember(eps, group) { if (group == null) emptyList() else eps.subList(group.from, group.to + 1) }
    // Info from the matching TMDB season (with continuous numbering not season 1 of the playlist)
    val metaSeason = group?.tmdbSeason ?: season
    // New with a different TMDB match (the detail page can correct a wrongly stored match on opening)
    var epCache by remember(item.id, tmdbId) { mutableStateOf<Map<Int, List<EpMeta>>>(emptyMap()) }
    var epLoading by remember(item.id, tmdbId) { mutableStateOf(false) }
    LaunchedEffect(tmdbId, imdbId, metaSeason) {
        if (item.kind == Kind.SERIES && isTv && tmdbId != null && tmdbId > 0 && metaSeason >= 0 && epCache[metaSeason] == null) {
            epLoading = true
            // IMDb ratings per episode only with the same count (IMDb splits long anime differently)
            val sameCount = group == null || (group.offset == 0 && metaSeason == season)
            val res = MetaRepo.season(tmdbId, metaSeason, if (sameCount) imdbId else null)
            epCache = epCache + (metaSeason to res)
            epLoading = false
        }
    }
    val epMetas = epCache[metaSeason]?.takeIf { it.isNotEmpty() } ?: LazySeries.cmEpisodes[item.id]?.get(season) ?: emptyList()
    fun metaOf(ep: Episode): EpMeta? {
        val g = group ?: return null
        if (g.tmdbSeason < 0) return null
        val n = EpisodeLogic.tmdbNum(g, ep.num)
        return (epMetas.firstOrNull { it.num == n } ?: if (g.offset > 0) epMetas.firstOrNull { it.num == ep.num } else null)
            // Shared with the player's episode list (same titles, stills and synopses there)
            ?.also { EpisodeInfo.put(item.id, ep.season, ep.num, it) }
    }

    val playFocus = remember { FocusRequester() }
    // Back from a person or a similar title: go back there, not to "Resume"
    var topMoreFocused by remember { mutableStateOf(false) }
    var topRowFocused by remember { mutableStateOf(false) }
    val memKey = remember(item.id) { DetailFocus.last.remove(item.id) }
    var restored by remember(item.id) { mutableStateOf(memKey == null) }
    val restoreReq = remember { FocusRequester() }
    val castState = rememberLazyListState()
    val simState = rememberLazyListState()
    LaunchedEffect(autoFocus) { if (autoFocus && memKey == null && retSE == null) runCatching { playFocus.requestFocus() } }
    LaunchedEffect(memKey, alsoLike, credits) {
        if (memKey == null || restored) return@LaunchedEffect
        val isSim = memKey.startsWith("s:")
        val id = memKey.drop(2)
        val idx = if (isSim) alsoLike.indexOfFirst { it.id == id } else credits?.cast?.indexOfFirst { it.id.toString() == id } ?: -1
        if (idx >= 0) {
            listState.scrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
            repeat(3) { withFrameNanos { } }
            (if (isSim) simState else castState).scrollToItem(idx)
            repeat(3) { withFrameNanos { } }
            runCatching { restoreReq.requestFocus() }
            restored = true
        }
    }
    // If the target is not found (list changed), go to "Play" normally after a short time
    LaunchedEffect(memKey) {
        if (memKey == null) return@LaunchedEffect
        delay(5000)
        if (!restored) { restored = true; if (autoFocus) runCatching { playFocus.requestFocus() } }
    }

    // Description: 4 lines by default, expandable with "Read more"
    var descExpanded by remember(item.id) { mutableStateOf(false) }
    var descOverflow by remember(item.id) { mutableStateOf(false) }

    // Season, section and episode focus (index = position in the season)
    val seasonReq = remember { FocusRequester() }
    val seasonRowState = rememberLazyListState()
    val groupReq = remember { FocusRequester() }
    val groupRowState = rememberLazyListState()
    val epReqs = remember(item.id) { HashMap<Int, FocusRequester>() }
    val epFocusIdx = remember(item.id) { intArrayOf(0) }
    // Target for the next focus change (-1 = first episode of the section). Separate from epFocusIdx because on
    // season change the old row disappears and Compose briefly puts focus on some new row.
    val targetIdx = remember(item.id) { intArrayOf(-1) }
    var focusedIdx by remember(item.id) { mutableIntStateOf(-1) }
    var refocusEp by remember { mutableStateOf(false) }
    // Episode list (right in the episodes area); the area itself is as tall as the picture, the episodes scroll inside
    val epListState = rememberLazyListState()
    val paneH = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp - 72).dp.coerceAtLeast(360.dp)
    var focusTick by remember { mutableIntStateOf(0) }
    var jumpOpen by remember(item.id) { mutableStateOf(false) }
    var jumpText by remember(item.id) { mutableStateOf("") }
    val jumpReq = remember { FocusRequester() }

    fun epReq(i: Int): FocusRequester = epReqs.getOrPut(i) { FocusRequester() }

    fun switchSeason(delta: Int) {
        val ni = seasons.indexOf(season) + delta
        if (ni in seasons.indices) {
            enterAtEnd[0] = delta < 0
            startFirst[0] = delta > 0
            season = seasons[ni]
            // always land on the first episode (of the section), not on the old position
            targetIdx[0] = -1
            refocusEp = true
        }
    }

    /** Left/Right in the episode list: next section (at its first episode); at the end of the season on to the next season. */
    fun switchGroup(delta: Int) {
        val ni = gi + delta
        if (ni in groups.indices) {
            val g = groups[ni]
            targetIdx[0] = g.from
            anchor = g.from
            refocusEp = true
        } else switchSeason(delta)
    }

    fun focusActiveSeason() {
        scope.launch {
            seasonRowState.scrollToItem((seasons.indexOf(season) - 2).coerceAtLeast(0))
            withFrameNanos { }
            withFrameNanos { }
            runCatching { seasonReq.requestFocus() }
        }
    }

    fun focusActiveGroup() {
        scope.launch {
            groupRowState.scrollToItem((gi - 2).coerceAtLeast(0))
            withFrameNanos { }
            withFrameNanos { }
            runCatching { groupReq.requestFocus() }
        }
    }

    /** "Go to episode": switch section and focus the episode (also in another season). */
    fun doJump() {
        val n0 = jumpText.trim().toIntOrNull() ?: return
        jumpOpen = false
        jumpText = ""
        // Number in the season, in another season or as total number across all seasons (e.g. 100)
        val tgt = EpisodeLogic.locate(item.episodes, season, n0)
        if (tgt == null) {
            Toast.makeText(ctx, Lang.tr("Only ${item.episodes.size} episodes are available"), Toast.LENGTH_SHORT).show()
            return
        }
        val (tSeason, n) = tgt
        if (tSeason != season) {
            pendingNum[0] = n
            targetIdx[0] = item.episodes.filter { it.season == tSeason }.sortedBy { it.num }.indexOfFirst { it.num == n }.coerceAtLeast(0)
            season = tSeason
            refocusEp = true
            focusTick++
            return
        }
        val idx = EpisodeLogic.indexOfNum(eps, n)
        if (idx < 0) return
        anchor = idx
        targetIdx[0] = idx
        refocusEp = true
        focusTick++
    }

    LaunchedEffect(jumpOpen) { if (jumpOpen) { delay(80); runCatching { jumpReq.requestFocus() } } }

    // Back from the player: highlight the episode that played last (list and section already stand there)
    LaunchedEffect(retSE) {
        if (retSE == null) return@LaunchedEffect
        delay(120)
        val idx = EpisodeLogic.indexOfNum(eps, retSE.second)
        if (idx >= 0 && eps.isNotEmpty()) { targetIdx[0] = idx; refocusEp = true; focusTick++ }
        else runCatching { playFocus.requestFocus() }
    }

    LaunchedEffect(season, gi, focusTick) {
        seasonRowState.scrollToItem((seasons.indexOf(season) - 2).coerceAtLeast(0))
        epListState.scrollToItem(0)
        if (groups.size > 1) groupRowState.scrollToItem((gi - 2).coerceAtLeast(0))
        if (refocusEp) {
            refocusEp = false
            val g = group ?: return@LaunchedEffect
            val t = targetIdx[0]
            targetIdx[0] = -1
            val idx = (if (t < 0) g.from else t).coerceIn(g.from, g.to)
            epFocusIdx[0] = idx
            val row = idx - g.from
            withFrameNanos { }
            // Put the episodes area at the end of the page and make the target episode visible in the episode list
            listState.scrollToItem(EP_PANE)
            epListState.scrollToItem((row - 1).coerceAtLeast(0))
            // Try several times: the new row may only be there after a frame or two
            focusedIdx = -1
            for (k in 0 until 10) {
                withFrameNanos { }
                if (focusedIdx == idx) break
                runCatching { epReq(idx).requestFocus() }
                delay(30)
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Bg)) {
        // Round 15: layout like the big picture of the home screen (image on the right, faded into the background at the left and bottom)
        // Picture, gradients and mood glow in one cached layer: painted once, not on every frame while the page scrolls
        // Same as the Mac app: the picture is a rounded card at the top of the page (it scrolls with the page), no glow
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(start = dpx(28), end = dpx(28), top = dpx(18), bottom = 36.dp)) {
            item {
                Box(
                    Modifier.fillMaxWidth().heightIn(min = dpx(if (embedded) 420 else 640))
                        .onPreviewKeyEvent { ev ->
                            if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionUp) {
                                if (onTopUp != null) {
                                    // Topmost control in the header ("Read more", otherwise the first button row): push the title row
                                    // down again. This is decided here itself, not via the focus search (which found
                                    // invisible targets in the shifted title row).
                                    val moreShown = showActions && (descOverflow || descExpanded) && (descExpanded || (meta?.overview?.length ?: 0) > FULL_TEXT_CHARS)
                                    if (topMoreFocused || (!moreShown && topRowFocused)) { onTopUp(); return@onPreviewKeyEvent true }
                                    return@onPreviewKeyEvent false
                                }
                                scope.launch { listState.scrollToItem(0) }
                            }
                            false
                        }
                        .onFocusChanged { heroFocused = it.hasFocus }
                ) {
                    Box(Modifier.matchParentSize().clip(RoundedCornerShape(dpx(22))).background(Color(0xFF111111))
                        .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }) {
                        meta?.backdrop?.let { AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter) }
                        // Trailer fades in over the picture after a few seconds on the page's buttons (muted, never focused)
                        if (showActions) HeroTrailer(meta?.trailerKey, heroFocused, Modifier.fillMaxSize())
                        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color(0xD9000000), 0.4f to Color(0x66000000), 0.65f to Color.Transparent)))
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xD9000000))))
                    }
                    Column(Modifier.align(if (embedded && !showActions) Alignment.TopStart else Alignment.BottomStart).widthIn(max = dpx(1030))
                        .padding(start = dpx(52), top = dpx(40), bottom = dpx(46)), verticalArrangement = Arrangement.spacedBy(dpx(12))) {
                        // Cinematic detail identity stays consistent with Home, Player and Sports.
                        // Netflix-style: the official title logo when there is one, otherwise the title in large type
                        HeroTitle(item, meta, 84, dpx(620), dpx(170))
                        // Netflix metadata line: green match, then year · genre · length, quality badge
                        Row(horizontalArrangement = Arrangement.spacedBy(dpx(14)), verticalAlignment = Alignment.CenterVertically) {
                            meta?.let { mm -> matchPercent(ctx, mm)?.let { Text("$it% Match", raw = true, color = Color(0xFF46D369), fontSize = spx(19), fontWeight = FontWeight.Bold) } }
                            HeroMetaLine(item, meta, dots = false)
                            qualityOf(item)?.let { PreviewQualityBadge(it) }
                            if (item.id in Top10.ids) Top10Tag()
                        }
                        // Movie started: small progress bar with remaining time
                        if (item.kind != Kind.SERIES && resume != null && resume.dur > 0 && !ResumeLogic.finished(resume.pos, resume.dur)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(12))) {
                                Text("CONTINUE WATCHING", raw = true, color = FofVisual.Violet,
                                    fontSize = spx(12), fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                                Box(Modifier.width(dpx(190)).height(dpx(5)).clip(RoundedCornerShape(50)).background(Color(0x40FFFFFF))) {
                                    Box(Modifier.fillMaxWidth((resume.pos.toFloat() / resume.dur).coerceIn(0f, 1f)).fillMaxHeight().background(FofVisual.Violet))
                                }
                                Text(((resume.dur - resume.pos) / 60000).coerceAtLeast(1).toString() + " min left",
                                    raw = true, color = Tok.TextMid, fontSize = spx(14))
                            }
                        }
                        if (meta?.overview == null && !metaDone) {
                            // Loading skeleton instead of briefly "No description found."
                            val sk = shimmerColor()
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                SkeletonBox(Modifier.fillMaxWidth(0.92f).height(16.dp), sk)
                                SkeletonBox(Modifier.fillMaxWidth(0.86f).height(16.dp), sk)
                                SkeletonBox(Modifier.fillMaxWidth(0.6f).height(16.dp), sk)
                            }
                        } else Text(
                            raw = meta?.overview != null,
                            text = meta?.overview ?: if (MetaRepo.usable(item)) "No description is available." else "Tip: Add a TMDB API key under Settings > API Keys to show descriptions, artwork, and ratings.",
                            color = Tok.TextMid, fontSize = spx(17), lineHeight = spx(25),
                            // Short and medium descriptions (up to about 800 characters) are shown completely, without "Read more".
                            // In the cinema preview (without buttons) it is at most 6 lines.
                            maxLines = if (descExpanded || (showActions && (meta?.overview?.length ?: 0) <= FULL_TEXT_CHARS)) Int.MAX_VALUE
                                else if (!showActions) 8 else 4,
                            overflow = TextOverflow.Ellipsis,
                            onTextLayout = { if (!descExpanded) descOverflow = it.hasVisualOverflow }
                        )
                        if (showActions && (descOverflow || descExpanded) && (descExpanded || (meta?.overview?.length ?: 0) > FULL_TEXT_CHARS)) {
                            DetailPill(if (descExpanded) "Weniger" else "Read more", Modifier.onFocusChanged { topMoreFocused = it.isFocused }, trailing = if (descExpanded) AIcon.Up else AIcon.Down) { descExpanded = !descExpanded }
                        }
                        if (showActions) {
                        Spacer(Modifier.height(4.dp))
                        // Buttons in the style of the big picture, smaller (round 15). Padding: the enlarged button would otherwise be cut off.
                        // Two rows: Play/Resume/From start on top, below List, Favorite, Trailer, External. So nothing is
                        // cut off at the right edge any more. Padding: the enlarged button would otherwise be cropped.
                        Column(
                            Modifier.offset(x = (-8).dp).padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(dpx(12))
                        ) {
                            val bh = 44
                            Row(Modifier.onFocusChanged { topRowFocused = it.hasFocus }, horizontalArrangement = Arrangement.spacedBy(dpx(12))) {
                                if (resume != null) {
                                    val oneSeason = seasons.size <= 1
                                    val label = when {
                                        item.kind != Kind.SERIES -> "Resume · " + (resume.pos / 60000) + " min"
                                        resumeSE == null -> "Resume"
                                        resume.next -> "Up next: " + (if (oneSeason) "Episode ${resumeSE.second}" else "S${resumeSE.first} · Episode ${resumeSE.second}")
                                        else -> "Fortsetzen · " + (if (oneSeason) "Episode ${resumeSE.second}" else "S${resumeSE.first} · Episode ${resumeSE.second}")
                                    }
                                    HeroButton(label, AIcon.Play, true, Modifier.focusRequester(playFocus), bh) {
                                        // Use the exact currently displayed episode. Never feed
                                        // a missing or known-dead saved link into the player.
                                        val t = WatchStore.target(ctx, item, resume)
                                        if (t.second.isBlank() || DeadLinks.isDead(t.second)) {
                                            val se = resumeSE
                                            if (se != null) stremioAt = se
                                            AppNav.autoResume = Triple(resume.season, resume.ep, resume.pos)
                                            AppNav.autoResumeId = item.id
                                            stremioAuto = true
                                            showStremio = true
                                        } else onPlay(t.second, t.first, resume.pos)
                                    }
                                    HeroButton("Start over", AIcon.Rewind, false, Modifier, bh) { playUrl?.let { onPlay(it, item.title + (if (item.kind == Kind.SERIES) " S" + season + "E" + (eps.firstOrNull()?.num ?: 1) else ""), 0L) } }
                                } else {
                                    // Watched movie: "Play again" instead of "Play"
                                    val seenMovie = item.kind == Kind.MOVIE && progress[ResumeLogic.key(0, 0)]?.done == true
                                    HeroButton(if (seenMovie) "Play again" else "Play", if (seenMovie) AIcon.Rewind else AIcon.Play, true, Modifier.focusRequester(playFocus), bh) {
                                        // Playlist link known as broken (or a Stremio-only title): offer the addon streams instead
                                        if ((playUrl.isNullOrBlank() || DeadLinks.isDead(playUrl)) && Stremio.addons.isNotEmpty()) { stremioAuto = true; showStremio = true; return@HeroButton }
                                        playUrl?.let { onPlay(it, item.title + (if (item.kind == Kind.SERIES) " S" + season + "E" + (eps.firstOrNull()?.num ?: 1) else ""), 0L) }
                                    }
                                }
                                // Fof TV: one calm row – Play, My List, Trailer, More. Everything used rarely is in "More"
                                val inList = Library.isWatch(item.id)
                                HeroButton(if (inList) "In My List" else "My List", if (inList) AIcon.Check else AIcon.Plus, false, Modifier, bh) { Library.toggleWatch(ctx, item.id) }
                                // Netflix: Rate (cycles Like → Not for me → not rated) and the trailer right in the main row
                                var rated by remember(item.id) { mutableIntStateOf(Taste.rating(ctx, item.id)) }
                                HeroButton(when (rated) { 1 -> "Liked"; -1 -> "Not for me"; else -> "Rate" }, if (rated == 1) AIcon.HeartFilled else AIcon.Heart, false, Modifier, bh) {
                                    rated = when (rated) { 0 -> 1; 1 -> -1; else -> 0 }
                                    Taste.rate(ctx, item.id, rated)
                                }
                                if (meta?.trailerKey != null) HeroButton("Trailer", AIcon.Play, false, Modifier, bh) { openTrailer(ctx, meta?.trailerKey, item.title) }
                                // Keep addon sources one remote click away for every movie/TV title,
                                // even if a provider stream already exists.
                                if (item.kind != Kind.LIVE && Stremio.addons.any { it.streams }) {
                                    HeroButton("Sources", AIcon.Globe, false, Modifier, bh) {
                                        stremioAuto = false; showStremio = true
                                    }
                                }
                                if (item.kind == Kind.SERIES && item.id.startsWith("stremio:")) HeroButton(if (busy247) "Starting …" else "24/7", AIcon.Live, false, Modifier, bh) { if (!busy247) show247 = true }
                                HeroButton("More", AIcon.Gear, false, Modifier, bh) { showMore = true }
                            }
                            if (showStremio) {
                                val rs = resumeSE?.takeIf { it.first == season }
                                StremioSourcesPopup(
                                    item, imdbId ?: item.id.removePrefix("stremio:").takeIf { StremioLogic.isImdb(it) },
                                    stremioAt?.first ?: season, stremioAt?.second ?: rs?.second ?: eps.firstOrNull()?.num ?: 1,
                                    { u, t, ms ->
                                        // Addon stream of a title with a saved spot: start there (the link itself is new)
                                        val saved = AppNav.autoResume?.takeIf { AppNav.autoResumeId == item.id }?.third?.takeIf { it > 0 }
                                            // Exact episode ("S4E1" must not match "S4E10")
                                            ?: WatchStore.entries.firstOrNull { it.itemId == item.id && (item.kind != Kind.SERIES || Regex("\\bS" + it.season + "E" + it.ep + "\\b").containsMatchIn(t)) }
                                                ?.takeIf { !ResumeLogic.finished(it.pos, it.dur) }?.pos
                                        AppNav.autoResume = null; AppNav.autoResumeId = null
                                        onPlay(u, t, if (ms > 0) ms else saved ?: 0L)
                                    }, { showStremio = false; stremioAt = null }, auto = stremioAuto
                                )
                            }
                            if (show247) OptionPopup(listOf("Start from beginning", "Continue from last episode", "Shuffle episodes"), -1, { show247 = false }) { a ->
                                show247 = false
                                busy247 = true
                                scope.launch {
                                    val p = Marathon.start(ctx, item, shuffle = a == 2, continueFromLast = a == 1)
                                    busy247 = false
                                    if (p != null) onPlay(p.url, p.title, 0L)
                                    else android.widget.Toast.makeText(ctx, "No episode of this show would play right now.", android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                            if (showMore) {
                                // A finished resume point would otherwise keep offering "Continue"
                                fun dropResume(keys: Collection<Long>) {
                                    if (resume == null) return
                                    val rk = resumeSE?.let { ResumeLogic.key(it.first, it.second) }
                                    if (item.kind != Kind.SERIES || rk in keys) WatchStore.remove(ctx, item.id)
                                }
                                val acts = ArrayList<Triple<String, AIcon?, () -> Unit>>()
                                if (Stremio.addons.isNotEmpty() && item.kind != Kind.LIVE) acts.add(Triple("Choose a stream", AIcon.Globe) { stremioAuto = false; showStremio = true })
                                acts.add(Triple("Trailer", AIcon.Play) { openTrailer(ctx, meta?.trailerKey, item.title) })
                                val fav = Library.isFav(item.id)
                                acts.add(Triple(if (fav) "Remove from Favorites" else "Add to Favorites", if (fav) AIcon.HeartFilled else AIcon.Heart) { Library.toggleFav(ctx, item.id) })
                                if (!playUrl.isNullOrBlank()) acts.add(Triple("Open externally", AIcon.External) { playUrl?.let { openExternal(ctx, it, item.title) } })
                                if (item.kind == Kind.SERIES) {
                                    val seasonKeys = eps.map { ResumeLogic.key(it.season, it.num) }
                                    val seasonSeen = seasonKeys.isNotEmpty() && seasonKeys.all { progress[it]?.done == true }
                                    acts.add(Triple(if (seasonSeen) "Reset season" else "Mark season watched", if (seasonSeen) AIcon.Rewind else AIcon.Check) {
                                        EpProgress.mark(ctx, item.id, seasonKeys, !seasonSeen); if (!seasonSeen) dropResume(seasonKeys); progVer++
                                    })
                                    if (seasons.size > 1) {
                                        val allKeys = item.episodes.map { ResumeLogic.key(it.season, it.num) }
                                        val allSeen = allKeys.isNotEmpty() && allKeys.all { progress[it]?.done == true }
                                        acts.add(Triple(if (allSeen) "Reset series" else "Mark series watched", if (allSeen) AIcon.Rewind else AIcon.Check) {
                                            EpProgress.mark(ctx, item.id, allKeys, !allSeen); if (!allSeen) dropResume(allKeys); progVer++
                                        })
                                    }
                                } else if (item.kind == Kind.MOVIE) {
                                    val k = ResumeLogic.key(0, 0)
                                    val seen = progress[k]?.done == true
                                    acts.add(Triple(if (seen) "Mark unwatched" else "Mark watched", if (seen) AIcon.Rewind else AIcon.Check) {
                                        EpProgress.mark(ctx, item.id, listOf(k), !seen); if (!seen) dropResume(listOf(k)); progVer++
                                    })
                                }
                                if (item.id in History.ids) acts.add(Triple("Remove from Recently Watched", AIcon.Close) { History.remove(ctx, item.id) })
                                OptionPopup(acts.map { it.first }, -1, { showMore = false }, acts.map { it.second }) { i -> showMore = false; acts[i].third() }
                            }
                        }
                        if (item.kind == Kind.MOVIE && movieAlts.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            // Choose the source explicitly (takes precedence over quality) or automatically to suit the device
                            val so = listOf("Automatic") + movieVars.map { it.label }
                            DropdownPill("Source", prefLabel ?: "Automatic", so, so.indexOf(prefLabel).coerceAtLeast(0), active = prefLabel != null) {
                                prefLabel = if (it == 0) null else so[it]; save()
                            }
                            if (movieQs.size > 1 && prefLabel == null) {
                                val qo = listOf("Automatic (best for device)") + movieQs
                                DropdownPill("Quality", prefQ ?: "Automatic", qo, qo.indexOf(prefQ).coerceAtLeast(0), active = prefQ != null) {
                                    prefQ = if (it == 0) null else qo[it]; save()
                                }
                            }
                            Text("${movieAlts.size} sources · automatically switches if one fails", color = Muted, fontSize = 13.sp)
                        }
                        if (item.kind != Kind.SERIES && DeadLinks.isDead(item.url)) IconLabel(
                            AIcon.Warning,
                            if (movieAlts.size > 1) "This source failed recently. FOF TV will try another one."
                            else "This source failed recently.",
                            DS.Gold, 14.sp, iconSize = 16.dp, maxLines = 3
                        )
                        if (multi) Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (qualities.size > 1) {
                                val qo = listOf("Automatic (best for device)") + qualities
                                DropdownPill("Quality", prefQ ?: "Automatic", qo, qo.indexOf(prefQ).coerceAtLeast(0), active = prefQ != null) {
                                    prefQ = if (it == 0) null else qo[it]; save()
                                }
                            }
                            if (langs.size > 1) {
                                val lo = listOf("Automatic") + langs
                                DropdownPill("Language", prefLang ?: "Automatic", lo, lo.indexOf(prefLang).coerceAtLeast(0), active = prefLang != null) {
                                    prefLang = if (it == 0) null else lo[it]; save()
                                }
                            }
                            if (labels.size > 1) {
                                val vo = listOf("Automatic") + labels
                                DropdownPill("Source", prefLabel ?: "Automatic", vo, vo.indexOf(prefLabel).coerceAtLeast(0), active = prefLabel != null) {
                                    prefLabel = if (it == 0) null else vo[it]; save()
                                }
                            }
                            Text("Applies to all episodes", color = Muted, fontSize = 13.sp)
                        }
                        credits?.directors?.takeIf { it.isNotEmpty() }?.let { ds ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Director", color = Muted, fontSize = 14.sp)
                                ds.take(3).forEach { d -> DetailPill(d.name) { onPerson(PersonRef(d.id, d.name, d.photo, item.id)) } }
                            }
                        }
                        }
                    }
                }
            }
            if (item.kind == Kind.SERIES) item(key = "eppane") {
                // Episodes area (round 16): seasons on the left with the shown season marked, episodes on the right.
                // Right in the episode list = next season, Left = to the season selection.
                Row(
                    Modifier.fillMaxWidth().height(paneH).padding(top = dpx(16))
                        .onFocusChanged {
                            if (it.hasFocus && listState.firstVisibleItemIndex < EP_PANE)
                                scope.launch { listState.animateScrollToItem(EP_PANE) }
                        }
                        .focusProperties { enterTo { seasonReq } }
                        .focusGroup(),
                    horizontalArrangement = Arrangement.spacedBy(dpx(14))
                ) {
                    LazyColumn(
                        Modifier.width(dpx(200)).fillMaxHeight(),
                        state = seasonRowState,
                        verticalArrangement = Arrangement.spacedBy(dpx(3)),
                        contentPadding = PaddingValues(vertical = dpx(6))
                    ) {
                        items(seasons, key = { it }) { sn ->
                            val cnt = remember(item.id, sn) { item.episodes.count { it.season == sn } }
                            SeasonEntry(
                                sn, cnt, sn == season,
                                if (sn == season) Modifier.focusRequester(seasonReq) else Modifier,
                                onRight = {
                                    // Right or OK: show the season and jump to the first episode (same season: to the last position)
                                    if (sn != season) { enterAtEnd[0] = false; startFirst[0] = true; season = sn; targetIdx[0] = -1 }
                                    else targetIdx[0] = epFocusIdx[0]
                                    refocusEp = true
                                    focusTick++
                                }
                            )
                        }
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Row(
                            Modifier.padding(bottom = dpx(10)),
                            horizontalArrangement = Arrangement.spacedBy(dpx(16)), verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Episodes", raw = true, color = Color.White, fontSize = spx(28),
                                fontWeight = FontWeight.Bold, letterSpacing = 0.sp)
                            Text("Season $season", raw = true, color = Color(0xFFBCBCBC), fontSize = spx(20), fontWeight = FontWeight.Medium)
                            // Fof TV: no permanent remote hints (clutter); only a short note while episode info loads
                            if (epLoading) Text("Updating episode info", color = Muted, fontSize = spx(13))
                            if (item.episodes.size > EpisodeLogic.LONG) DetailPill("Find episode", icon = AIcon.Down) { jumpOpen = !jumpOpen }
                        }
                        // Long season: sections (TMDB seasons or blocks of 50 episodes) instead of an endless list
                        if (groups.size > 1) {
                            LazyRow(
                                Modifier.offset(x = (-10).dp),
                                state = groupRowState,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                itemsIndexed(groups) { k, g ->
                                    DetailPill(
                                        EpisodeLogic.label(g),
                                        if (k == gi) Modifier.focusRequester(groupReq) else Modifier,
                                        light = k == gi
                                    ) { anchor = g.from; epFocusIdx[0] = g.from }
                                }
                            }
                        }
                        if (jumpOpen) Row(
                            Modifier.padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Field(
                                jumpText, { v -> jumpText = v.filter { it.isDigit() }.take(4) }, "Episode number",
                                Modifier.width(280.dp), inner = Modifier.focusRequester(jumpReq), number = true, onDone = { doJump() }
                            )
                            DetailPill("Go", light = true) { doJump() }
                            DetailPill("Cancel") { jumpOpen = false; jumpText = "" }
                        }
                        LazyColumn(Modifier.fillMaxWidth().weight(1f), state = epListState, contentPadding = PaddingValues(bottom = dpx(40))) {
                            itemsIndexed(shown, key = { _, ep -> "e${ep.season}_${ep.num}" }) { j, ep ->
                                val i = (group?.from ?: 0) + j
                                val em = metaOf(ep)
                                val v = pick(ep)
                                EpisodeRow(
                                    ep, em, v, progress[ResumeLogic.key(ep.season, ep.num)],
                                    Modifier
                                        .focusRequester(epReq(i))
                                        .onPreviewKeyEvent { ev ->
                                            if (ev.type != KeyEventType.KeyDown) false
                                            else when (ev.key) {
                                                // Left: to the season selection; Right: next section, at the end of the season the next season
                                                Key.DirectionLeft -> { if (ev.nativeKeyEvent.repeatCount == 0) { if (groups.size > 1 && gi > 0) switchGroup(-1) else focusActiveSeason() }; true }
                                                Key.DirectionRight -> { if (ev.nativeKeyEvent.repeatCount == 0) switchGroup(1); true }
                                                Key.DirectionUp -> if (j == 0 && groups.size > 1) { focusActiveGroup(); true } else false
                                                else -> false
                                            }
                                        },
                                    onFocus = { epFocusIdx[0] = i; focusedIdx = i },
                                    onToggleSeen = {
                                        val k = ResumeLogic.key(ep.season, ep.num)
                                        val seen = progress[k]?.done == true
                                        EpProgress.mark(ctx, item.id, listOf(k), !seen)
                                        if (!seen && resumeSE?.let { ResumeLogic.key(it.first, it.second) } == k) WatchStore.remove(ctx, item.id)
                                        progVer++
                                    }
                                ) {
                                    if (v.url.isBlank()) { stremioAt = ep.season to ep.num; stremioAuto = true; showStremio = true }
                                    else onPlay(v.url, item.title + " S" + ep.season + "E" + ep.num, 0L)
                                }
                            }
                        }
                    }
                }
            }
            credits?.let { c ->
                if (c.cast.isNotEmpty()) item(key = "cast") { CastRow(c.cast, castState, if (memKey?.startsWith("c:") == true) memKey.drop(2).toIntOrNull() else null, restoreReq) { p -> DetailFocus.last[item.id] = "c:" + p.id; onPerson(PersonRef(p.id, p.name, p.photo, item.id)) } }
            }
            if (credits == null && (meta?.tmdbId ?: 0) > 0 && CreditsRepo.lastError != null) item(key = "cerr") {
                Text("Cast unavailable: ${CreditsRepo.lastError}", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 24.dp))
            }
            if (reviews.isNotEmpty()) item(key = "reviews") { ReviewsRow(reviews) }
            if (alsoLike.isNotEmpty()) item(key = "similar") { SimilarRow("You Might Also Like", alsoLike, simState, if (memKey?.startsWith("s:") == true) memKey.drop(2) else null, restoreReq) { i -> DetailFocus.last[item.id] = "s:" + i.id; onOpenItem(i) } }
            if (MetaRepo.enabled()) item {
                Text("This product uses the TMDB API but is not endorsed or certified by TMDB.",
                    color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 32.dp))
            }
        }
    }
}

@Composable
private fun EpisodeRow(ep: Episode, em: EpMeta?, v: Variant, prog: ResumeLogic.EpPos?, modifier: Modifier, onFocus: () -> Unit, onToggleSeen: () -> Unit, onClick: () -> Unit) {
    // Netflix-style episode row: big episode number, 16:9 still with a red progress line, title with the length on the
    // right, two lines of description. Flat; the selected row gets a soft light surface (no frame, no lift).
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(dpx(8))
    Row(
        modifier.fillMaxWidth().padding(vertical = dpx(3))
            .clip(shape).background(if (f) Color(0x29FFFFFF) else Color.Transparent)
            .onFocusChanged { f = it.isFocused; if (it.isFocused) onFocus() }
            .favKeys(onToggleSeen)
            .clickable(onClick = onClick)
            .padding(horizontal = dpx(14), vertical = dpx(14)),
        horizontalArrangement = Arrangement.spacedBy(dpx(22)), verticalAlignment = Alignment.CenterVertically
    ) {
        Text(raw = true, text = ep.num.toString(), color = if (f) Color.White else Color(0xFF8C8C8C), fontSize = spx(30),
            fontWeight = FontWeight.Medium, modifier = Modifier.width(dpx(44)), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Box(
            Modifier.width(dpx(214)).height(dpx(120)).clip(RoundedCornerShape(dpx(6))).background(Color(0xFF1F1F1F)),
            contentAlignment = Alignment.Center
        ) {
            if (em?.still != null) {
                // No spoilers: an episode not watched yet shows a blurred picture, sharp again once watched
                val hide = FofPrefs.spoilerPics && prog?.done != true
                if (hide) {
                    val ctxI = androidx.compose.ui.platform.LocalContext.current
                    val req = remember(em.still) { coil.request.ImageRequest.Builder(ctxI).data(em.still).size(32, 18).build() }
                    AsyncImage(req, null, Modifier.fillMaxSize().blur(14.dp), contentScale = ContentScale.Crop)
                } else AsyncImage(em.still, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else BrandMark(Modifier.size(dpx(40)).graphicsLayer { alpha = 0.5f })
            // Selected: Netflix's round play button over the still
            if (f) Box(Modifier.align(Alignment.Center).size(dpx(46)).clip(CircleShape).background(Color(0x99000000))
                .border(2.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
                AppIcon(AIcon.Play, Color.White, size = dpx(20))
            }
            // Progress: red line at the bottom (full when watched)
            val frac = if (prog?.done == true) 1f else prog?.fraction ?: 0f
            if (frac > 0.02f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(dpx(4)).background(Color(0x80808080))) {
                Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(Color(0xFFE60023)))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(dpx(8))) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(10))) {
                Text(raw = true, text = em?.name ?: ep.title, color = Color.White, fontSize = spx(20), fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (prog?.done == true) AppIcon(AIcon.Check, Color(0xFF46D369), size = dpx(18))
                v.quality?.let { QualityBadge(it) }
                em?.runtime?.let { Text(raw = true, text = if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m", color = Color(0xFFD2D2D2), fontSize = spx(18), fontWeight = FontWeight.Medium) }
            }
            em?.overview?.takeIf { !(FofPrefs.spoilerText && prog?.done != true) }?.let {
                Text(raw = true, text = it, color = Color(0xFFBCBCBC), fontSize = spx(16), lineHeight = spx(23), maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            // Fof touch: the air date in small type
            em?.air?.let { Text(raw = true, text = it, color = Color(0xFF7A7A7A), fontSize = spx(13)) }
        }
    }
}

/** Season list entry (Netflix): plain text, the shown season bright and bold with its episode count, focus = white capsule. */
@Composable
private fun SeasonEntry(season: Int, count: Int, selected: Boolean, modifier: Modifier, onRight: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(dpx(6))
    Column(
        modifier.fillMaxWidth().clip(shape)
            .background(if (f) Color.White else Color.Transparent)
            .onFocusChanged { f = it.isFocused }
            .onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionRight) { if (ev.nativeKeyEvent.repeatCount == 0) onRight(); true } else false
            }
            .clickable(onClick = onRight)
            .padding(horizontal = dpx(14), vertical = dpx(10))
    ) {
        Text("Season $season", color = if (f) Color.Black else if (selected) Color.White else Color(0xFF8C8C8C),
            fontSize = spx(if (selected) 21 else 19), fontWeight = if (selected || f) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
        if (selected) Text(text = if (count == 1) "1 episode" else "$count episodes", color = if (f) Color(0xFF444444) else Color(0xFFE60023),
            fontSize = spx(13), fontWeight = FontWeight.SemiBold)
    }
}


/** TryBox-style reviews: glass cards with avatar, name, date, score and three lines; OK opens the whole review. */
@Composable
private fun ReviewsRow(list: List<TmdbExtra.Review>) {
    var open by remember { mutableStateOf<TmdbExtra.Review?>(null) }
    Column(Modifier.padding(top = 26.dp)) {
        Text("Reviews", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, raw = true)
        LazyRow(
            Modifier.offset(x = -RowEdge),
            contentPadding = PaddingValues(horizontal = RowEdge, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(list.size) { i -> ReviewCard(list[i]) { open = list[i] } }
        }
    }
    open?.let { r ->
        androidx.compose.ui.window.Dialog(onDismissRequest = { open = null }) {
            val f = remember { FocusRequester() }
            LaunchedEffect(Unit) { delay(60); runCatching { f.requestFocus() } }
            Column(Modifier.width(760.dp).heightIn(max = 560.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xF2141416))
                .border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(18.dp)).padding(28.dp)
                .focusRequester(f).focusable().verticalScroll(androidx.compose.foundation.rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                ReviewHead(r, big = true)
                Text(r.text, color = Color(0xE6FFFFFF), fontSize = 18.sp, lineHeight = 27.sp, raw = true)
            }
        }
    }
}

@Composable
private fun ReviewHead(r: TmdbExtra.Review, big: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val sz = if (big) 46.dp else 38.dp
        Box(Modifier.size(sz).clip(CircleShape).background(Color(0x33FFFFFF)), contentAlignment = Alignment.Center) {
            Text(r.author.take(1).uppercase(), color = Color.White, fontSize = if (big) 20.sp else 16.sp, fontWeight = FontWeight.Bold, raw = true)
            r.avatar?.let { AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
        Column(Modifier.weight(1f)) {
            Text(r.author, color = Color.White, fontSize = if (big) 19.sp else 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, raw = true)
            Text(reviewDate(r.date), color = Color(0x99FFFFFF), fontSize = 13.sp, maxLines = 1, raw = true)
        }
        r.rating?.let {
            Row(Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0x26FFFFFF)).padding(horizontal = 9.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                AppIcon(AIcon.Star, Color(0xFF46D369), size = 14.dp)
                Text(String.format("%.0f", it), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, raw = true)
                Text("/ 10", color = Color(0x99FFFFFF), fontSize = 12.sp, raw = true)
            }
        }
    }
}

@Composable
private fun ReviewCard(r: TmdbExtra.Review, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.width(400.dp).height(200.dp).clip(shape)
            .background(if (f) Color(0x2EFFFFFF) else Color(0x14FFFFFF))
            .border(if (f) 2.dp else 1.dp, if (f) Color.White else Color(0x1AFFFFFF), shape)
            .onFocusChanged { f = it.isFocused }.clickable(onClick = onClick).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ReviewHead(r)
        Text(r.text, color = Color(0xCCFFFFFF), fontSize = 15.sp, lineHeight = 22.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, raw = true)
        Text("View more", color = Color(0x80FFFFFF), fontSize = 13.sp, raw = true)
    }
}

/** "2026-10-10" -> "Oct 10, 2026". */
private fun reviewDate(d: String): String = runCatching {
    val p = d.split("-"); val m = listOf("Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec")[p[1].toInt() - 1]
    "$m ${p[2].toInt()}, ${p[0]}"
}.getOrDefault(d)
