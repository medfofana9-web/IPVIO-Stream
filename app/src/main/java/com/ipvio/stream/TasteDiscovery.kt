package com.ipvio.stream

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Taste discovery while a profile is being created: a balanced start (every genre, several decades, popular and top
 * rated, documentaries, reality and animation) because nothing is known about the person yet; search reaches the
 * whole catalog; after every few ratings more titles of the genres they rate well are added, plus one genre they have
 * not touched, so the choice never narrows to one kind of title. OK on a poster cycles Love it → Like it → Not for me →
 * clear ("Haven't seen" = left alone).
 */
object Discovery {
    const val NONE = 0
    const val LOVED = 1
    const val LIKED = 2
    const val NOPE = 3

    class Pool(val items: List<Item>, val genresOf: Map<String, Set<String>>, val byGenre: Map<String, List<Item>>)

    /** The start pool of one kind: genre lists, decades, popular and top rated, mixed evenly. */
    suspend fun pool(c: android.content.Context, k: Kind): Pool = withContext(Dispatchers.IO) {
        Catalog.init(c)
        val type = if (k == Kind.SERIES) "series" else "movie"
        val decades = listOf(1985, 1995, 2005, 2015).map { Catalog.Spec("${it / 10 * 10}s", type, "year", it.toString()) }
        val specs = Catalog.genres(k) + listOf(Catalog.popular(k), Catalog.featured(k), Catalog.newest(k)) + decades
        val lists = coroutineScope {
            specs.map { s -> async { s.title to runCatching { Catalog.items(s, 2) }.getOrDefault(emptyList()).filter { !it.logo.isNullOrBlank() } } }.awaitAll()
        }.filter { it.second.isNotEmpty() }
        val genreNames = Catalog.genres(k).map { it.title }.toSet()
        val genresOf = HashMap<String, MutableSet<String>>()
        for ((g, l) in lists) if (g in genreNames) for (i in l) genresOf.getOrPut(i.id) { HashSet() }.add(g)
        Pool(DiscoveryLogic.balance(lists.map { it.second }, 48) { it.id }, genresOf, lists.filter { it.first in genreNames }.toMap())
    }
}

/** Pure parts of the discovery (tested). */
object DiscoveryLogic {
    /** Takes from each list in turn (no duplicates) until [n]: every genre/decade is represented from the start. */
    fun <T> balance(lists: List<List<T>>, n: Int, id: (T) -> String): List<T> {
        val out = ArrayList<T>(); val seen = HashSet<String>()
        var round = 0
        while (out.size < n && lists.any { it.size > round }) {
            for (l in lists) {
                val x = l.getOrNull(round) ?: continue
                if (seen.add(id(x))) out += x
                if (out.size >= n) break
            }
            round++
        }
        return out
    }

    /** Genres ranked by the ratings (loved 2, liked 1, not for me -1). */
    fun genreScores(ratings: Map<String, Int>, genresOf: Map<String, Set<String>>): List<Pair<String, Int>> {
        val s = HashMap<String, Int>()
        for ((id, r) in ratings) {
            val w = when (r) { Discovery.LOVED -> 2; Discovery.LIKED -> 1; Discovery.NOPE -> -1; else -> 0 }
            if (w == 0) continue
            for (g in genresOf[id].orEmpty()) s[g] = (s[g] ?: 0) + w
        }
        return s.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }

    /**
     * Next titles to add: mostly from the two best-rated genres, one from a genre not rated yet (exploration), never
     * one already shown.
     */
    fun <T> adapt(scores: List<Pair<String, Int>>, byGenre: Map<String, List<T>>, shown: Set<String>, id: (T) -> String, n: Int = 8): List<T> {
        val top = scores.filter { it.second > 0 }.take(2).map { it.first }
        if (top.isEmpty()) return emptyList()
        val fresh = byGenre.keys.filter { g -> scores.none { it.first == g } }
        val sources = top.map { byGenre[it].orEmpty() } + listOfNotNull(fresh.shuffled().firstOrNull()?.let { byGenre[it] })
        val out = ArrayList<T>(); val seen = HashSet(shown)
        var round = 0
        while (out.size < n && sources.any { it.size > round }) {
            for ((i, l) in sources.withIndex()) {
                // the exploration genre gives one title per round of two from the favorites
                if (i == sources.lastIndex && sources.size > top.size && round % 2 == 1) continue
                val x = l.getOrNull(round) ?: continue
                if (seen.add(id(x))) out += x
                if (out.size >= n) break
            }
            round++
        }
        return out
    }

    fun next(r: Int): Int = (r + 1) % 4
}

