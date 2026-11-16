package com.ipvio.stream

import androidx.compose.animation.core.animateFloat
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
import kotlinx.coroutines.launch

/**
 * "Who's watching?" (Netflix style): slowly drifting poster wall, Fof TV mark with greeting and time at the top,
 * avatar tiles with names, a "+" tile to add a profile and "Manage profiles" (rename, picture, delete). Profiles share the IPTV login; history, favorites and settings are per profile.
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
    var addAfterPin by remember { mutableStateOf(false) }
    var pinErr by remember { mutableStateOf<String?>(null) }
    var options by remember { mutableStateOf<Int?>(null) }
    var phone by remember { mutableStateOf(false) }
    var changePin by remember { mutableStateOf<Int?>(null) }
    // New profile: name, then picture, then it opens (PIN create/skip and the optional taste questions follow)
    var newFlow by remember { mutableStateOf<Int?>(null) }
    // New profile, after the picture: who it is for (kids = adult content hidden)
    var audience by remember { mutableStateOf<Int?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val first = remember { FocusRequester() }
    val startIdx = remember { Profiles.list.indexOfFirst { it.id == Profiles.active }.coerceAtLeast(0) }
    LaunchedEffect(naming, picking) {
        if (naming != null || picking != null) return@LaunchedEffect
        delay(120)
        repeat(8) { if (runCatching { first.requestFocus() }.getOrDefault(false)) return@LaunchedEffect; delay(80) }
    }
    FofAccount.init(ctx)
    // The account's profiles and their PIN state (new profiles from other TVs appear here)
    LaunchedEffect(FofAccount.signedInState) { if (FofAccount.signedInState) AccountCloud.refreshPins(ctx) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        PosterWall()
        // Top bar: Fof TV mark on the left, greeting and time on the right
        val now by produceState(System.currentTimeMillis()) { while (true) { value = System.currentTimeMillis(); delay(15_000) } }
        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 56.dp, vertical = 36.dp), verticalAlignment = Alignment.CenterVertically) {
            FofLogo(44.dp, 30.sp)
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(android.text.format.DateFormat.getTimeFormat(ctx).format(java.util.Date(now)), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, raw = true)
                Text(greeting(now), color = Color(0x99FFFFFF), fontSize = 16.sp, raw = true)
            }
        }
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (manage) "Manage profiles" else "Who's watching?", color = Color.White, fontSize = 52.sp, fontWeight = FontWeight.Bold, raw = true)
            // The account this TV is signed in to, as a quiet line under the title
            Spacer(Modifier.height(8.dp))
            if (FofAccount.signedInState) Text("Fof TV account · " + FofAccount.email.orEmpty(), color = Color(0x99FFFFFF), fontSize = 16.sp, raw = true)
            // Not signed in: say plainly what is missing (this TV shows nothing from the account until it signs in)
            else Text("This TV is not signed in. Sign in to bring your profiles, addons and Continue Watching here.",
                color = Color(0xCCFFFFFF), fontSize = 17.sp, raw = true)
            Spacer(Modifier.height(56.dp))
            // Up to 10 profiles: one row up to 5, then two rows; the "+" tile adds one (master PIN asked when set)
            // Signed in: only the account's profiles here; this TV's own older profiles are listed apart below
            val lo = if (FofAccount.signedInState) AccountCloud.localOnly(ctx) else emptySet()
            val tiles: List<Int?> = Profiles.list.indices.filter { Profiles.list[it].id !in lo } +
                (if (Profiles.list.size < Profiles.MAX) listOf(null) else emptyList())
            val big = tiles.size > 5
            Column(verticalArrangement = Arrangement.spacedBy(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                tiles.chunked(if (big) (tiles.size + 1) / 2 else 5).forEach { rowTiles ->
                    Row(horizontalArrangement = Arrangement.spacedBy(if (big) 30.dp else 44.dp), verticalAlignment = Alignment.Top) {
                        rowTiles.forEach { i ->
                            if (i == null) AddTile(big) {
                                if (!manage && Parental.profileLocked(ctx, 0)) { addAfterPin = true; askPin = true; pinErr = null }
                                else if (Profiles.add(ctx)) { naming = Profiles.list.last().id; newFlow = naming; name = "" }
                            }
                            else {
                                val p = Profiles.list[i]
                                val nm = Profiles.displayName(p)
                                val av = remember(p.id, ProfileAvatars.version) { ProfileAvatars.presetFor(ctx, p.id) }
                                val pinned = AccountCloud.cloudId(ctx, p.id)?.let { AccountCloud.pins[it]?.set } == true
                                val owner = FofAccount.signedInState && AccountCloud.cloudId(ctx, p.id)?.let { it == AccountCloud.ownerCloudId } == true
                                ProfileTile(
                                    if (owner) "$nm · Owner" else nm, av, Parental.profileLocked(ctx, p.id) || pinned, manage, big,
                                    if (i == startIdx) Modifier.focusRequester(first) else Modifier,
                                    // Hold OK on a profile: choose its picture right here (a locked profile asks in Manage)
                                    onLong = { if (!Parental.profileLocked(ctx, p.id)) picking = p.id }
                                ) { if (manage) options = p.id else onPick(p.id) }
                            }
                        }
                    }
                }
            }
            // This TV's own profiles from before it signed in: not part of the account, never mixed into it
            val loList = Profiles.list.filter { it.id in lo }
            if (loList.isNotEmpty()) {
                Spacer(Modifier.height(28.dp))
                Text("On this TV only (not in your account)", color = Color(0x80FFFFFF), fontSize = 15.sp, raw = true)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    loList.forEach { p -> OutlinePill(Profiles.displayName(p) + (if (manage) "  ·  Add to account" else "")) {
                        if (manage) { AccountCloud.addToAccount(ctx, p.id); note = "Adding " + Profiles.displayName(p) + " to your account …" } else onPick(p.id)
                    } }
                }
            }
            Spacer(Modifier.height(if (big) 40.dp else 64.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // Not signed in: one sign-in for the whole TV (email and password on the phone); the account's profiles appear
            if (!manage && !FofAccount.signedInState) OutlinePill("Sign in") { phone = true }
            // Sign out lives in Manage profiles: the main screen only picks a profile
            if (manage && FofAccount.signedInState) OutlinePill("Sign out of this TV") { FofAccount.signOut(ctx); manage = false }
            OutlinePill(if (manage) "Done" else "Manage Profiles") {
                if (manage) manage = false
                else if (Parental.profileLocked(ctx, 0)) { askPin = true; pinErr = null }
                else manage = true
            }
            }
        }
        if (phone) {
            LaunchedEffect(FofAccount.signedInState) { if (FofAccount.signedInState) { phone = false; AccountCloud.refreshPins(ctx) } }
            FofSignInScreen(start = "signin", onSkip = null, onClose = { phone = false })
        }
        note?.let { n ->
            LaunchedEffect(n) { delay(4_000); note = null }
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xE61C1C20)).padding(horizontal = 22.dp, vertical = 14.dp)) {
                Text(n, color = Color.White, fontSize = 17.sp, raw = true)
            }
        }
        changePin?.let { id -> ProfileGate(id, change = true, onOpen = { changePin = null; note = "PIN saved" }, onCancel = { changePin = null }) }
        if (askPin) Box(Modifier.fillMaxSize().background(Color(0xE6000000)), contentAlignment = Alignment.Center) {
            Box(Modifier.width(420.dp)) {
                PinPad("Master PIN", "Only the master profile can add or manage profiles.", pinErr,
                    { if (Parental.verifyFor(ctx, 0, it)) {
                        askPin = false
                        if (addAfterPin) { addAfterPin = false; if (Profiles.add(ctx)) { naming = Profiles.list.last().id; newFlow = naming; name = "" } } else manage = true
                    } else pinErr = Parental.pinError() },
                    { askPin = false; addAfterPin = false })
            }
        }
        options?.let { id ->
            val acts = listOf("Change avatar", "Rename", "Your taste", "Reset taste") +
                (if (FofAccount.signedInState) listOf("Change PIN") else emptyList()) +
                (if (id != 0) listOf("Delete profile") else emptyList())
            OptionPopup(acts, -1, { options = null }) { a ->
                options = null
                when (acts[a]) {
                    "Change avatar" -> picking = id
                    "Rename" -> { naming = id; name = Profiles.list.firstOrNull { it.id == id }?.let { Profiles.displayName(it) } ?: "" }
                    // The questions come back the next time this profile is picked
                    "Your taste" -> { Taste.reset(ctx, id); manage = false; onPick(id) }
                    "Change PIN" -> changePin = id
                    // Start the taste setup from scratch the next time this profile is opened
                    "Reset taste" -> { Taste.clearAll(ctx, id); note = "Taste reset – the setup starts fresh next time" }
                    "Delete profile" -> scope.launch {
                        // In the account: removed there first (owner's profile or that profile open on this TV), then here
                        val e = if (FofAccount.signedIn) AccountCloud.deleteProfile(ctx, id) else null
                        if (e != null) note = e
                        else if (id == Profiles.active) note = "Open another profile first, then remove this one."
                        else if (Profiles.remove(ctx, id)) ProfileAvatars.set(ctx, id, null)
                    }
                }
            }
        }
        picking?.let { id -> AvatarPicker(id) { picking = null; if (newFlow == id) { newFlow = null; audience = id } } }
        audience?.let { id ->
            androidx.activity.compose.BackHandler { audience = null; onPick(id) }
            Box(Modifier.fillMaxSize().background(Color(0xE6000000)), contentAlignment = Alignment.Center) {
                Column(Modifier.width(720.dp).glass(RoundedCornerShape(DS.RadiusL), 2).padding(34.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text("Step 3 of 4 · Who is this profile for?", color = Color(0x99FFFFFF), fontSize = 16.sp, raw = true)
                    Text("Who is this profile for?", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold, raw = true)
                    Text("Kids profiles hide adult channels and titles. You can change this later in Settings → Parental control.", color = Color(0xB3FFFFFF), fontSize = 17.sp, raw = true)
                    val f = remember { FocusRequester() }
                    LaunchedEffect(Unit) { delay(80); runCatching { f.requestFocus() } }
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        PillButton("Everyone", Modifier.focusRequester(f), light = true, icon = AIcon.Person, raw = true) { Parental.setHideFor(ctx, id, false); audience = null; onPick(id) }
                        PillButton("Kids", icon = AIcon.Star, raw = true) { Parental.setHideFor(ctx, id, true); audience = null; onPick(id) }
                    }
                    Text("Next: an optional PIN, then a few quick taste questions you can skip.", color = Color(0x80FFFFFF), fontSize = 15.sp, raw = true)
                }
            }
        }
        naming?.let { id ->
            Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
                Column(
                    Modifier.width(520.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF141416)).padding(28.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    if (newFlow == id) Text("Step 1 of 4 · New profile", color = Color(0x99FFFFFF), fontSize = 16.sp, raw = true)
                    Text("Profile name", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, raw = true)
                    Field(name, { name = it }, "Name", autoOpen = true, onDone = { Profiles.rename(ctx, id, name); naming = null; if (newFlow == id) picking = id })
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PillButton("Save", light = true, icon = AIcon.Check) { Profiles.rename(ctx, id, name); naming = null; if (newFlow == id) picking = id }
                        // Cancel while creating: the half-made profile goes away again
                        PillButton("Cancel") { if (newFlow == id && id != Profiles.active) Profiles.remove(ctx, id); naming = null; newFlow = null }
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ProfileTile(name: String, avatar: ProfileAvatars.Preset, locked: Boolean, manage: Boolean, small: Boolean, modifier: Modifier, onLong: () -> Unit = {}, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (f) 1.08f else 1f, androidx.compose.animation.core.tween(160), label = "pt")
    // Avatar tile (as on Netflix): focus = white frame and a slight lift; the others are dimmed a little
    val shape = RoundedCornerShape(if (small) 14.dp else 18.dp)
    Column(
        modifier.onFocusChanged { f = it.isFocused }.combinedClickable(onClick = onClick, onLongClick = onLong).graphicsLayer { scaleX = s; scaleY = s },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(if (small) 136.dp else 180.dp)) {
            Box(Modifier.fillMaxSize().clip(shape).border(if (f) 2.dp else 1.dp, if (f) FofGlass.rim() else FofGlass.edge(false), shape)) {
                FofAvatar(avatar, Modifier.fillMaxSize())
                if (!f) Box(Modifier.fillMaxSize().background(Color(0x33000000)))
            }
            if (locked || manage) Box(
                Modifier.align(Alignment.BottomEnd).padding(8.dp).size(38.dp).clip(CircleShape).background(Color(0xE61C1C20)),
                contentAlignment = Alignment.Center
            ) { AppIcon(if (manage) AIcon.Edit else AIcon.Lock, Color.White, size = 18.dp) }
        }
        Spacer(Modifier.height(18.dp))
        Text(name, color = if (f) Color.White else Color(0x99FFFFFF), fontSize = if (small) 21.sp else 26.sp, fontWeight = if (f) FontWeight.Bold else FontWeight.Medium, maxLines = 1, raw = true)
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
            Modifier.size(if (small) 136.dp else 180.dp).clip(RoundedCornerShape(if (small) 14.dp else 18.dp))
                .background(if (f) Color(0x26FFFFFF) else Color(0x0FFFFFFF))
                .border(if (f) 4.dp else 2.dp, if (f) Color.White else Color(0x26FFFFFF), RoundedCornerShape(if (small) 14.dp else 18.dp)),
            contentAlignment = Alignment.Center
        ) { Text("+", color = if (f) Color.White else Color(0x99FFFFFF), fontSize = 72.sp, fontWeight = FontWeight.Light, raw = true) }
        Spacer(Modifier.height(18.dp))
        Text("Add profile", color = if (f) Color.White else Color(0x99FFFFFF), fontSize = if (small) 21.sp else 26.sp, fontWeight = if (f) FontWeight.Bold else FontWeight.Medium, raw = true)
    }
}

@Composable
private fun OutlinePill(label: String, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Box(
        Modifier.onFocusChanged { f = it.isFocused }.then(if (f) Modifier.clip(shape).background(Color.White) else Modifier.glass(shape, 0)).clickable(onClick = onClick)
            .padding(horizontal = 34.dp, vertical = 14.dp)
    ) { Text(label, color = if (f) Color.Black else Color(0xB3FFFFFF), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, raw = true) }
}

/** "Good morning" until noon, "Good afternoon" until 6 pm, then "Good evening" (device time). */
private fun greeting(now: Long): String {
    val h = java.util.Calendar.getInstance().apply { timeInMillis = now }.get(java.util.Calendar.HOUR_OF_DAY)
    return when { h in 5..11 -> "Good morning"; h in 12..17 -> "Good afternoon"; else -> "Good evening" }
}

