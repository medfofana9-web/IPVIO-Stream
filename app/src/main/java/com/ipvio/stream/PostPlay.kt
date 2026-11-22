package com.ipvio.stream

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/** Opens a title page from anywhere (the player's "More Like This"): MainActivity closes the player and shows the page. */
object OpenTitle {
    var req by mutableStateOf<Item?>(null)
}

/**
 * End of a movie or of the last episode: "More Like This" over the last frame, like Netflix. Up to five recommendations
 * (TMDB, the same list as the title page); OK opens a title's page, Back closes the player. Without recommendations
 * (no TMDB key, or nothing found) the player simply closes after a few seconds.
 */
@Composable
fun PostPlay(seed: Item?, title: String, onOpen: (Item) -> Unit, onClose: () -> Unit) {
    val picks by produceState<List<Item>?>(null, seed?.id) {
        value = runCatching { if (seed == null) emptyList() else TmdbExtra.because(seed).take(5) }.getOrDefault(emptyList())
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(picks) {
        val p = picks ?: return@LaunchedEffect
        if (p.isEmpty()) { kotlinx.coroutines.delay(4_000); onClose() } else runCatching { first.requestFocus() }
    }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x66000000), 0.45f to Color(0xCC000000), 1f to Color(0xF2000000)))) {
        Column(Modifier.align(Alignment.BottomStart).padding(start = dpx(64), end = dpx(64), bottom = dpx(56)), verticalArrangement = Arrangement.spacedBy(dpx(18))) {
            Text(raw = true, text = title, color = Color(0xFFB3B3B3), fontSize = spx(18), fontWeight = FontWeight.SemiBold, maxLines = 1)
            val p = picks
            Text(if (p != null && p.isEmpty()) "Back to Fof TV …" else "More Like This", color = Color.White, fontSize = spx(30), fontWeight = FontWeight.Bold)
            if (!p.isNullOrEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(dpx(14))) {
                p.forEachIndexed { i, it -> PostPlayCard(it, if (i == 0) Modifier.focusRequester(first) else Modifier) { onOpen(it) } }
            }
        }
    }
}

@Composable
private fun PostPlayCard(item: Item, modifier: Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(if (f) focusScale(1.07f) else 1f, tween(150), label = "pp")
    val shape = RoundedCornerShape(dpx(6))
    val m = rememberMeta(item)
    Box(
        modifier.width(dpx(200)).aspectRatio(2f / 3f).graphicsLayer { scaleX = sc; scaleY = sc }.clip(shape)
            .background(Color(0xFF1C1C1C)).border(if (f) 3.dp else 0.dp, if (f) Color.White else Color.Transparent, shape)
            .onFocusChanged { f = it.isFocused }.clickable(onClick = onClick)
    ) {
        val img = m?.poster?.let { cardPoster(it) } ?: item.logo
        if (img != null) AsyncImage(img, item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else ArtPlaceholder(item, Modifier.fillMaxSize(), showTitle = true)
    }
}
