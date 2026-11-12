package com.ipvio.stream

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Avatar colors per profile (by id), calm tones that sit well on black. */
private val AVATAR = listOf(Color(0xFFFF7A5C), Color(0xFF3FB8AF), Color(0xFF6C7BFF), Color(0xFFF2B544), Color(0xFFE0609A), Color(0xFF8BC34A))

/**
 * "Who's watching?" welcome: Fof TV wordmark, large profile tiles, and a tile to add a profile (named right away).
 * Profiles share the IPTV login; history, favorites and settings are kept per profile.
 */
@Composable
fun FofWelcome(onPick: (Int) -> Unit) {
    val ctx = LocalContext.current
    var naming by remember { mutableStateOf<Int?>(null) }
    var name by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }
    val startIdx = remember { Profiles.list.indexOfFirst { it.id == Profiles.active }.coerceAtLeast(0) }
    LaunchedEffect(naming) {
        if (naming != null) return@LaunchedEffect
        delay(150)
        repeat(8) { if (runCatching { first.requestFocus() }.getOrDefault(false)) return@LaunchedEffect; delay(80) }
    }
    Box(
        Modifier.fillMaxSize().background(Color(0xFF0A0A0C))
            .background(Brush.radialGradient(listOf(Color(0x33FF7A5C), Color.Transparent), radius = 1400f))
    ) {
        // Wordmark top left
        Row(Modifier.padding(start = 56.dp, top = 40.dp)) {
            Text("Fof", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, raw = true)
            Text(" TV", color = Color(0xFFFF7A5C), fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, raw = true)
        }
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(44.dp)) {
            Text("Who's watching?", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold, raw = true)
            Row(horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.Top) {
                Profiles.list.forEachIndexed { i, p ->
                    ProfileTile(
                        Profiles.displayName(p), AVATAR[p.id % AVATAR.size],
                        if (i == startIdx) Modifier.focusRequester(first) else Modifier
                    ) { onPick(p.id) }
                }
                if (Profiles.list.size < Profiles.MAX) AddTile {
                    if (Profiles.add(ctx)) { naming = Profiles.list.last().id; name = "" }
                }
            }
            Text("Each profile keeps its own history, favorites and settings.", color = Color(0x99FFFFFF), fontSize = 16.sp, raw = true)
        }
        naming?.let { id ->
            Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
                Column(
                    Modifier.width(520.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF16161A)).padding(28.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    Text("Name the new profile", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, raw = true)
                    Field(name, { name = it }, "Name", autoOpen = true, onDone = { Profiles.rename(ctx, id, name); naming = null })
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PillButton("Save", light = true, icon = AIcon.Check) { Profiles.rename(ctx, id, name); naming = null }
                        PillButton("Skip") { naming = null }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileTile(name: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (f) 1.1f else 1f, label = "pt")
    Column(
        modifier.onFocusChanged { f = it.isFocused }.clickable(onClick = onClick).graphicsLayer { scaleX = s; scaleY = s },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            Modifier.size(150.dp).clip(RoundedCornerShape(28.dp)).background(color)
                .border(3.dp, if (f) Color.White else Color.Transparent, RoundedCornerShape(28.dp))
                .alpha(if (f) 1f else 0.85f),
            contentAlignment = Alignment.Center
        ) {
            Text(name.take(1).uppercase(), color = Color(0xFF0A0A0C), fontSize = 64.sp, fontWeight = FontWeight.ExtraBold, raw = true)
        }
        Text(name, color = if (f) Color.White else Color(0x99FFFFFF), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, raw = true)
    }
}

@Composable
private fun AddTile(onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (f) 1.1f else 1f, label = "pa")
    Column(
        Modifier.onFocusChanged { f = it.isFocused }.clickable(onClick = onClick).graphicsLayer { scaleX = s; scaleY = s },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            Modifier.size(150.dp).clip(RoundedCornerShape(28.dp)).background(Color(0x14FFFFFF))
                .border(2.dp, if (f) Color.White else Color(0x33FFFFFF), RoundedCornerShape(28.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("+", color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.Light, raw = true)
        }
        Text("Add profile", color = if (f) Color.White else Color(0x99FFFFFF), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, raw = true)
    }
}