/** Poster grid with search, ratings and "more like this" growth. */
@Composable
internal fun DiscoverStep(kind: Kind, pool: Discovery.Pool?, ratings: MutableMap<String, Int>, items: MutableMap<String, Item>, onRated: () -> Unit) {
    val ctx = LocalContext.current
    if (pool == null) { Text("Loading titles …", color = Color(0x99FFFFFF), fontSize = 18.sp, raw = true); return }
    var query by remember(kind) { mutableStateOf("") }
    val shown = remember(kind, pool) { mutableStateListOf<Item>().apply { addAll(pool.items) } }
    var found by remember(kind) { mutableStateOf<List<Item>?>(null) }
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) { found = null; return@LaunchedEffect }
        delay(350)
        found = runCatching { Catalog.search(kind, q) }.getOrDefault(emptyList()).filter { !it.logo.isNullOrBlank() }
    }
    fun rate(i: Item) {
        val r = DiscoveryLogic.next(ratings[i.id] ?: Discovery.NONE)
        if (r == Discovery.NONE) ratings.remove(i.id) else ratings[i.id] = r
        items[i.id] = i
        if (r == Discovery.LOVED || r == Discovery.LIKED) CatalogItems.put(ctx, i)
        // Every third positive rating: more of what they like, plus something new
        val positive = ratings.values.count { it == Discovery.LOVED || it == Discovery.LIKED }
        if (r == Discovery.LOVED && positive % 3 == 0) {
            val more = DiscoveryLogic.adapt(DiscoveryLogic.genreScores(ratings, pool.genresOf), pool.byGenre, shown.mapTo(HashSet()) { it.id }, { it.id })
            shown.addAll(more)
        }
        onRated()
    }
    val list = found ?: shown
    val first = remember(kind) { FocusRequester() }
    LaunchedEffect(kind) { repeat(10) { delay(60); if (runCatching { first.requestFocus() }.getOrDefault(false)) return@LaunchedEffect } }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Box(Modifier.width(420.dp)) { Field(query, { query = it }, if (kind == Kind.SERIES) "Search any show" else "Search any movie") }
            Text("OK once: Love it  ·  twice: Like it  ·  three times: Not for me", color = Color(0x99FFFFFF), fontSize = 15.sp, raw = true)
        }
        if (found != null && found!!.isEmpty()) Text("Nothing found for \"$query\".", color = Color(0x99FFFFFF), fontSize = 16.sp, raw = true)
        LazyVerticalGrid(GridCells.Fixed(7), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(22.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp, start = 6.dp, end = 6.dp)) {
            items(list, key = { it.id }) { i ->
                RatePoster(i, ratings[i.id] ?: Discovery.NONE, if (i === list.firstOrNull()) Modifier.focusRequester(first) else Modifier) { rate(i) }
            }
            if (found == null) item(key = "more", span = { GridItemSpan(maxLineSpan) }) {
                Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                    PillButton("Show more", raw = true) {
                        val have = shown.mapTo(HashSet()) { it.id }
                        shown.addAll(DiscoveryLogic.balance(pool.byGenre.values.map { l -> l.filter { it.id !in have } }, 21) { it.id })
                    }
                }
            }
        }
    }
}

@Composable
private fun RatePoster(i: Item, r: Int, modifier: Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (f) 1.06f else 1f, tween(140), label = "rp")
    val shape = RoundedCornerShape(12.dp)
    val (frame, label) = when (r) {
        Discovery.LOVED -> Color(0xFFB9B4F0) to "Love it"
        Discovery.LIKED -> Color(0xFF8FD3A8) to "Like it"
        Discovery.NOPE -> Color(0x66FFFFFF) to "Not for me"
        else -> Color(0x1FFFFFFF) to null
    }
    Column(modifier.graphicsLayer { scaleX = s; scaleY = s }.onFocusChanged { f = it.isFocused }.clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).focusGlow(f, shape).clip(shape).background(Color(0xFF15161A))
            .border(if (r == Discovery.LOVED || r == Discovery.LIKED) 3.dp else 1.dp, frame, shape)) {
            coil.compose.AsyncImage(i.logo, i.title, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            if (r == Discovery.NOPE) Box(Modifier.fillMaxSize().background(Color(0xB3000000)))
            if (r == Discovery.LOVED) Box(Modifier.fillMaxSize().background(Color(0x337B5CFF)))
            if (label != null) Box(Modifier.align(Alignment.BottomCenter).padding(8.dp).clip(RoundedCornerShape(50))
                .background(if (r == Discovery.NOPE) Color(0xCC1C1C20) else Color.White).padding(horizontal = 10.dp, vertical = 4.dp)) {
                Text(label, color = if (r == Discovery.NOPE) Color.White else Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold, raw = true)
            }
        }
        Text(i.title, color = if (f || r != Discovery.NONE) Color.White else Color(0x99FFFFFF), fontSize = 14.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, raw = true, modifier = Modifier.padding(top = 8.dp, start = 2.dp))
    }
}

