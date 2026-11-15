package com.ipvio.stream

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Opening a profile of the account (T-007): its PIN is checked by the server. A profile without a PIN gets one first
 * (existing profiles from before, new profiles). The profile last opened on this TV opens without the PIN, unless the
 * owner turned on "Ask for the PIN every time". "Forgot PIN": the account password allows a new PIN.
 * [change] = "Change PIN" from Manage profiles: ends after the new PIN is saved instead of opening the profile.
 */
@Composable
fun ProfileGate(id: Int, change: Boolean = false, onOpen: () -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // load, create, again, enter, forgot, busy
    var mode by remember { mutableStateOf("load") }
    var first by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var reset by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    val name = Profiles.list.firstOrNull { it.id == id }?.let { Profiles.displayName(it) } ?: ""
    val av = remember(id, ProfileAvatars.version) { ProfileAvatars.presetFor(ctx, id) }

    fun open() {
        if (change) { onOpen(); return }
        mode = "busy"
        scope.launch {
            // The profile's own data comes first (Continue watching, taste), so the next screens show it
            Profiles.select(ctx, id)
            withTimeoutOrNull(8_000) { CloudSync.run(ctx.applicationContext) }
            onOpen()
        }
    }

    LaunchedEffect(id) {
        withTimeoutOrNull(6_000) { AccountCloud.refreshPins(ctx) }
        val cid = AccountCloud.cloudId(ctx, id)
        // Not in the account (yet): a profile only on this TV, nothing to check
        if (cid == null) { if (change) { err = "This profile is not in the account yet."; mode = "enter" } else open(); return@LaunchedEffect }
        val st = AccountCloud.pins[cid]
        val trusted = st?.unlocked == true && AccountCloud.openedHere(ctx) == id && !AccountCloud.pinAlways
        mode = when {
            st == null -> if (!change && AccountCloud.openedHere(ctx) == id) { open(); "busy" } else "enter"
            !st.set && !change && AccountCloud.noPin(ctx, cid) -> { open(); "busy" }
            !st.set -> "create"
            change && st.unlocked -> "create"
            !change && trusted -> { open(); "busy" }
            else -> { if (st.waitS > 0) err = "Too many tries. " + AccountCloudLogic.waitText(st.waitS); "enter" }
        }
    }

    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF111218), Color(0xFF050507)))),
        contentAlignment = Alignment.Center
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(64.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(170.dp).clip(RoundedCornerShape(18.dp))) { FofAvatar(av, Modifier.fillMaxSize()) }
                Spacer(Modifier.height(16.dp))
                Text(name, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, raw = true, maxLines = 1)
            }
            Box(Modifier.width(440.dp)) {
                when (mode) {
                    "load", "busy" -> Text(if (mode == "busy") "Opening $name…" else "One moment…", color = Color(0xB3FFFFFF), fontSize = 22.sp, raw = true)
                    "create" -> key("create") {
                        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                            PinPad(if (reset) "Choose a new PIN" else "Create a PIN for $name",
                                "4 to 6 digits, then OK. It opens this profile on every TV.", err,
                                { p -> first = p; err = null; mode = "again" }, onCancel, minLen = 4, maxLen = 6, raw = true)
                            // The PIN is optional: a profile can stay open (it can get a PIN later in Manage profiles)
                            if (!reset && !change) PillButton("No PIN for this profile", raw = true) {
                                AccountCloud.cloudId(ctx, id)?.let { AccountCloud.setNoPin(ctx, it) }
                                open()
                            }
                        }
                    }
                    "again" -> key("again") {
                        PinPad("Enter the PIN again", null, err, { p ->
                            if (p != first) { err = "The two PINs were different. Try again."; mode = "create"; return@PinPad }
                            mode = "busy"
                            scope.launch {
                                val e = AccountCloud.setPin(ctx, id, p)
                                if (e == null) open() else { err = e; mode = "create" }
                            }
                        }, { mode = "create" }, minLen = 4, maxLen = 6, raw = true)
                    }
                    "enter" -> key("enter") {
                        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                            PinPad(if (change) "Enter the current PIN" else "Enter the PIN", null, err, { p ->
                                mode = "busy"
                                scope.launch {
                                    when (val r = AccountCloud.verifyPin(ctx, id, p)) {
                                        is AccountCloud.PinResult.Ok -> if (change) { err = null; mode = "create" } else open()
                                        is AccountCloud.PinResult.Wrong -> { err = if (r.left > 0) "Wrong PIN. ${r.left} more ${if (r.left == 1) "try" else "tries"}." else "Wrong PIN."; mode = "enter" }
                                        is AccountCloud.PinResult.Locked -> { err = "Too many tries. " + AccountCloudLogic.waitText(r.waitS); mode = "enter" }
                                        is AccountCloud.PinResult.Offline ->
                                            if (!change && AccountCloud.openedHere(ctx) == id) open()
                                            else { err = "No connection. The PIN is checked online."; mode = "enter" }
                                        is AccountCloud.PinResult.Failed -> { if (r.text == "no_pin") { err = null; mode = "create" } else { err = r.text; mode = "enter" } }
                                    }
                                }
                            }, onCancel, minLen = 4, maxLen = 6, raw = true)
                            PillButton("Forgot PIN?", raw = true) { err = null; password = ""; mode = "forgot" }
                        }
                    }
                    "forgot" -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        androidx.activity.compose.BackHandler { err = null; mode = "enter" }
                        Text("Forgot the PIN?", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, raw = true)
                        Text("Enter the password of the Fof TV account (${FofAccount.email.orEmpty()}). Then choose a new PIN.",
                            color = Color(0xB3FFFFFF), fontSize = 15.sp, raw = true)
                        Field(password, { password = it }, "Account password", password = true, autoOpen = true)
                        err?.let { Text(it, color = DS.Danger, fontSize = 15.sp, raw = true) }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            PillButton("Continue", light = true, raw = true) {
                                val pw = password
                                mode = "busy"
                                scope.launch {
                                    val e = FofAccount.confirmPassword(ctx, pw)
                                    password = ""
                                    if (e == null) { reset = true; err = null; mode = "create" } else { err = e; mode = "forgot" }
                                }
                            }
                            PillButton("Back", raw = true) { err = null; mode = "enter" }
                        }
                    }
                }
            }
        }
    }
}
