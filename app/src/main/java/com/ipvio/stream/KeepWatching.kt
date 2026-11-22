package com.ipvio.stream

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

/** Shown once per app start. */
object KeepWatching { var shown = false }

/**
 * "Keep watching" card in the lower right of Home (same as the Mac app's): something was played in the last hour, here or
 * on another device of the account. It takes the focus on Resume for 15 s; OK resumes at the saved spot, Back or Right
 * closes it, and the Home screen gets the focus back.
 */
@Composable
fun KeepWatchingCard(e: Entry, item: Item, modifier: Modifier, onResume: () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    val req = remember { FocusRequester() }
    fun close() { visible = false; KeepWatching.shown = true; runCatching { ContentFocus.req.requestFocus() } }
    LaunchedEffect(Unit) {
        delay(1_500); visible = true
        delay(400); runCatching { req.requestFocus() }
        delay(15_000); if (visible) close()
    }
    BackHandler(enabled = visible) { close() }
    AnimatedVisibility(visible, modifier.padding(end = dpx(36), bottom = dpx(32)), enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it }) {
        val m = rememberMeta(item)
        Row(
            Modifier.widthIn(max = dpx(700)).clip(RoundedCornerShape(dpx(10))).background(Color(0xF21C1C1C)).padding(dpx(12)),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(16))
        ) {
            Box(Modifier.width(dpx(150)).height(dpx(84)).clip(RoundedCornerShape(dpx(6))).background(Color(0xFF2A2A2A))) {
                val img = m?.backdrop?.let { cardBackdrop(it) } ?: m?.poster
                if (img != null) AsyncImage(img, null, Modifier.size(dpx(150), dpx(84)), contentScale = ContentScale.Crop)
            }
            Column(Modifier.widthIn(max = dpx(300)), verticalArrangement = Arrangement.spacedBy(dpx(3))) {
                Text("KEEP WATCHING", color = Color(0xFFE60023), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.5.sp)
                val se = if (e.season > 0 || e.ep > 0) "  ·  S${e.season} E${e.ep}" else ""
                Text(raw = true, text = item.title + se, color = Color.White, fontSize = spx(17), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(raw = true, text = ((e.dur - e.pos) / 60_000).coerceAtLeast(1).toString() + " min left", color = Color(0xFFB3B3B3), fontSize = spx(13))
            }
            var f by remember { mutableStateOf(false) }
            Row(
                Modifier.focusRequester(req).clip(RoundedCornerShape(dpx(6))).background(if (f) Color.White else Color(0xB36D6D6E))
                    .onFocusChanged { f = it.isFocused }
                    .clickable { close(); onResume() }
                    .padding(horizontal = dpx(18), vertical = dpx(10)),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(8))
            ) {
                AppIcon(AIcon.Play, if (f) Color.Black else Color.White, size = dpx(16))
                Text("Resume", color = if (f) Color.Black else Color.White, fontSize = spx(16), fontWeight = FontWeight.Bold)
            }
        }
    }
}