/** Slowly drifting poster wall faded into black (welcome, sign-in and "Who's watching?"). */
@Composable
internal fun PosterWall() {
    // Poster wall behind the profiles (the catalog's popular titles, already on the device), faded into black
    val wall = remember {
    val m = Catalog.cached(Catalog.popular(Kind.MOVIE)); val s = Catalog.cached(Catalog.popular(Kind.SERIES))
    (0 until maxOf(m.size, s.size)).flatMap { listOfNotNull(m.getOrNull(it), s.getOrNull(it)) }.mapNotNull { it.logo }.distinct().take(40)
    }
    // The wall drifts slowly sideways (layer move only: nothing is redrawn, smooth on a Fire TV)
    val drift = androidx.compose.animation.core.rememberInfiniteTransition(label = "wall")
    val dx by drift.animateFloat(0f, 1f, androidx.compose.animation.core.infiniteRepeatable(
        androidx.compose.animation.core.tween(70_000, easing = androidx.compose.animation.core.LinearEasing),
        androidx.compose.animation.core.RepeatMode.Reverse), label = "dx")
    if (wall.size >= 8) Column(Modifier.fillMaxSize().graphicsLayer {
        alpha = 0.24f; rotationZ = -6f; scaleX = 1.3f; scaleY = 1.3f
        translationX = -dx * 260.dp.toPx(); translationY = -dx * 40.dp.toPx()
    }, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        wall.chunked(10).forEach { row ->
            Row(Modifier.wrapContentWidth(Alignment.Start, unbounded = true), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                row.forEach { u -> coil.compose.AsyncImage(u, null, Modifier.width(150.dp).height(225.dp).clip(RoundedCornerShape(10.dp)),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
            }
        }
    }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xCC000000), Color(0xE6000000), Color.Black))))
    // Soft light from above instead of a colored glow
    Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0x1FFFFFFF), Color.Transparent),
        center = androidx.compose.ui.geometry.Offset(960f, 0f), radius = 1200f)))
}
