package com.ipvio.stream

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

/**
 * Ambient screen: after 4 minutes without a key press (not while playing), large artwork from popular and top rated
 * titles fades slowly, with the title and the clock, like the Apple TV screensaver. Any key returns exactly where you were
 * (that key does nothing else). The TV's own sleep still applies: the screen is not kept on.
 */
object Ambient {
    const val IDLE_MS = 4 * 60_000L
    @Volatile var lastKey = System.currentTimeMillis()
    var on by mutableStateOf(false)

    /** Called for every key: true = the key only woke the screen and is used up. */
    fun key(): Boolean { lastKey = System.currentTimeMillis(); if (on) { on = false; return true }; return false }

    fun pool(): List<Pair<Item, String>> = (Catalog.cached(Catalog.featured(Kind.MOVIE)) + Catalog.cached(Catalog.popular(Kind.SERIES)) + Catalog.cached(Catalog.popular(Kind.MOVIE)))
        .distinctBy { it.id }.mapNotNull { i -> Catalog.meta(i)?.backdrop?.let { i to (if (Lite.on) it else it.replace("/t/p/w780/", "/t/p/w1280/")) } }.shuffled().take(40)
}

@Composable
fun AmbientHost() {
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            if (!Ambient.on && !AppNav.playing && System.currentTimeMillis() - Ambient.lastKey > Ambient.IDLE_MS && Ambient.pool().size >= 3) Ambient.on = true
        }
    }
    if (!Ambient.on) return
    val pool = remember { Ambient.pool() }
    if (pool.isEmpty()) { Ambient.on = false; return }
    var i by remember { mutableIntStateOf(0) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(12_000); i = (i + 1) % pool.size; now = System.currentTimeMillis() } }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // Budget boxes (Lite) and Motion off: the picture changes with a short fade and stands still. The 14 s drift and
        // 2 s crossfade of two full-screen pictures kept the GPU drawing every frame for as long as the TV sat idle.
        // Calm on every TV: the onn 4K Plus (not a budget box) still reported 99–100 % slow frames at 76–81 % GPU while the
        // drift and long crossfade ran for as long as the TV sat idle. Now a short fade, then a still picture.
        val calm = true
        Crossfade(i, animationSpec = tween(if (calm) 400 else 2_000), label = "amb") { k ->
            val (item, url) = pool[k]
            // Slow drift: one gentle move per picture (drawn by the layer, nothing is laid out again)
            val t = remember { androidx.compose.animation.core.Animatable(0f) }
            LaunchedEffect(k) { if (!calm) t.animateTo(1f, tween(14_000, easing = androidx.compose.animation.core.LinearEasing)) }
            Box(Modifier.fillMaxSize()) {
                AsyncImage(url, null, Modifier.fillMaxSize().graphicsLayer { val s = 1.04f + 0.04f * t.value; scaleX = s; scaleY = s; translationX = -24f * t.value }, contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0xCC000000))))
                Column(Modifier.align(Alignment.BottomStart).padding(56.dp)) {
                    Text(item.title, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold, raw = true, maxLines = 1)
                    Catalog.meta(item)?.year?.let { Text(it, color = Color(0xB3FFFFFF), fontSize = 18.sp, raw = true) }
                }
            }
        }
        Text(android.text.format.DateFormat.getTimeFormat(androidx.compose.ui.platform.LocalContext.current).format(java.util.Date(now)),
            color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Light, raw = true, modifier = Modifier.align(Alignment.TopEnd).padding(48.dp))
    }
}