/** Viewing preferences: one quick choice per question (all optional). */
internal val PREF_QUESTIONS = listOf(
    "mix" to listOf("Mostly movies", "Both", "Mostly shows"),
    "mood" to listOf("Make me laugh", "A bit of both", "Serious drama"),
    "pace" to listOf("Fast and exciting", "Either", "Slow burn"),
    "world" to listOf("Real life", "Either", "Fantasy and sci-fi"),
    "docs" to listOf("Love documentaries", "Sometimes", "Rarely"),
    "reality" to listOf("Love reality TV", "Sometimes", "Not for me"),
    "subs" to listOf("Subtitles are fine", "English audio only"),
    "length" to listOf("Short episodes", "Any length", "Long episodes")
)
internal val PREF_TITLES = mapOf(
    "mix" to "Movies or shows?", "mood" to "Your mood", "pace" to "Story pace", "world" to "Kind of world",
    "docs" to "Documentaries", "reality" to "Reality TV", "subs" to "Other languages", "length" to "Episode length"
)

@Composable
internal fun PrefsStep(prefs: MutableMap<String, String>, onChange: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { repeat(8) { delay(60); if (runCatching { first.requestFocus() }.getOrDefault(false)) return@LaunchedEffect } }
    LazyVerticalGrid(GridCells.Fixed(2), horizontalArrangement = Arrangement.spacedBy(40.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        items(PREF_QUESTIONS, key = { it.first }) { (k, opts) ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(PREF_TITLES[k].orEmpty(), color = Color(0xB3FFFFFF), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, raw = true)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    opts.forEachIndexed { idx, o ->
                        val on = prefs[k] == o
                        var f by remember { mutableStateOf(false) }
                        val shape = RoundedCornerShape(50)
                        Box(
                            (if (k == PREF_QUESTIONS.first().first && idx == 0) Modifier.focusRequester(first) else Modifier)
                                .onFocusChanged { f = it.isFocused }
                                .then(if (on) Modifier.clip(shape).background(Color.White) else Modifier.glass(shape, 0, f))
                                .clickable { if (on) prefs.remove(k) else prefs[k] = o; onChange() }
                                .padding(horizontal = 18.dp, vertical = 10.dp)
                        ) { Text(o, color = if (on) Color.Black else Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, raw = true) }
                    }
                }
            }
        }
    }
}

/**
 * Home rows from the profile's own setup answers (real catalog titles of the genres its ratings point to; titles it
 * rated "Not for me", already likes or watched are left out). Empty for a profile that skipped the setup: it gets the
 * normal, varied rows instead of anybody else's taste.
 */
object TasteRows {
    private suspend fun genreItems(k: Kind, g: String): List<Item> =
        Catalog.genres(k).firstOrNull { it.title.equals(g, true) }?.let { s -> runCatching { Catalog.items(s, 2) }.getOrDefault(emptyList()) }.orEmpty()

    private fun skip(c: android.content.Context): Set<String> = Taste.nope(c) + Taste.titles(c) + History.ids

    suspend fun forKind(c: android.content.Context, k: Kind, top: List<String>): List<Item> {
        val sk = skip(c)
        return DiscoveryLogic.balance(top.map { g -> genreItems(k, g).filter { it.id !in sk } }, 30) { it.id }
    }

    suspend fun picked(c: android.content.Context, top: List<String>): List<Item> {
        // "Movies or shows?" decides which kind leads
        val mix = Taste.pref(c, "mix")
        val m = forKind(c, Kind.MOVIE, top); val s = forKind(c, Kind.SERIES, top)
        val order = if (mix == "Mostly shows") listOf(s, m) else listOf(m, s)
        return DiscoveryLogic.balance(order, 30) { it.id }
    }

    /** Genres the profile has not shown interest in (documentaries and reality only when the answers allow them). */
    suspend fun discover(c: android.content.Context, top: List<String>): List<Item> {
        val sk = skip(c)
        val noDocs = Taste.pref(c, "docs") == "Rarely"
        val others = Catalog.genres(Kind.MOVIE).map { it.title }.filter { g -> top.none { it.equals(g, true) } && !(noDocs && g == "Documentary") }.shuffled().take(4)
        return DiscoveryLogic.balance(others.map { g -> genreItems(Kind.MOVIE, g).filter { it.id !in sk } }, 30) { it.id }
    }
}
