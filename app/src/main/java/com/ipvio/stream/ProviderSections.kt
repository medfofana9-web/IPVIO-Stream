package com.ipvio.stream

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Section and tile chosen last, kept while the app runs (Back from the player lands on the same tile). */
internal object ProviderMemory {
    var section: String? = null
    var tileId: String? = null
}

/**
 * "Your channels" in Sports (layout B): the provider's sports folders as chips, the chosen folder as a grid of tiles –
 * logo, name, and the state from the provider's own guide ("● Live · NBA: Pistons at Magic", "In 3 h · DAL @ DEN",
 * "Tomorrow", "No game today"). Live tiles first. OK plays the channel at once; Up/Down zaps through the same folder.
 * No game matching: this is the TiviMate way of reaching a team's feed.
 */
@Composable
fun ProviderSections(live: List<Item>, onPlay: (Item, List<Item>) -> Unit) {
    val sections by produceState(emptyList<Pair<String, List<Item>>>(), live) {
        value = withContext(Dispatchers.Default) {
            val m = LinkedHashMap<String, MutableList<Item>>()
            for (c in live) if (c.kind == Kind.LIVE && ProviderSectionsLogic.wanted(c.group)) m.getOrPut(c.group) { ArrayList() }.add(c)
            val folders = m.entries.sortedWith(compareBy({ ProviderSectionsLogic.rank(it.key) }, { it.key })).map { it.key to it.value.toList() }
            val espn = live.filter { SportChannels.wanted(it) }.sortedBy { SportChannels.rank(it) }.take(40)
            folders + (if (espn.isNotEmpty()) listOf(ESPN_SECTION to espn) else emptyList())
        }
    }
    var sel by remember { mutableStateOf(ProviderMemory.section) }
    val cur = sections.firstOrNull { it.first == sel } ?: sections.firstOrNull()
    var tick by remember { mutableIntStateOf(0) }
    // Guide states move on by themselves (a game starts): read again every minute, off the main thread
    LaunchedEffect(Unit) { while (true) { delay(60_000); tick++ } }
    val tiles by produceState(emptyList<Pair<Item, ProviderSectionsLogic.Status>>(), cur?.first, cur?.second?.size, tick, Xmltv.channelCount) {
        val items = cur?.second
        if (items == null) { value = emptyList(); return@produceState }
        value = withContext(Dispatchers.Default) {
            val now = System.currentTimeMillis() / 1000
            val st = items.map { c -> ProviderSectionsLogic.status(runCatching { SportGuard.entryAt(Xmltv.forChannel(c.tvgId), now) }.getOrNull()) }
            ProviderSectionsLogic.order(st).map { items[it] to st[it] }
        }
    }

    if (sections.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(DS.PageX), contentAlignment = Alignment.TopStart) {
            Text(raw = true, text = "Your playlist has no NBA, NFL or soccer folders yet. They appear here once the playlist has loaded.",
                color = Muted, fontSize = spx(18))
        }
        return
    }
    val firstTile = remember { FocusRequester() }
    val restoreReq = remember { FocusRequester() }
    val gridState = rememberLazyGridState()
    Column(Modifier.fillMaxSize()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = DS.PageX, vertical = dpx(6)),
            horizontalArrangement = Arrangement.spacedBy(dpx(10))
        ) {
            items(sections, key = { it.first }) { (g, list) ->
                FilterPill(ProviderSectionsLogic.sectionName(g) + "  " + list.size, g == cur?.first, raw = true) {
                    sel = g; ProviderMemory.section = g
                }
            }
        }
        val cols = 5
        LazyVerticalGrid(
            columns = GridCells.Fixed(cols),
            state = gridState,
            contentPadding = PaddingValues(start = DS.PageX, end = DS.PageX, top = dpx(10), bottom = dpx(40)),
            horizontalArrangement = Arrangement.spacedBy(dpx(14)),
            verticalArrangement = Arrangement.spacedBy(dpx(14)),
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(tiles, key = { _, t -> t.first.id }) { i, (c, st) ->
                val mod = when {
                    c.id == ProviderMemory.tileId -> Modifier.focusRequester(restoreReq)
                    i == 0 -> Modifier.focusRequester(firstTile)
                    else -> Modifier
                }
                ProviderTile(c, st, mod) {
                    ProviderMemory.tileId = c.id
                    StartTrace.ok("channels")
                    onPlay(c, tiles.map { it.first })
                }
            }
        }
    }
    // Back from the player: focus returns to the tile that was played
    LaunchedEffect(tiles.isNotEmpty()) {
        if (tiles.isEmpty()) return@LaunchedEffect
        val idx = tiles.indexOfFirst { it.first.id == ProviderMemory.tileId }
        if (idx >= 0) { gridState.scrollToItem(idx); delay(50); runCatching { restoreReq.requestFocus() } }
    }
}

private const val ESPN_SECTION = "ESPN and NBA TV"

@Composable
private fun ProviderTile(c: Item, st: ProviderSectionsLogic.Status, modifier: Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(dpx(10))
    val ctx = LocalContext.current
    Column(
        modifier.fillMaxWidth().height(dpx(156)).clip(shape)
            .background(if (f) Color(0xFF2C2C33) else Color(0xFF1C1C21))
            .border(if (f) 3.dp else 0.dp, if (f) Color.White else Color.Transparent, shape)
            .onFocusChanged { f = it.isFocused }
            .clickable(onClick = onClick)
            .padding(dpx(14)),
        verticalArrangement = Arrangement.spacedBy(dpx(8))
    ) {
        Box(Modifier.size(dpx(54)).clip(CircleShape).background(Color(0xFF2E2E36)), contentAlignment = Alignment.Center) {
            val logo = c.logo
            if (!logo.isNullOrBlank()) {
                val req = remember(logo) { coil.request.ImageRequest.Builder(ctx).data(logo).size(128, 128).build() }
                AsyncImage(req, null, Modifier.fillMaxSize().padding(dpx(6)), contentScale = ContentScale.Fit)
            } else Text(raw = true, text = ProviderSectionsLogic.tileName(c.title).take(2).uppercase(), color = Color(0xFFBDBDBD), fontSize = spx(18), fontWeight = FontWeight.Bold)
        }
        Text(raw = true, text = ProviderSectionsLogic.tileName(c.title), color = Color.White, fontSize = spx(18),
            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val (color, text) = when (st.kind) {
            ProviderSectionsLogic.Kind.LIVE -> Color(0xFFFF4D5A) to "● Live · " + st.text
            ProviderSectionsLogic.Kind.SOON -> Color(0xFFE8E8E8) to st.text
            ProviderSectionsLogic.Kind.LATER -> Color(0xFF9A9A9A) to st.text
            ProviderSectionsLogic.Kind.NONE -> Color(0xFF6E6E6E) to st.text
            ProviderSectionsLogic.Kind.UNKNOWN -> Color(0xFF6E6E6E) to ""
        }
        if (text.isNotEmpty()) Text(raw = true, text = text, color = color, fontSize = spx(15), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
