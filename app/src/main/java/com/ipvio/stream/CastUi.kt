package com.ipvio.stream

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun PersonCard(p: Person, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var f by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (f) focusScale(1.08f) else 1f, label = "person")
    Column(
        modifier.width(116.dp).graphicsLayer { scaleX = s; scaleY = s }
            .onFocusChanged { f = it.isFocused }.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(104.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Surf2, Surf)))
                .border(3.dp, if (f) Accent2 else Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (p.photo != null) AsyncImage(p.photo, p.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Text(raw = true, text = p.name.split(" ").mapNotNull { it.firstOrNull() }.take(2).joinToString("").uppercase(), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
        Text(raw = true, text = p.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        p.role?.let { Text(raw = true, text = it, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center) }
    }
}

/**
 * Remembers which card (cast "c:ID" / similar title "s:ID") was last clicked on the detail page, so that
 * Back lands there instead of on "Resume" again. Read once and deleted when the page is reopened.
 */
object DetailFocus {
    val last = HashMap<String, String>()
}

// Row padding: otherwise the enlarged focused card is clipped at the edge of the row
internal val RowEdge = 16.dp

@Composable
fun CastRow(
    cast: List<Person>, state: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
    restoreId: Int? = null, restoreReq: FocusRequester? = null, onPerson: (Person) -> Unit
) {
    if (cast.isEmpty()) return
    Column(Modifier.padding(top = 30.dp)) {
        Text("Besetzung", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        LazyRow(
            Modifier.offset(x = -RowEdge), state = state,
            contentPadding = PaddingValues(horizontal = RowEdge, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(cast.distinctBy { it.id }, key = { it.id }) { p ->
                PersonCard(p, { onPerson(p) }, if (restoreReq != null && p.id == restoreId) Modifier.focusRequester(restoreReq) else Modifier)
            }
        }
    }
}

@Composable
fun SimilarRow(
    title: String, list: List<Item>, state: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
    restoreId: String? = null, restoreReq: FocusRequester? = null, onOpen: (Item) -> Unit
) {
    if (list.isEmpty()) return
    Column(Modifier.padding(top = 26.dp)) {
        Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        LazyRow(
            Modifier.offset(x = -RowEdge), state = state,
            contentPadding = PaddingValues(horizontal = RowEdge, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(list.distinctBy { it.id }, key = { it.id }) { i ->
                PosterCard(i, { onOpen(i) }, Modifier.width(150.dp).then(if (restoreReq != null && i.id == restoreId) Modifier.focusRequester(restoreReq) else Modifier))
            }
        }
    }
}

/**
 * Page of a person (Apple TV style, same as the Mac and phone): photo, name and role; born, birthplace and credits;
 * biography; "Known for" (their most popular titles); and their titles in your playlist as a grid (All, Movies, Shows).
 */
@Composable
fun PersonScreen(ref: PersonRef, all: List<Item>, onOpen: (Item) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var info by remember(ref.id) { mutableStateOf<PersonInfo?>(null) }
    var loaded by remember(ref.id) { mutableStateOf(false) }
    LaunchedEffect(ref.id) { info = CreditsRepo.person(ref.id); loaded = true }
    val owned by produceState(emptyList<Item>(), info, all) {
        val i = info ?: return@produceState
        value = withContext(Dispatchers.Default) {
            // The title they came from is always included (TMDB does not know every work for some people)
            val m = LibMatch.matches(all, i.titles, 80)
            val origin = ref.from?.let { id -> all.firstOrNull { it.id == id } }
            if (origin != null && m.none { it.id == origin.id }) listOf(origin) + m else m
        }
    }
    var expanded by remember(ref.id) { mutableStateOf(false) }
    var kind by remember(ref.id) { mutableIntStateOf(0) }   // 0 all, 1 movies, 2 shows
    val first = remember { FocusRequester() }
    LaunchedEffect(info != null) { if (info != null) { delay(150); runCatching { first.requestFocus() } } }
    val i = info
    val photo = i?.photo ?: ref.photo
    val panel = Color(0xFF141821)
    Box(Modifier.fillMaxSize().background(Color(0xFF0B0D12))) {
        // The portrait large on the right, fading into the page
        if (photo != null) AsyncImage(photo, null, Modifier.align(Alignment.TopEnd).fillMaxWidth(0.42f).fillMaxHeight(0.62f).graphicsLayer { alpha = 0.45f }, contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0.35f to Color(0xFF0B0D12), 0.85f to Color(0x660B0D12), 1f to Color(0x330B0D12))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 0.65f to Color(0xFF0B0D12))))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = DS.PageX, vertical = 40.dp)) {
            item(key = "head") {
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(width = 170.dp, height = 200.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(22.dp)).background(Surf)) {
                        if (photo != null) AsyncImage(photo, ref.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        else Text(raw = true, text = ref.name.take(1), color = Color.White, fontSize = 60.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.align(Alignment.Center))
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(raw = true, text = ref.name, color = Color.White, fontSize = 46.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 50.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val role = when (i?.dept) { "Directing" -> "Director"; "Acting" -> "Actor"; "Writing" -> "Writer"; null -> null; else -> i.dept }
                        if (role != null) Text(raw = true, text = role, color = Color(0xFFAAAAAA), fontSize = 18.sp)
                    }
                }
            }
            if (i == null) { item(key = "load") { Text(if (loaded) "Could not load this person." else "Loading…", color = Muted, fontSize = 16.sp, raw = true, modifier = Modifier.padding(top = 30.dp)) }; return@LazyColumn }
            item(key = "facts") {
                Row(Modifier.padding(top = 26.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.width(460.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).background(panel).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val since = i.titles.mapNotNull { it.year?.toIntOrNull() }.minOrNull()
                        listOfNotNull(i.born?.let { "Born" to it }, i.place?.let { "Birthplace" to it },
                            ("Credits" to "${i.titles.size} titles" + (since?.let { " · since $it" } ?: ""))).forEach { (k, v) ->
                            Row { Text(raw = true, text = k, color = Color(0xFF999999), fontSize = 16.sp, modifier = Modifier.width(120.dp)); Text(raw = true, text = v, color = Color.White, fontSize = 16.sp) }
                        }
                    }
                    if (i.bio != null) Column(Modifier.weight(1f).clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).background(panel).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Biography", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, raw = true)
                        Text(raw = true, text = i.bio, color = Color(0xFFCCCCCC), fontSize = 15.sp, lineHeight = 22.sp, maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis)
                        SmallPill(if (expanded) "Less" else "Read more", trailing = if (expanded) AIcon.Up else AIcon.Down) { expanded = !expanded }
                    }
                }
            }
            // Known for: their most popular titles (opens it when it is in your playlist)
            val known = i.titles.take(12)
            if (known.isNotEmpty()) item(key = "known") {
                Column(Modifier.padding(top = 30.dp)) {
                    Text("Known for", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, raw = true)
                    LazyRow(Modifier.offset(x = (-14).dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(known.size) { n ->
                            val r = known[n]
                            KnownCard(r, if (n == 0) Modifier.focusRequester(first) else Modifier) {
                                val hit = LibMatch.matches(all, listOf(r), 1).firstOrNull()
                                if (hit != null) onOpen(hit) else android.widget.Toast.makeText(ctx, r.title + " is not in your playlist", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
            // Filmography: their titles in your playlist
            item(key = "filmhead") {
                Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(raw = true, text = "Filmography · in your playlist", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.width(12.dp))
                    listOf("All", "Movies", "Shows").forEachIndexed { k, t -> FilterPill(t, kind == k, Modifier) { kind = k } }
                }
            }
            val shown = owned.filter { kind == 0 || (kind == 1 && it.kind == Kind.MOVIE) || (kind == 2 && it.kind == Kind.SERIES) }
            if (shown.isEmpty()) item(key = "none") { Text(if (owned.isEmpty()) "None of their titles are in your playlist." else "Nothing here.", color = Muted, fontSize = 15.sp, raw = true, modifier = Modifier.padding(top = 12.dp)) }
            shown.chunked(7).forEachIndexed { r, row ->
                item(key = "g$r") {
                    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        row.forEach { it -> PosterCard(it, { onOpen(it) }, Modifier.width(150.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun KnownCard(r: Ref, modifier: Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
    Column(modifier.width(150.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(shape).background(Surf)
            .border(if (f) 4.dp else 0.dp, if (f) Color.White else Color.Transparent, shape)
            .onFocusChanged { f = it.isFocused }.clickable(onClick = onClick)) {
            if (r.poster != null) AsyncImage(r.poster, r.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Text(raw = true, text = r.title, color = Color.White, fontSize = 15.sp, modifier = Modifier.align(Alignment.Center).padding(8.dp), textAlign = TextAlign.Center)
        }
        Text(raw = true, text = r.title, color = if (f) Color.White else Color(0xFFCCCCCC), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text(raw = true, text = listOfNotNull(r.year, if (r.isTv) "Series" else "Movie").joinToString(" · "), color = Color(0xFF999999), fontSize = 12.sp)
    }
}
