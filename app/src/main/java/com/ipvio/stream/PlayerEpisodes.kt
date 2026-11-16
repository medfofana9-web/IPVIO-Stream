package com.ipvio.stream

import androidx.compose.foundation.background
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
        modifier.fillMaxWidth().height(112.dp).clip(shape)
            .background(if (f) Color(0x29FFFFFF) else if (playing) Color(0x14FFFFFF) else Color.Transparent)
            .onFocusChanged { f = it.isFocused }.clickable(onClick = onClick).padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(164.dp).fillMaxHeight().clip(RoundedCornerShape(DS.RadiusS)).background(Color(0xFF1A1C24)), contentAlignment = Alignment.Center) {
            val still = meta?.still
            val hide = FofPrefs.spoilerPics && prog?.done != true
            if (still != null) {
                // Decoded small (no full-size stills while scrolling); blurred-by-size when spoilers are hidden
                val req = remember(still, hide) { coil.request.ImageRequest.Builder(ctx).data(still).size(if (hide) 32 else 328, if (hide) 18 else 184).build() }
                coil.compose.AsyncImage(req, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else Text("E${ep.num}", color = Color(0x99FFFFFF), fontSize = 22.sp, fontWeight = FontWeight.Bold, raw = true)
            if (prog != null && !prog.done && prog.fraction > 0.02f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x66FFFFFF))) {
                Box(Modifier.fillMaxWidth(prog.fraction).fillMaxHeight().background(Accent2))
            }
            if (prog?.done == true) Box(Modifier.align(Alignment.TopEnd).padding(5.dp).clip(RoundedCornerShape(50)).background(Color(0xCC000000)).padding(4.dp)) {
                AppIcon(AIcon.Check, Accent2, size = 14.dp)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("E${ep.num} · " + (meta?.name ?: ep.title.ifBlank { "Episode ${ep.num}" }), color = Color.White, fontSize = 18.sp,
                    fontWeight = if (f || playing) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, raw = true,
                    modifier = Modifier.weight(1f, fill = false))
                when {
                    playing -> Tag("Playing", Accent2)
                    upNext -> Tag("Up next", Color.White)
                }
            }
            val info = listOfNotNull(meta?.runtime?.let { "$it min" },
                prog?.takeIf { !it.done && it.fraction > 0.02f && it.dur > 0 }?.let { p -> "${(p.dur - p.pos).coerceAtLeast(60) / 60} min left" },
                if (prog?.done == true) "Watched" else null, meta?.air).joinToString(" · ")
            if (info.isNotEmpty()) Text(info, color = Color(0x99FFFFFF), fontSize = 14.sp, raw = true, maxLines = 1)
            val ov = meta?.overview?.takeIf { !(FofPrefs.spoilerText && prog?.done != true) }
            if (ov != null) Text(ov, color = if (f) Color(0xD9FFFFFF) else Color(0x80FFFFFF), fontSize = 14.sp, lineHeight = 18.sp, raw = true, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Tag(t: String, c: Color) {
    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(c.copy(alpha = 0.16f)).padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(t, color = c, fontSize = 12.sp, fontWeight = FontWeight.Bold, raw = true)
    }
}
