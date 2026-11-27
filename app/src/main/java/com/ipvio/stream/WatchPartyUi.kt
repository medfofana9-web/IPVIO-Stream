package com.ipvio.stream

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Player panel: start a party for what is playing, show the code, who is watching, reactions, leave or end. */
@Composable
fun WatchPartyPanel(modifier: Modifier, start: suspend () -> String?, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s = WatchParty.state
    var busy by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(s?.code) { delay(80); runCatching { first.requestFocus() } }
    // Same look as the other player panels (player redesign, 27 Nov 2026): opaque dark card, hairline edge
    val shape = RoundedCornerShape(PT.PanelRadius)
    Column(modifier.width(580.dp).clip(shape).background(PT.PanelBg).border(1.dp, Color(0x1AFFFFFF), shape).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Watch together", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, raw = true)
        if (s == null) {
            Text("Start a private party for what is playing. Friends join with a code on their own Fof TV and watch in sync – each TV plays from its own sources.",
                color = Color(0xB3FFFFFF), fontSize = 16.sp, raw = true)
            WatchParty.error?.let { Text(it, color = DS.Gold, fontSize = 15.sp, raw = true) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton(if (busy) "Starting …" else "Start a watch party", Modifier.focusRequester(first), light = true, icon = AIcon.Person, raw = true) {
                    if (busy) return@PillButton
                    busy = true
                    scope.launch { start(); busy = false }
                }
                PillButton("Close", raw = true) { onClose() }
            }
        } else {
            Text(if (s.host) "Party code" else "You joined", color = Color(0x99FFFFFF), fontSize = 15.sp, raw = true)
            Text(s.code.chunked(3).joinToString(" "), color = Color.White, fontSize = 52.sp, fontWeight = FontWeight.Bold, raw = true)
            if (s.host) Text("On another TV: profile circle at the top → Join a watch party → enter this code.", color = Color(0xB3FFFFFF), fontSize = 15.sp, raw = true)
            Text("Watching: " + (listOf(if (s.host) "you (host)" else "you") + WatchParty.members).distinct().joinToString(", "), color = Color.White, fontSize = 16.sp, raw = true)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                WatchPartyLogicUi.REACTIONS.forEachIndexed { i, r ->
                    SmallPill(r, if (i == 0) Modifier.focusRequester(first) else Modifier) { WatchParty.send(ctx, r) }
                }
            }
            // Text chat: short messages appear over the picture for everyone in the party
            var msg by remember { mutableStateOf("") }
            val sendMsg = { val t = msg.trim().take(140); if (t.isNotEmpty()) { WatchParty.send(ctx, t); msg = "" } }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Field(msg, { msg = it.take(140) }, "Say something …", onDone = { sendMsg() }) }
                PillButton("Send", raw = true) { sendMsg() }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton(if (s.host) "End the party" else "Leave the party", raw = true) { WatchParty.leave(ctx); onClose() }
                PillButton("Close", raw = true) { onClose() }
            }
        }
    }
}

object WatchPartyLogicUi {
    val REACTIONS = listOf("👍", "😂", "😮", "🔥", "❤️", "👏")
}

/** Over the picture: the party chip and the latest reactions and messages (each for a few seconds). */
@Composable
fun WatchPartyOverlay(modifier: Modifier) {
    val s = WatchParty.state ?: return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1_000); now = System.currentTimeMillis() } }
    Column(modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        WatchParty.messages.filter { now - it.at < 6_000 }.takeLast(3).forEach { m ->
            Box(Modifier.clip(RoundedCornerShape(50)).background(Color(0xCC111218)).padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(m.name + "  " + m.body, color = Color.White, fontSize = 18.sp, raw = true)
            }
        }
        // Joined from "Watching now" (or someone joined this TV's quiet party): who you watch with, in sync
        val others = (if (s.host) WatchParty.members.toList() else listOf(s.hostName) + WatchParty.members).filter { it.isNotBlank() && it != Profiles.activeName() }.distinct()
        if (others.isNotEmpty()) Row(Modifier.clip(RoundedCornerShape(50)).background(Color(0xAA000000)).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Watching with " + others.joinToString(", "), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, raw = true)
            Text("● in sync", color = Color(0xFF7BE39A), fontSize = 14.sp, raw = true)
        } else if (!(s.host && Presence.autoParty)) Box(Modifier.clip(RoundedCornerShape(50)).background(Color(0x99111218)).padding(horizontal = 14.dp, vertical = 6.dp)) {
            Text("Watch party " + s.code + "  ·  " + (WatchParty.members.size + 1) + " watching", color = Color(0xCCFFFFFF), fontSize = 14.sp, raw = true)
        }
    }
}

/** Join with the 6-digit code (remote number keys or the pad). */
@Composable
fun JoinPartyDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color(0xE6000000)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(460.dp).glass(RoundedCornerShape(DS.RadiusL), 2).padding(28.dp)) {
            if (busy) Text("Joining …", color = Color.White, fontSize = 22.sp, raw = true)
            else PinPad("Join a watch party", "Enter the 6-digit code from the host's TV.", err, { code ->
                busy = true
                scope.launch {
                    val e = WatchParty.join(ctx, code)
                    busy = false
                    if (e == null) onClose() else err = e
                }
            }, onClose, minLen = 6, maxLen = 6, raw = true)
        }
    }
}
