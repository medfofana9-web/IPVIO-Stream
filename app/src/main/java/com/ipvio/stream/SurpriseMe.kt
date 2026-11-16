package com.ipvio.stream

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

/** Pure pick logic for Surprise Me (no Android imports, unit-tested). */
object SurpriseLogic {
    class Cand(val id: String, val title: String, val genres: List<String>, val runtime: Int?, val rating: Double?, val seen: Boolean, val source: String)

    /** [time]: 0 = any, 1 = under 100 min (movies). [fresh]: 0 = any, 1 = new to you, 2 = something I've seen. */
    fun filter(all: List<Cand>, genre: String?, time: Int, fresh: Int, nope: Set<String>, shown: Set<String>): List<Cand> =
        all.asSequence().distinctBy { it.id }
            .filter { it.id !in nope && it.id !in shown }
            .filter { genre == null || it.genres.any { g -> g.equals(genre, true) } }
            .filter { time == 0 || (it.runtime != null && it.runtime in 60..100) }
            .filter { fresh == 0 || (fresh == 1 && !it.seen) || (fresh == 2 && it.seen) }
            .toList()

    /** Weighted pick: taste-genre overlap and rating count, with randomness so "Another" really changes. */
    fun pick(c: List<Cand>, taste: List<String>, rnd: kotlin.random.Random): Cand? {
        if (c.isEmpty()) return null
        val w = c.map { x -> 1.0 + 2.0 * x.genres.count { g -> taste.any { it.equals(g, true) } } + ((x.rating ?: 6.0) - 6.0).coerceAtLeast(0.0) }
        var r = rnd.nextDouble() * w.sum()
        c.forEachIndexed { i, x -> r -= w[i]; if (r <= 0) return x }
        return c.last()
    }

    /** Why this pick, built only from facts we have. */
    fun why(x: Cand, taste: List<String>): String {
        val parts = ArrayList<String>()
        x.genres.firstOrNull { g -> taste.any { it.equals(g, true) } }?.let { parts += "You like $it" }
        x.rating?.takeIf { it >= 7.5 }?.let { parts += "rated %.1f".format(java.util.Locale.US, it) }
        parts += x.source
        x.runtime?.let { parts += "$it min" }
        if (x.seen) parts += "you've seen it before"
        return parts.joinToString(" · ").replaceFirstChar { it.uppercase() }
    }
}

/** "Surprise me": optional filters, one pick with a reason, Play / Details / Another. */
@Composable
fun SurpriseMeDialog(onOpen: (Item) -> Unit, onPlay: (Item) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    BackHandler { onClose() }
    var kind by remember { mutableIntStateOf(0) }    // 0 movie, 1 show
    var genre by remember { mutableStateOf<String?>(null) }
    var time by remember { mutableIntStateOf(0) }
    var fresh by remember { mutableIntStateOf(1) }
    var nonce by remember { mutableIntStateOf(0) }
    val shown = remember { HashSet<String>() }
    var pick by remember { mutableStateOf<Pair<Item, String>?>(null) }
    var loading by remember { mutableStateOf(true) }
    val taste = remember { Taste.topGenres(ctx) }
    val k = if (kind == 1) Kind.SERIES else Kind.MOVIE
    val genres = remember(k) { Catalog.genres(k).map { it.title } }
    LaunchedEffect(kind, genre, time, fresh, nonce) {
        loading = true
        val specs = buildList {
            add(Catalog.popular(k) to "popular right now"); add(Catalog.featured(k) to "top rated")
            genre?.let { g -> Catalog.genres(k).firstOrNull { it.title == g }?.let { add(it to "popular in $g") } }
        }
        val seen = History.ids.toSet()
        val items = HashMap<String, Item>()
        val cands = specs.flatMap { (s, label) ->
            runCatching { Catalog.items(s, 2) }.getOrDefault(emptyList()).map { i ->
                items[i.id] = i
                val m = Catalog.meta(i)
                SurpriseLogic.Cand(i.id, i.title, m?.genres.orEmpty(), m?.runtime, m?.imdbRating?.toDoubleOrNull() ?: m?.rating, i.id in seen, label)
            }
        }
        val pool = SurpriseLogic.filter(cands, genre, if (kind == 0) time else 0, fresh, Taste.nope(ctx), shown)
        val p = SurpriseLogic.pick(pool, taste, kotlin.random.Random(System.nanoTime()))
        pick = p?.let { c -> shown += c.id; items[c.id]!! to SurpriseLogic.why(c, taste) }
        loading = false
    }
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)).clickable { onClose() }, contentAlignment = Alignment.Center) {
        Column(Modifier.width(980.dp).glass(RoundedCornerShape(DS.RadiusL), 2).clickable(enabled = false) {}.padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Surprise me", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, raw = true)
            val first = remember { FocusRequester() }
            LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterPill("Movie", kind == 0, Modifier.focusRequester(first), raw = true) { kind = 0; genre = null }
                FilterPill("Show", kind == 1, raw = true) { kind = 1; genre = null }
                Spacer(Modifier.width(12.dp))
                if (kind == 0) {
                    FilterPill("Any length", time == 0, raw = true) { time = 0 }
                    FilterPill("Under 100 min", time == 1, raw = true) { time = 1 }
                    Spacer(Modifier.width(12.dp))
                }
                FilterPill("New to me", fresh == 1, raw = true) { fresh = 1 }
                FilterPill("Seen before", fresh == 2, raw = true) { fresh = 2 }
                FilterPill("Either", fresh == 0, raw = true) { fresh = 0 }
            }
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterPill("Any genre", genre == null, raw = true) { genre = null } }
                items(genres.size) { i -> val g = genres[i]; FilterPill(g, genre == g, raw = true) { genre = g } }
            }
            Row(Modifier.fillMaxWidth().height(300.dp), horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
                val p = pick
                when {
                    loading -> Text("Finding something …", color = Color(0xB3FFFFFF), fontSize = 18.sp, raw = true)
                    p == null -> Text("Nothing fits these filters. Try fewer filters.", color = Color(0xB3FFFFFF), fontSize = 18.sp, raw = true)
                    else -> {
                        val (item, why) = p
                        Box(Modifier.width(200.dp).fillMaxHeight().clip(RoundedCornerShape(DS.RadiusM)).background(Color(0x22FFFFFF))) {
                            if (item.logo != null) AsyncImage(item.logo, item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(item.title + (item.year?.let { y -> " ($y)" } ?: ""), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, raw = true, maxLines = 2)
                            Text(why, color = DS.Gold, fontSize = 16.sp, raw = true)
                            Catalog.meta(item)?.overview?.let { o -> Text(o, color = Color(0xCCFFFFFF), fontSize = 15.sp, raw = true, maxLines = 4) }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                PillButton("Play", light = true, icon = AIcon.Play, raw = true) { onClose(); onPlay(item) }
                                PillButton("Details", icon = AIcon.Info, raw = true) { onClose(); onOpen(item) }
                                PillButton("Another", icon = AIcon.Sparkle, raw = true) { nonce++ }
                            }
                        }
                    }
                }
            }
        }
    }
}
