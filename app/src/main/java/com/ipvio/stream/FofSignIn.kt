package com.ipvio.stream

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Pure rules of the TV sign-in screen (unit-tested). */
object SignInLogic {
    /** An activation code on screen is valid this long, then a new one is made. */
    const val CODE_TTL_MS = 10 * 60_000L

    fun left(startedAt: Long, now: Long): Long = (CODE_TTL_MS - (now - startedAt)).coerceAtLeast(0)
    fun clock(ms: Long): String { val s = (ms + 999) / 1000; return "%d:%02d".format(s / 60, s % 60) }

    /** Null = ok, else what to fix (checked before anything is sent). */
    fun check(mail: String, pw: String, confirm: String?, create: Boolean): String? {
        val m = mail.trim()
        if (!Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(m)) return "Enter your email address."
        if (pw.isEmpty()) return "Enter your password."
        if (create && pw.length < 8) return "Use at least 8 characters for the password."
        if (create && confirm != pw) return "The two passwords are different."
        return null
    }

    /** Phone progress: 0 waiting for the phone, 1 phone opened the page, 2 phone sent the sign-in. */
    fun phoneStep(codeAt: Long, phoneAt: Long, signInAt: Long): Int = when {
        signInAt >= codeAt && signInAt > 0 -> 2
        phoneAt >= codeAt && phoneAt > 0 -> 1
        else -> 0
    }
}

/** First screen on a TV that is not signed in: Sign in, Create account, or use this TV without an account. */
object SignInGate {
    private fun prefs(c: Context) = FofAccount.devicePrefs(c)
    /** "Use without an account" chosen on this TV: the welcome screen is not shown again on launch. */
    fun skipped(c: Context) = prefs(c).getBoolean("welcome_skipped", false)
    fun skip(c: Context, v: Boolean) { prefs(c).edit().putBoolean("welcome_skipped", v).apply() }
}

@Composable
fun FofSignInScreen(start: String = "welcome", onSkip: (() -> Unit)?, onClose: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    var mode by remember { mutableStateOf(start) }   // welcome, signin, create
    if (mode != "welcome" || onClose != null) BackHandler { if (mode != "welcome" && start == "welcome") mode = "welcome" else onClose?.invoke() }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        PosterWall()
        FofLogo(44.dp, 30.sp, Modifier.align(Alignment.TopStart).padding(horizontal = 56.dp, vertical = 36.dp))
        AnimatedContent(mode, transitionSpec = { fadeIn(androidx.compose.animation.core.tween(260)) togetherWith fadeOut(androidx.compose.animation.core.tween(180)) },
            modifier = Modifier.fillMaxSize(), label = "signin") { m ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (m == "welcome") Welcome({ mode = "signin" }, { mode = "create" }, onSkip)
                else SignInPanel(create = m == "create", { mode = if (m == "create") "signin" else "create" })
            }
        }
    }
    // Signed in (on the phone or here): the account's profiles come next
    LaunchedEffect(FofAccount.signedInState) { if (FofAccount.signedInState) { SignInGate.skip(ctx, false); AccountCloud.refreshPins(ctx); onClose?.invoke() } }
}

@Composable
private fun Welcome(onSignIn: () -> Unit, onCreate: () -> Unit, onSkip: (() -> Unit)?) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); runCatching { first.requestFocus() } }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FofLogo(96.dp, 64.sp)
        Spacer(Modifier.height(26.dp))
        Text("Your movies, shows, live TV and sports. One account for every TV.", color = Color(0xCCFFFFFF), fontSize = 22.sp, raw = true, textAlign = TextAlign.Center)
        Spacer(Modifier.height(54.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            PillButton("Sign in", Modifier.focusRequester(first), light = true, icon = AIcon.Person, raw = true) { onSignIn() }
            PillButton("Create account", icon = AIcon.Plus, raw = true) { onCreate() }
        }
        if (onSkip != null) {
            Spacer(Modifier.height(30.dp))
            SmallPill("Use this TV without an account") { onSkip() }
        }
    }
}

