package com.ipvio.stream

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One episode in the player's episode list: still, "E4 · Title", runtime, two lines of synopsis, progress bar,
 * watched / playing / up next. Fixed height, so moving through the list never shifts the layout.
 * Details come from [EpisodeInfo] (same as the title page), progress from [EpProgress].
 */
@Composable
internal fun PEpisodeRow(
    ep: Episode, meta: EpMeta?, prog: ResumeLogic.EpPos?, playing: Boolean, upNext: Boolean,
    modifier: Modifier, onClick: () -> Unit
) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(DS.RadiusM)
    val ctx = LocalContext.current
    Row(
        modifier.fillMaxWidth().height(96.dp).clip(shape)
            .background(if (f) Color(0x29FFFFFF) else if (playing) Color(0x14FFFFFF) else Color.Transparent)
            .onFocusChanged { f = it.isFocused }.clickable(onClick = onClick).padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(136.dp).fillMaxHeight().clip(RoundedCornerShape(DS.RadiusS)).background(Color(0xFF1A1C24)), contentAlignment = Alignment.Center) {
            val still = meta?.still
            val hide = FofPrefs.spoilerPics && prog?.done != true
            if (still != null) {
                // Decoded small (no full-size stills while scrolling); blurred-by-size when spoilers are hidden
                val req = remember(still, hide) { coil.request.ImageRequest.Builder(ctx).data(still).size(if (hide) 32 else 272, if (hide) 18 else 152).build() }
                coil.compose.AsyncImage(req, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else AppIcon(AIcon.Play, Color(0x66FFFFFF), size = 24.dp)
            if (prog != null && !prog.done && prog.fraction > 0.02f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x66FFFFFF))) {
                Box(Modifier.fillMaxWidth(prog.fraction).fillMaxHeight().background(Accent2))
            }
            if (prog?.done == true) Box(Modifier.align(Alignment.TopEnd).padding(5.dp).clip(RoundedCornerShape(50)).background(Color(0xCC000000)).padding(4.dp)) {
                AppIcon(AIcon.Check, Accent2, size = 14.dp)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            val title = meta?.name?.takeIf { it.isNotBlank() } ?: ep.title.ifBlank { "Episode ${ep.num}" }
            val episodeLabel = "Episode ${ep.num}"
            Text(
                if (title.equals(episodeLabel, ignoreCase = true)) episodeLabel else "${ep.num}. $title",
                color = Color.White, fontSize = 18.sp, fontWeight = if (f || playing) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, raw = true
            )
            val status = when {
                playing -> "Now playing"
                upNext -> "Up next"
                prog != null && !prog.done && prog.fraction > 0.02f && prog.dur > 0 ->
                    "${(prog.dur - prog.pos).coerceAtLeast(60) / 60} min left"
                else -> meta?.runtime?.let { "$it min" }
            }
            if (status != null) Text(status, color = if (playing) Accent2 else Color(0x99FFFFFF), fontSize = 13.sp, raw = true, maxLines = 1)
            val overview = meta?.overview?.takeIf { f && !(FofPrefs.spoilerText && prog?.done != true) }
            if (overview != null) Text(overview, color = Color(0xD9FFFFFF), fontSize = 14.sp, lineHeight = 17.sp,
                raw = true, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * One episode as a card in the player's Episodes strip (player redesign, 27 Nov 2026): the still in 16:9 with the progress
 * line, "Playing" on the running one, a white frame when focused; number, name and length below. Same spoiler rules and
 * small decoding as [PEpisodeRow].
 */
@Composable
internal fun PEpisodeCard(
    ep: Episode, meta: EpMeta?, prog: ResumeLogic.EpPos?, playing: Boolean, upNext: Boolean,
    modifier: Modifier, onClick: () -> Unit
) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    val ctx = LocalContext.current
    Column(Modifier.width(260.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(shape).background(Color(0xFF1A1C24))
                .then(if (f) Modifier.border(3.dp, Color.White, shape) else Modifier)
                .onFocusChanged { f = it.isFocused }.clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            val still = meta?.still
            val hide = FofPrefs.spoilerPics && prog?.done != true && !playing
            if (still != null) {
                val req = remember(still, hide) { coil.request.ImageRequest.Builder(ctx).data(still).size(if (hide) 32 else 400, if (hide) 18 else 225).build() }
                coil.compose.AsyncImage(req, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else Text(ep.num.toString(), color = Color(0x33FFFFFF), fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, raw = true)
            if (playing) Row(
                Modifier.align(Alignment.TopStart).padding(8.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xB3000000))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(FofVisual.Violet))
                Text("Playing", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, raw = true)
            }
            if (prog?.done == true && !playing) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).clip(RoundedCornerShape(50)).background(Color(0xB3000000)).padding(4.dp)) {
                AppIcon(AIcon.Check, Color.White, size = 13.dp)
            }
            if (prog != null && !prog.done && prog.fraction > 0.02f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x55FFFFFF))) {
                Box(Modifier.fillMaxWidth(prog.fraction).fillMaxHeight().background(FofVisual.Violet))
            }
        }
        val title = meta?.name?.takeIf { it.isNotBlank() } ?: ep.title.ifBlank { "Episode ${ep.num}" }
        val episodeLabel = "Episode ${ep.num}"
        Text(
            if (title.equals(episodeLabel, ignoreCase = true)) episodeLabel else "${ep.num} · $title",
            color = if (f || playing) Color.White else Color(0xFFDDE1EA), fontSize = 16.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, raw = true
        )
        val status = when {
            playing -> prog?.takeIf { it.dur > 0 }?.let { "${(it.dur - it.pos).coerceAtLeast(60) / 60} min left" } ?: "Now playing"
            upNext -> "Up next" + (meta?.runtime?.let { " · $it min" } ?: "")
            prog?.done == true -> "Watched" + (meta?.runtime?.let { " · $it min" } ?: "")
            prog != null && prog.fraction > 0.02f && prog.dur > 0 -> "${(prog.dur - prog.pos).coerceAtLeast(60) / 60} min left"
            else -> meta?.runtime?.let { "$it min" }
        }
        Text(status ?: " ", color = Color(0xFFA9AFBD), fontSize = 13.sp, maxLines = 1, raw = true)
    }
}
