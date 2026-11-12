package com.ipvio.stream

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
                            onDismiss()
                            onPlay(s.url, title, 0L)
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
    Hint("Only streams with a direct link play. Torrent-only addons need a debrid service (such as Real-Debrid) configured inside the addon.")
}