@Composable
private fun SignInPanel(create: Boolean, onSwitch: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (create) "Create your Fof TV account" else "Sign in to Fof TV", color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold, raw = true)
        Spacer(Modifier.height(8.dp))
        Text(if (create) "One account for the whole home. Up to 10 profiles, each with its own history and lists." else "Your profiles, addons and Continue Watching come along.",
            color = Color(0xB3FFFFFF), fontSize = 19.sp, raw = true)
        Spacer(Modifier.height(36.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.Top) {
            PhoneCard(create)
            Column(Modifier.width(520.dp).height(470.dp).glass(RoundedCornerShape(DS.RadiusL), 1).padding(30.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Or on this TV", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, raw = true)
                var mail by remember { mutableStateOf("") }
                var pw by remember { mutableStateOf("") }
                var pw2 by remember { mutableStateOf("") }
                var err by remember { mutableStateOf<String?>(null) }
                var busy by remember { mutableStateOf(false) }
                Field(mail, { mail = it.trim(); err = null }, "Email")
                Field(pw, { pw = it; err = null }, "Password", password = true)
                if (create) Field(pw2, { pw2 = it; err = null }, "Password again", password = true)
                err?.let { Text(it, color = DS.Gold, fontSize = 16.sp, raw = true) }
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PillButton(if (busy) (if (create) "Creating …" else "Signing in …") else (if (create) "Create account" else "Sign in"), light = true, raw = true) {
                        if (busy) return@PillButton
                        SignInLogic.check(mail, pw, if (create) pw2 else null, create)?.let { err = it; return@PillButton }
                        if (!DeviceCaps.online(ctx)) { err = "This TV is offline. Check the internet connection and try again."; return@PillButton }
                        busy = true
                        scope.launch {
                            err = FofAccount.signUpOrIn(ctx, mail, pw, create)
                            // Created but the service wants a confirmed email first: say so plainly
                            if (err == null && !FofAccount.signedIn) err = "Check your email to confirm the account, then sign in."
                            if (err == null) FofAccount.reload(ctx)
                            busy = false
                        }
                    }
                    PillButton(if (create) "I have an account" else "Create account", raw = true) { onSwitch() }
                }
            }
        }
    }
}

/** QR and short code for the phone, with progress, expiry and a new code on demand. */
@Composable
private fun PhoneCard(create: Boolean) {
    val ctx = LocalContext.current
    val ip = remember { PhoneImport.localIp() }
    var nonce by remember { mutableIntStateOf(0) }
    var codeAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val server = remember(nonce) { if (nonce > 0) RemoteHub.stop(); RemoteHub.start(ctx) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(nonce) { while (true) { now = System.currentTimeMillis(); delay(1_000) } }
    val left = SignInLogic.left(codeAt, now)
    val step = SignInLogic.phoneStep(codeAt, RemoteHub.phoneAt, RemoteHub.phoneSignInAt)
    Column(Modifier.width(520.dp).height(470.dp).glass(RoundedCornerShape(DS.RadiusL), 1).padding(30.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("With your phone (fastest)", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, raw = true)
        when {
            ip == null -> Text("This TV is not on Wi-Fi, so the phone can't reach it. Use email and password on the right.", color = DS.Gold, fontSize = 17.sp, raw = true, textAlign = TextAlign.Center)
            server == null -> {
                Text("The sign-in code could not be started.", color = DS.Gold, fontSize = 17.sp, raw = true)
                PillButton("Try again", raw = true) { nonce++; codeAt = System.currentTimeMillis() }
            }
            left == 0L -> {
                Spacer(Modifier.height(60.dp))
                Text("This code has expired.", color = Color.White, fontSize = 20.sp, raw = true)
                PillButton("Get a new code", light = true, raw = true) { nonce++; codeAt = System.currentTimeMillis() }
            }
            else -> {
                Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White).padding(10.dp)) { QrImage("http://$ip:${server.port}/in?code=${server.code}", 210.dp) }
                Text("Scan with your phone camera" + (if (create) ", then create your account" else ", then sign in"), color = Color(0xB3FFFFFF), fontSize = 16.sp, raw = true)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Code", color = Color(0x99FFFFFF), fontSize = 16.sp, raw = true)
                    Text(server.code, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, raw = true)
                    Text("· expires in " + SignInLogic.clock(left), color = Color(0x99FFFFFF), fontSize = 16.sp, raw = true)
                }
                Text(when (step) { 2 -> "Signing in from your phone …"; 1 -> "Phone connected. Finish on your phone."; else -> "Waiting for your phone …" },
                    color = if (step > 0) DS.Gold else Color(0x80FFFFFF), fontSize = 17.sp, raw = true)
            }
        }
    }
}
