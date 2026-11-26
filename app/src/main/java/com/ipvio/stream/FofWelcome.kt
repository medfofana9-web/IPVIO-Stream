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
    // A profile saved on this TV before sign-in: add it to the account or remove it from this TV (explicit)
    var legacy by remember { mutableStateOf<Int?>(null) }
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
    val wall by produceState(emptyList<String>()) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                Net.base.newCall(okhttp3.Request.Builder().url("https://v3-cinemeta.strem.io/catalog/movie/top.json").build()).execute().use { r ->
                    val a = org.json.JSONObject(r.body!!.string()).getJSONArray("metas")
                    (0 until minOf(a.length(), 24)).mapNotNull { i -> a.getJSONObject(i).optString("id").takeIf { it.startsWith("tt") } }
                        .map { "https://images.metahub.space/background/medium/$it/img" }
                }
            }.getOrDefault(emptyList())
        }
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF0B0D12))) {
        if (wall.isNotEmpty() && !Lite.on) Column(
            Modifier.fillMaxSize().graphicsLayer { rotationZ = -6f; scaleX = 1.25f; scaleY = 1.25f; alpha = 0.3f },
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            wall.chunked(6).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { u -> coil.compose.AsyncImage(u, null, Modifier.weight(1f).aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
            } }
        }
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0x8C0B0D12), Color(0xFF0B0D12)), radius = 1500f)))
        // Top bar: Fof TV mark on the left, greeting and time on the right
        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 56.dp, vertical = 36.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("FOF TV", color = Color(0xFFE60023), fontSize = 40.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp, raw = true)
            Spacer(Modifier.weight(1f))
        }
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (manage) "Manage Profiles" else "Who's watching?", color = Color.White,
                fontSize = 58.sp, fontWeight = FontWeight.Bold, raw = true)
            // The account this TV is signed in to, as a quiet line under the title
            Spacer(Modifier.height(8.dp))
            // Not signed in: say plainly what is missing (this TV shows nothing from the account until it signs in)
            if (!FofAccount.signedInState) Text("This TV is not signed in. Sign in to bring your profiles, addons and Continue Watching here.",
                color = Color(0xCCFFFFFF), fontSize = 17.sp, raw = true)
            Spacer(Modifier.height(44.dp))
            // Up to 10 profiles: one row up to 5, then two rows; the "+" tile adds one (master PIN asked when set)
            // Signed in: only the account's profiles here; this TV's own older profiles are listed apart below
            val lo = if (FofAccount.signedInState) AccountCloud.localOnly(ctx) else emptySet()
            val tiles: List<Int?> = Profiles.list.indices.filter { Profiles.list[it].id !in lo } +
                (if (Profiles.list.size < Profiles.MAX) listOf(null) else emptyList())
            val big = tiles.size > 5
            // Focus starts on the last used profile; if that one is not shown here (saved on this TV only), the first tile
            val focusIdx = if (startIdx in tiles) startIdx else tiles.firstOrNull()
            Column(verticalArrangement = Arrangement.spacedBy(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                tiles.chunked(if (big) (tiles.size + 1) / 2 else 5).forEach { rowTiles ->
                    Row(horizontalArrangement = Arrangement.spacedBy(if (big) 30.dp else 44.dp), verticalAlignment = Alignment.Top) {
                        rowTiles.forEach { i ->
                            if (i == null) AddTile(big, if (focusIdx == null) Modifier.focusRequester(first) else Modifier) {
                                if (!manage && Parental.profileLocked(ctx, 0)) { addAfterPin = true; askPin = true; pinErr = null }
                                else if (Profiles.add(ctx)) { naming = Profiles.list.last().id; newFlow = naming; name = "" }
                            }
                            else {
                                val p = Profiles.list[i]
                                val nm = Profiles.displayName(p)
                                val av = remember(p.id, ProfileAvatars.version) { ProfileAvatars.presetFor(ctx, p.id) }
                                val pinned = AccountCloud.cloudId(ctx, p.id)?.let { AccountCloud.pins[it]?.set } == true
                                ProfileTile(
                                    nm, av, Parental.profileLocked(ctx, p.id) || pinned, manage, big,
                                    if (i == focusIdx) Modifier.focusRequester(first) else Modifier,
                                    kids = Parental.isKids(ctx, p.id),
                                    cont = remember(p.id) { lastWatched(ctx, p.id) },
                                    onLong = {}
                                ) { if (manage) options = p.id else onPick(p.id) }
                            }
                        }
                    }
                }
            }
            // This TV's own profiles from before it signed in: not part of the account, never mixed into it
            val loList = Profiles.list.filter { it.id in lo }
            // Shown in Manage profiles only, with an explicit choice (never merged on their own)
            if (manage && loList.isNotEmpty()) {
                Spacer(Modifier.height(28.dp))
                Text("Saved on this TV from before sign-in", color = Color(0x80FFFFFF), fontSize = 15.sp, raw = true)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    loList.forEach { p -> OutlinePill(Profiles.displayName(p)) { legacy = p.id } }
                }
            }
            Spacer(Modifier.height(if (big) 40.dp else 64.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // Not signed in: one sign-in for the whole TV (email and password on the phone); the account's profiles appear
            if (!manage && !FofAccount.signedInState) OutlinePill("Sign in") { phone = true }
            // Sign out lives in Manage profiles: the main screen only picks a profile
            if (manage && FofAccount.signedInState) OutlinePill("Sign out of this TV") { FofAccount.signOut(ctx); manage = false }
            SubtleAction(if (manage) "Done" else "Manage Profiles") {
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
        legacy?.let { id ->
            val nm = Profiles.list.firstOrNull { it.id == id }?.let { Profiles.displayName(it) } ?: ""
            val acts = listOf("Add \"$nm\" to my account as a new profile") + (if (id != 0 && id != Profiles.active) listOf("Remove from this TV") else emptyList()) + "Cancel"
            OptionPopup(acts, -1, { legacy = null }) { a ->
                legacy = null
                when {
                    acts[a].startsWith("Add") -> { AccountCloud.addToAccount(ctx, id); note = "Adding $nm to your account …" }
                    acts[a] == "Remove from this TV" -> if (Profiles.remove(ctx, id)) { ProfileAvatars.set(ctx, id, null); note = "$nm removed from this TV" }
                }
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
private fun ProfileTile(name: String, avatar: ProfileAvatars.Preset, locked: Boolean, manage: Boolean, small: Boolean, modifier: Modifier, kids: Boolean = false, cont: String? = null, onLong: () -> Unit = {}, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (f) 1.08f else 1f, androidx.compose.animation.core.tween(160), label = "pt")
    // Avatar tile (as on Netflix): focus = white frame and a slight lift; the others are dimmed a little
    // Round picture; the selected one gets a white ring
    val shape = CircleShape
    Column(
        modifier.width(if (small) 190.dp else 250.dp).onFocusChanged { f = it.isFocused }.combinedClickable(onClick = onClick, onLongClick = onLong).graphicsLayer { scaleX = s; scaleY = s },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(if (small) 150.dp else 200.dp)) {
            Box(Modifier.fillMaxSize().clip(shape)
                .border(if (f) 5.dp else 0.dp, if (f) Color.White else Color.Transparent, shape)) {
                FofAvatar(avatar, Modifier.fillMaxSize(), letterSize = if (small) 56.sp else 76.sp)

            }
            if (manage) Box(
                Modifier.align(Alignment.BottomEnd).padding(8.dp).size(38.dp).clip(CircleShape).background(Color(0xE61C1C20)),
                contentAlignment = Alignment.Center
            ) { AppIcon(AIcon.Edit, Color.White, size = 18.dp) }
        }
        Spacer(Modifier.height(18.dp))
        if (kids) Text("KIDS", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp, raw = true,
            modifier = Modifier.padding(bottom = 6.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFFE60023)).padding(horizontal = 8.dp, vertical = 2.dp))
        Text(name, color = if (f) Color.White else Color(0xFF808080),
            fontSize = if (small) 21.sp else 25.sp,
            fontWeight = FontWeight.Normal, maxLines = 1, raw = true)
        // Same as the Mac app: "🔒 PIN" under the name of a locked profile
        if (locked && !manage) Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AppIcon(AIcon.Lock, Color(0xFFD4A017), size = 15.dp)
            Text("PIN", color = Color(0xFF808080), fontSize = 16.sp, raw = true)
        }
        // What this profile was last watching (same as the Mac and phone)
        if (cont != null && !manage) Text("Continue: $cont", color = Color(0xFF9A9A9A), fontSize = 14.sp, maxLines = 2, raw = true,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun AddTile(small: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (f) 1.08f else 1f, androidx.compose.animation.core.tween(160), label = "pa")
    Column(
        modifier.onFocusChanged { f = it.isFocused }.clickable(onClick = onClick).graphicsLayer { scaleX = s; scaleY = s },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(if (small) 150.dp else 200.dp).clip(CircleShape)
                .border(if (f) 5.dp else 2.dp, if (f) Color.White else Color(0x40FFFFFF), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.size(if (small) 76.dp else 100.dp).clip(CircleShape).background(if (f) Color.White else Color(0xFF808080)), contentAlignment = Alignment.Center) {
                Text("+", color = Color(0xFF141414), fontSize = if (small) 52.sp else 66.sp, fontWeight = FontWeight.Bold, raw = true)
            }
        }
        Spacer(Modifier.height(18.dp))
        Text("Add Profile", color = if (f) Color.White else Color(0xFF808080), fontSize = if (small) 21.sp else 25.sp, fontWeight = FontWeight.Normal, raw = true)
    }
}

@Composable
private fun OutlinePill(label: String, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Box(
        Modifier.onFocusChanged { f = it.isFocused }.background(if (f) Color.White else Color.Transparent)
            .border(1.dp, if (f) Color.White else Color(0xFF808080)).clickable(onClick = onClick)
            .padding(horizontal = 34.dp, vertical = 12.dp)
    ) { Text(label.uppercase(), color = if (f) Color.Black else Color(0xFF808080), fontSize = 19.sp, fontWeight = FontWeight.Medium, letterSpacing = 3.sp, raw = true) }
}

/** "Good morning" until noon, "Good afternoon" until 6 pm, then "Good evening" (device time). */
private fun greeting(now: Long): String {
    val h = java.util.Calendar.getInstance().apply { timeInMillis = now }.get(java.util.Calendar.HOUR_OF_DAY)
    return when { h in 5..11 -> "Good morning"; h in 12..17 -> "Good afternoon"; else -> "Good evening" }
}

/** Slowly drifting poster wall faded into black (welcome, sign-in and "Who's watching?"). */
@Composable
internal fun PosterWall() {
    // Keep the cinematic poster wall, but decode fewer offscreen posters on low-memory sticks.
    // The same cached thumbnails and slow GPU-only drift remain; no live blur or new animation.
    val ctx = LocalContext.current
    val maxPosters = remember(ctx) { when (Mem.tier(ctx)) { 0 -> 20; 1 -> 30; else -> 40 } }
    val wall = remember(maxPosters) {
        val m = Catalog.cached(Catalog.popular(Kind.MOVIE))
        val s = Catalog.cached(Catalog.popular(Kind.SERIES))
        (0 until maxOf(m.size, s.size)).flatMap { listOfNotNull(m.getOrNull(it), s.getOrNull(it)) }
            .mapNotNull { it.logo }.distinct().take(maxPosters)
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

/** Quiet text action under the profiles (Manage Profiles, Done): no box until focused. */
@Composable
private fun SubtleAction(label: String, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Row(Modifier.onFocusChanged { f = it.isFocused }.background(if (f) Color.White else Color.Transparent)
        .border(1.dp, if (f) Color.White else Color(0xFF808080)).clickable(onClick = onClick).padding(horizontal = 34.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label.uppercase(), color = if (f) Color.Black else Color(0xFF808080), fontSize = 19.sp, fontWeight = FontWeight.Medium, letterSpacing = 3.sp, raw = true)
    }
}


/**
 * TryBox-style backdrop for "Who's watching?": one cinematic still of today's most popular title, in black and white,
 * dimmed, with that title's logo at the bottom left. Falls back to the poster wall until the catalog is cached.
 */
@Composable
internal fun PickerBackdrop() {
    val pick = remember {
        (Catalog.cached(Catalog.popular(Kind.MOVIE)).take(6) + Catalog.cached(Catalog.popular(Kind.SERIES)).take(4)).shuffled().firstOrNull()
    }
    if (pick == null) { PosterWall(); return }
    val m = rememberMeta(pick)
    val full = rememberFullMeta(pick, m)
    val url = m?.backdrop
    if (url == null) { PosterWall(); return }
    val gray = remember { androidx.compose.ui.graphics.ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix().apply { setToSaturation(0f) }) }
    coil.compose.AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop, colorFilter = gray)
    Box(Modifier.fillMaxSize().background(Color(0x8C000000)))
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x99000000), 0.3f to Color.Transparent, 0.7f to Color.Transparent, 1f to Color(0xE6000000))))
    Box(Modifier.fillMaxSize().padding(start = 56.dp, bottom = 48.dp), contentAlignment = Alignment.BottomStart) {
        Box(Modifier.alpha(0.85f)) { HeroTitle(pick, full, 52, 340.dp, 110.dp) }
    }
}

/** Last thing a profile watched (its own Continue Watching store), as "Show · S4 E10"; null when nothing. */
private fun lastWatched(ctx: android.content.Context, id: Int): String? = runCatching {
    val raw = ctx.applicationContext.getSharedPreferences(Profiles.scopedName("ipvio_watch", id), android.content.Context.MODE_PRIVATE).getString("e", null) ?: return null
    val a = org.json.JSONArray(raw)
    var best: org.json.JSONObject? = null
    for (i in 0 until a.length()) { val o = a.getJSONObject(i); if (best == null || o.optLong("ts") > best.optLong("ts")) best = o }
    val b = best ?: return null
    val t = b.optString("t").replace(Regex("\\s*S\\d+\\s*E\\d+\\s*$", RegexOption.IGNORE_CASE), "").trim()
    if (t.isBlank()) null else t + (if (b.optInt("s") > 0 || b.optInt("e") > 0) " · S${b.optInt("s")} E${b.optInt("e")}" else "")
}.getOrNull()
