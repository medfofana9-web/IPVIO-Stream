package com.ipvio.stream

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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

/** Avatar ring colors per profile (by id), calm tones that sit well on black. */
private val AVATAR = listOf(Color(0xFF7A3B55), Color(0xFF2F6F6A), Color(0xFF3E4A9E), Color(0xFF8A6A1E), Color(0xFF7C2F4E), Color(0xFF4E7A2A))

/** Illustrated avatar per profile name (DiceBear, free); the initial shows until it has loaded or when offline. */
private fun avatarUrl(name: String) = "https://api.dicebear.com/9.x/avataaars/png?size=256&seed=" + java.net.URLEncoder.encode(name.ifBlank { "Fof" }, "UTF-8")

/**
 * "Who's watching?" (Nuvio style): warm glow, Fof TV mark, round avatars with names and a lock for protected profiles,
 * and "Manage profiles" (add or rename). Profiles share the IPTV login; history, favorites and settings are per profile.
 */
@Composable
fun FofWelcome(onPick: (Int) -> Unit) {
    val ctx = LocalContext.current
    var naming by remember { mutableStateOf<Int?>(null) }
    var name by remember { mutableStateOf("") }
    var manage by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    val startIdx = remember { Profiles.list.indexOfFirst { it.id == Profiles.active }.coerceAtLeast(0) }
    LaunchedEffect(naming) {
        if (naming != null) return@LaunchedEffect
        delay(120)
        repeat(8) { if (runCatching { first.requestFocus() }.getOrDefault(false)) return@LaunchedEffect; delay(80) }
    }
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .background(Brush.radialGradient(listOf(Color(0x8C4A3A10), Color(0x334A3A10), Color.Transparent),
                center = androidx.compose.ui.geometry.Offset(380f, 320f), radius = 1500f))
    ) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            // Fof TV mark
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(Color(0xFFE50914), Color(0xFF8E0710)))),
                    contentAlignment = Alignment.Center) { AppIcon(AIcon.Play, Color.White, size = 22.dp) }
                Text("fof tv", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, raw = true)
            }
            Spacer(Modifier.height(30.dp))
            Text(if (manage) "Manage profiles" else "Who's watching?", color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold, raw = true)
            Spacer(Modifier.height(48.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(44.dp), verticalAlignment = Alignment.Top) {
                Profiles.list.forEachIndexed { i, p ->
                    val nm = Profiles.displayName(p)
                    ProfileTile(
                        nm, AVATAR[p.id % AVATAR.size], Parental.profileLocked(ctx, p.id), manage,
                        if (i == startIdx) Modifier.focusRequester(first) else Modifier
                    ) { if (manage) { naming = p.id; name = nm } else onPick(p.id) }
                }
                if (manage && Profiles.list.size < Profiles.MAX) AddTile {
                    if (Profiles.add(ctx)) { naming = Profiles.list.last().id; name = "" }
                }
            }
            Spacer(Modifier.height(56.dp))
            OutlinePill(if (manage) "Done" else "Manage Profiles") { manage = !manage }
        }
        naming?.let { id ->
            Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
                Column(
                    Modifier.width(520.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF141416)).padding(28.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    Text("Profile name", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, raw = true)
                    Field(name, { name = it }, "Name", autoOpen = true, onDone = { Profiles.rename(ctx, id, name); naming = null })
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PillButton("Save", light = true, icon = AIcon.Check) { Profiles.rename(ctx, id, name); naming = null }
                        PillButton("Cancel") { naming = null }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileTile(name: String, ring: Color, locked: Boolean, manage: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (f) 1.08f else 1f, androidx.compose.animation.core.tween(160), label = "pt")
    Column(
        modifier.onFocusChanged { f = it.isFocused }.clickable(onClick = onClick).graphicsLayer { scaleX = s; scaleY = s },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(148.dp)) {
            Box(
                Modifier.fillMaxSize().clip(CircleShape).background(ring)
                    .border(4.dp, if (f) Color.White else ring.copy(alpha = 0.5f), CircleShape).padding(8.dp).clip(CircleShape)
                    .background(ring.copy(alpha = 0.9f)),
                contentAlignment = Alignment.Center
            ) {
                Text(name.take(1).uppercase(), color = Color.White, fontSize = 56.sp, fontWeight = FontWeight.ExtraBold, raw = true)
                coil.compose.AsyncImage(avatarUrl(name), null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            }
            if (locked || manage) Box(
                Modifier.align(Alignment.BottomEnd).size(40.dp).clip(CircleShape).background(Color(0xFF1C1C20)).border(2.dp, Color.Black, CircleShape),
                contentAlignment = Alignment.Center
            ) { AppIcon(if (manage) AIcon.Edit else AIcon.Lock, Color(0xFFB0B0B8), size = 18.dp) }
        }
        Spacer(Modifier.height(18.dp))
        Text(name, color = if (f) Color.White else Color(0xE6FFFFFF), fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, raw = true)
    }
}

@Composable
private fun AddTile(onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (f) 1.08f else 1f, androidx.compose.animation.core.tween(160), label = "pa")
    Column(
        Modifier.onFocusChanged { f = it.isFocused }.clickable(onClick = onClick).graphicsLayer { scaleX = s; scaleY = s },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(148.dp).clip(CircleShape).background(Color(0x14FFFFFF))
                .border(3.dp, if (f) Color.White else Color(0x33FFFFFF), CircleShape),
            contentAlignment = Alignment.Center
        ) { Text("+", color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.Light, raw = true) }
        Spacer(Modifier.height(18.dp))
        Text("Add profile", color = if (f) Color.White else Color(0x99FFFFFF), fontSize = 22.sp, fontWeight = FontWeight.Bold, raw = true)
    }
}

@Composable
private fun OutlinePill(label: String, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Box(
        Modifier.onFocusChanged { f = it.isFocused }.clip(shape).background(if (f) Color.White else Color.Transparent)
            .border(2.dp, if (f) Color.White else Color(0x40FFFFFF), shape).clickable(onClick = onClick)
            .padding(horizontal = 34.dp, vertical = 14.dp)
    ) { Text(label, color = if (f) Color.Black else Color(0xB3FFFFFF), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, raw = true) }
}
