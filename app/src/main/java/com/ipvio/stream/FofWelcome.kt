package com.ipvio.stream

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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


/**
 * "Who's watching?" (Nuvio style): warm glow, Fof TV mark, round avatars with names and a lock for protected profiles,
 * and "Manage profiles" (add or rename). Profiles share the IPTV login; history, favorites and settings are per profile.
 */
@Composable
fun FofWelcome(onPick: (Int) -> Unit) {
    val ctx = LocalContext.current
    var naming by remember { mutableStateOf<Int?>(null) }
    var picking by remember { mutableStateOf<Int?>(null) }
    var name by remember { mutableStateOf("") }
    var manage by remember { mutableStateOf(false) }
    // Master profile (the first one): only it may manage profiles; with its PIN set, managing asks for it
    var askPin by remember { mutableStateOf(false) }
    var pinErr by remember { mutableStateOf<String?>(null) }
    var options by remember { mutableStateOf<Int?>(null) }
    var phone by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    val startIdx = remember { Profiles.list.indexOfFirst { it.id == Profiles.active }.coerceAtLeast(0) }
    LaunchedEffect(naming, picking) {
        if (naming != null || picking != null) return@LaunchedEffect
        delay(120)
        repeat(8) { if (runCatching { first.requestFocus() }.getOrDefault(false)) return@LaunchedEffect; delay(80) }
    }
    FofAccount.init(ctx)
    // Poster wall behind the profiles (the catalog's popular titles, already on the device), faded into black
    val wall = remember {
        val m = Catalog.cached(Catalog.popular(Kind.MOVIE)); val s = Catalog.cached(Catalog.popular(Kind.SERIES))
        (0 until maxOf(m.size, s.size)).flatMap { listOfNotNull(m.getOrNull(it), s.getOrNull(it)) }.mapNotNull { it.logo }.distinct().take(24)
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (wall.size >= 8) Column(Modifier.fillMaxSize().graphicsLayer { alpha = 0.22f; rotationZ = -6f; scaleX = 1.25f; scaleY = 1.25f },
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            wall.chunked(8).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    row.forEach { u -> coil.compose.AsyncImage(u, null, Modifier.width(150.dp).height(225.dp).clip(RoundedCornerShape(10.dp)),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
                }
            }
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xCC000000), Color(0xE6000000), Color.Black))))
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0x40E50914), Color.Transparent),
            center = androidx.compose.ui.geometry.Offset(960f, 200f), radius = 1100f)))
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            // Fof TV mark
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(Color(0xFFE50914), Color(0xFF8E0710)))),
                    contentAlignment = Alignment.Center) { AppIcon(AIcon.Play, Color.White, size = 22.dp) }
                Text("fof tv", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, raw = true)
            }
            Spacer(Modifier.height(30.dp))
            Text(if (manage) "Manage profiles" else "Who's watching?", color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold, raw = true)
            // The family this TV belongs to (family account), as a quiet line under the title
            FofAccount.member?.let { m ->
                Spacer(Modifier.height(8.dp))
                Text(if (m.owner) "Your family setup" else "Family setup by your host", color = Color(0x99FFFFFF), fontSize = 16.sp, raw = true)
            }
            Spacer(Modifier.height(48.dp))
            // Up to 10 profiles: one row up to 5, then two rows
            val tiles: List<Int?> = Profiles.list.indices.toList() + (if (manage && Profiles.list.size < Profiles.MAX) listOf(null) else emptyList())
            val big = tiles.size > 5
            Column(verticalArrangement = Arrangement.spacedBy(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                tiles.chunked(if (big) (tiles.size + 1) / 2 else 5).forEach { rowTiles ->
                    Row(horizontalArrangement = Arrangement.spacedBy(if (big) 30.dp else 44.dp), verticalAlignment = Alignment.Top) {
                        rowTiles.forEach { i ->
                            if (i == null) AddTile(big) { if (Profiles.add(ctx)) { naming = Profiles.list.last().id; name = "" } }
                            else {
                                val p = Profiles.list[i]
                                val nm = Profiles.displayName(p)
                                val av = remember(p.id, nm, ProfileAvatars.version) { ProfileAvatars.urlFor(ctx, p.id, nm) }
                                ProfileTile(
                                    nm, av, AVATAR[p.id % AVATAR.size], Parental.profileLocked(ctx, p.id), manage, p.id == 0, big,
                                    if (i == startIdx) Modifier.focusRequester(first) else Modifier,
                                    // Hold OK on a profile: choose its picture right here (a locked profile asks in Manage)
                                    onLong = { if (!Parental.profileLocked(ctx, p.id)) picking = p.id }
                                ) { if (manage) options = p.id else onPick(p.id) }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(if (big) 26.dp else 40.dp))
            if (!manage) Text("Hold OK on a profile to change its picture", color = Color(0x80FFFFFF), fontSize = 15.sp, raw = true)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // Not signed in: join the family (sign up with name, avatar and invite code on the phone)
            if (!manage && FofAccount.member == null) OutlinePill(if (FofAccount.signedIn) "Join your family" else "Sign in or join your family") { phone = true }
            OutlinePill(if (manage) "Done" else "Manage Profiles") {
                if (manage) manage = false
                else if (Parental.profileLocked(ctx, 0)) { askPin = true; pinErr = null }
                else manage = true
            }
            }
        }
        if (phone) RemoteOverlay(Modifier.align(Alignment.Center), onClose = { phone = false })
        if (askPin) Box(Modifier.fillMaxSize().background(Color(0xE6000000)), contentAlignment = Alignment.Center) {
            Box(Modifier.width(420.dp)) {
                PinPad("Master PIN", "Only the master profile can manage profiles.", pinErr,
                    { if (Parental.verifyFor(ctx, 0, it)) { askPin = false; manage = true } else pinErr = Parental.pinError() },
                    { askPin = false })
            }
        }
        options?.let { id ->
            val acts = if (id == 0) listOf("Change avatar", "Rename") else listOf("Change avatar", "Rename", "Delete profile")
            OptionPopup(acts, -1, { options = null }) { a ->
                options = null
                when (a) {
                    0 -> picking = id
                    1 -> { naming = id; name = Profiles.list.firstOrNull { it.id == id }?.let { Profiles.displayName(it) } ?: "" }
                    2 -> if (Profiles.remove(ctx, id)) ProfileAvatars.set(ctx, id, null)
                }
            }
        }
        picking?.let { id -> AvatarPicker(id) { picking = null } }
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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ProfileTile(name: String, avatar: String, ring: Color, locked: Boolean, manage: Boolean, master: Boolean, small: Boolean, modifier: Modifier, onLong: () -> Unit = {}, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (f) 1.08f else 1f, androidx.compose.animation.core.tween(160), label = "pt")
    // Rounded picture tile (as on Netflix / Apple TV): the portrait fills it, focus = white frame and a slight lift
    val shape = RoundedCornerShape(if (small) 18.dp else 22.dp)
    Column(
        modifier.onFocusChanged { f = it.isFocused }.combinedClickable(onClick = onClick, onLongClick = onLong).graphicsLayer { scaleX = s; scaleY = s },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(if (small) 128.dp else 168.dp)) {
            Box(
                Modifier.fillMaxSize().clip(shape).background(ring.copy(alpha = 0.85f))
                    .border(if (f) 4.dp else 0.dp, Color.White, shape),
                contentAlignment = Alignment.Center
            ) {
                Text(name.take(1).uppercase(), color = Color.White, fontSize = 60.sp, fontWeight = FontWeight.ExtraBold, raw = true)
                coil.compose.AsyncImage(avatar, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                if (!f) Box(Modifier.fillMaxSize().background(Color(0x26000000)))
            }
            if (locked || manage) Box(
                Modifier.align(Alignment.BottomEnd).padding(6.dp).size(36.dp).clip(CircleShape).background(Color(0xE61C1C20)),
                contentAlignment = Alignment.Center
            ) { AppIcon(if (manage) AIcon.Edit else AIcon.Lock, Color.White, size = 18.dp) }
        }
        Spacer(Modifier.height(16.dp))
        Text(name, color = if (f) Color.White else Color(0xB3FFFFFF), fontSize = if (small) 19.sp else 22.sp, fontWeight = if (f) FontWeight.Bold else FontWeight.SemiBold, maxLines = 1, raw = true)
        if (master) Text("MASTER", color = Color(0x66FFFFFF), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, raw = true)
    }
}

@Composable
private fun AddTile(small: Boolean, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (f) 1.08f else 1f, androidx.compose.animation.core.tween(160), label = "pa")
    Column(
        Modifier.onFocusChanged { f = it.isFocused }.clickable(onClick = onClick).graphicsLayer { scaleX = s; scaleY = s },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(if (small) 128.dp else 168.dp).clip(RoundedCornerShape(if (small) 18.dp else 22.dp)).background(Color(0x14FFFFFF))
                .border(3.dp, if (f) Color.White else Color(0x33FFFFFF), RoundedCornerShape(if (small) 18.dp else 22.dp)),
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
