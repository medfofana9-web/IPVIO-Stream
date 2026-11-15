package com.ipvio.stream

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Settings > Account & devices (T-007): the account this TV is signed in to, "Ask for the PIN every time",
 * the signed-in devices (rename, sign out one, sign out all others) and signing this TV out.
 */
@Composable
internal fun AccountPage(ctx: Context) {
    val scope = rememberCoroutineScope()
    if (!FofAccount.signedInState) {
        SectionTitle("Account")
        Text("This TV is not signed in. Sign in once and every profile, addon and Continue Watching of the account comes to this TV.",
            color = Muted, fontSize = 15.sp, raw = true)
        PillButton("Sign in", light = true, icon = AIcon.Phone, raw = true) { Profiles.restartToPicker(ctx) }
        return
    }
    var devices by remember { mutableStateOf<List<AccountCloud.Device>?>(null) }
    var reloads by remember { mutableIntStateOf(0) }
    var msg by remember { mutableStateOf<String?>(null) }
    var options by remember { mutableStateOf<AccountCloud.Device?>(null) }
    var renaming by remember { mutableStateOf<AccountCloud.Device?>(null) }
    var newName by remember { mutableStateOf("") }
    LaunchedEffect(reloads) { devices = AccountCloud.devices(ctx) }
    val me = remember { AccountCloud.deviceId(ctx) }

    SectionTitle("Account")
    Text(FofAccount.email.orEmpty(), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, raw = true)
    Text("Profiles, addons, the provider login and everyone's progress are saved in this account and come to every TV signed in to it.",
        color = Muted, fontSize = 14.sp, raw = true)
    ToggleRow("Ask for the PIN every time", "Also for the profile last used on a TV. Off: a TV opens its last profile without the PIN.", AccountCloud.pinAlways) { v ->
        scope.launch { AccountCloud.setPinAlways(ctx, v)?.let { msg = it } }
    }

    SectionTitle("Connected devices")
    val list = devices
    when {
        list == null -> Text("Loading…", color = Muted, fontSize = 15.sp, raw = true)
        list.isEmpty() -> Text("No devices found.", color = Muted, fontSize = 15.sp, raw = true)
        else -> list.forEach { d -> DeviceRow(d, d.id == me) { options = d } }
    }
    msg?.let { Text(it, color = Accent2, fontSize = 15.sp, raw = true) }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ConfirmPill("Sign out all other devices") {
            scope.launch {
                msg = if (AccountCloud.signOutOthers(ctx)) "All other devices are signed out." else "That did not work. Check the connection."
                reloads++
            }
        }
        ConfirmPill("Sign out of this TV") { FofAccount.signOut(ctx); Profiles.restartToPicker(ctx) }
    }
    val st = listOf(AccountCloud.status, CloudSync.status).filter { it.isNotBlank() }.joinToString(" · ")
    if (st.isNotBlank()) Text(st, color = Muted, fontSize = 13.sp, raw = true)

    options?.let { d ->
        val acts = listOf("Rename", if (d.id == me) "Sign out of this TV" else "Sign out this device")
        OptionPopup(acts, -1, { options = null }) { a ->
            options = null
            when (a) {
                0 -> { renaming = d; newName = d.name }
                1 -> if (d.id == me) { FofAccount.signOut(ctx); Profiles.restartToPicker(ctx) }
                    else scope.launch {
                        msg = if (AccountCloud.revokeDevice(ctx, d.id)) "${d.name.ifBlank { "The device" }} is signed out." else "That did not work. Check the connection."
                        reloads++
                    }
            }
        }
    }
    renaming?.let { d ->
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Name for this device", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, raw = true)
            Field(newName, { newName = it.take(40) }, "For example Living room", autoOpen = true)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton("Save", light = true, icon = AIcon.Check, raw = true) {
                    val n = newName
                    renaming = null
                    scope.launch { if (!AccountCloud.renameDevice(ctx, d.id, n)) msg = "Not saved. Check the connection."; reloads++ }
                }
                PillButton("Cancel", raw = true) { renaming = null }
            }
        }
    }
}

@Composable
private fun DeviceRow(d: AccountCloud.Device, mine: Boolean, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(DS.RadiusM)
    Row(
        Modifier.fillMaxWidth().widthIn(max = 760.dp).glass(shape, 1, f)
            .onFocusChanged { f = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(d.name.ifBlank { d.kind.ifBlank { "Device" } }, color = if (d.revoked) Muted else Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, raw = true, maxLines = 1)
            val sub = listOf(d.kind, if (d.revoked) "Signed out" else lastSeenText(d.lastSeen), d.version.takeIf { it.isNotBlank() }?.let { "App $it" })
                .filter { !it.isNullOrBlank() }.joinToString(" · ")
            Text(sub, color = Muted, fontSize = 13.sp, raw = true, maxLines = 1)
        }
        if (mine) Box(Modifier.clip(RoundedCornerShape(50)).background(Color(0x22FFFFFF)).padding(horizontal = 12.dp, vertical = 5.dp)) {
            Text("This TV", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, raw = true)
        }
    }
}

/** "Active now", "12 min ago", "3 h ago", "2 days ago" from the server time (UTC). */
private fun lastSeenText(iso: String): String {
    val t = runCatching {
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(iso.take(19))?.time
    }.getOrNull() ?: return ""
    val m = ((System.currentTimeMillis() - t) / 60_000).coerceAtLeast(0)
    return when {
        m < 5 -> "Active now"
        m < 60 -> "$m min ago"
        m < 24 * 60 -> "${m / 60} h ago"
        else -> "${m / (24 * 60)} days ago"
    }
}
