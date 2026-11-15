package com.ipvio.stream

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.json.JSONObject

/**
 * Stream limit of the shared provider login (e.g. 4 at once for the whole family). Before a new stream starts on this
 * device, the provider is asked how many streams run right now on the login (Xtream "active_cons" of "max_connections").
 * When all are in use, nothing starts and the person sees who has to stop first – instead of the provider cutting off
 * a stream somewhere else or blocking the login. Counts every device on the login, also other apps.
 * If the provider does not answer within 2.5 s, playback is allowed (never blocks on a slow server).
 */
object ConnGuard {
    data class State(val active: Int, val max: Int)

    /** Login from a stream address: /live|movie|series/user/pass/id.ext or /user/pass/id (Xtream). */
    fun acctOf(url: String): XtreamLogic.Acct? = ConnLogic.acctOf(url)

    @Volatile private var last: Pair<Long, State>? = null

    /** Streams this device itself holds (player, multi view tiles): they are replaced, not added. */
    @Volatile var own = 0

    suspend fun state(url: String): State? {
        val a = acctOf(url) ?: return null
        last?.let { if (System.currentTimeMillis() - it.first < 8_000L) return it.second }
        val s = withTimeoutOrNull(2_500L) {
            withContext(Dispatchers.IO) {
                runCatching {
                    Net.withTimeouts(2, 2).newCall(Request.Builder().url(XtreamLogic.api(a)).build()).execute().use { r ->
                        if (!r.isSuccessful) null else ConnLogic.parse(r.body?.string().orEmpty())
                    }
                }.getOrNull()
            }
        } ?: return null
        last = System.currentTimeMillis() to s
        return s
    }

    /** null = a stream may start; otherwise the state that blocks it. [replacing] = this device stops one of its own for it. */
    suspend fun blockedBy(url: String, replacing: Boolean = false): State? {
        val s = state(url) ?: return null
        return if (ConnLogic.full(s.active, s.max, if (replacing) maxOf(own, 1) else 0)) s else null
    }

    /** A new stream started or stopped here: the next check asks the provider again. */
    fun changed() { last = null }
}

/** Pure parts (testable without Android). */
object ConnLogic {
    private val PATH = Regex("""^(https?://[^/]+)/(?:(?:live|movie|series)/)?([^/]+)/([^/]+)/[^/]+$""")

    fun acctOf(url: String): XtreamLogic.Acct? {
        val m = PATH.find(url.substringBefore('?').trim()) ?: return null
        val (base, user, pass) = m.destructured
        if (user.isEmpty() || pass.isEmpty() || user.equals("get.php", true)) return null
        fun dec(s: String) = runCatching { java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)
        return XtreamLogic.Acct(base, dec(user), dec(pass))
    }

    /** "user_info": active_cons and max_connections (numbers or texts); null when the provider does not say. */
    fun parse(body: String): ConnGuard.State? {
        val ui = runCatching { JSONObject(body).optJSONObject("user_info") }.getOrNull() ?: return null
        val active = ui.opt("active_cons")?.toString()?.trim()?.toIntOrNull() ?: return null
        val max = ui.opt("max_connections")?.toString()?.trim()?.toIntOrNull() ?: return null
        return if (max > 0) ConnGuard.State(active, max) else null
    }

    /** All streams in use, not counting [freedHere] that this device gives up for the new one. */
    fun full(active: Int, max: Int, freedHere: Int): Boolean = active - freedHere >= max
}

/** All streams of the shared login are taken: say so plainly, offer to try again. */
@Composable
fun StreamLimitDialog(s: ConnGuard.State, onRetry: () -> Unit, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(80); runCatching { first.requestFocus() } }
    Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.width(520.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFF16181D)).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("All ${s.max} streams are in use", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, raw = true)
            Text("Your family is watching on ${s.active} screens right now. When someone stops, try again.", color = Muted, fontSize = 17.sp, raw = true)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton("Try again", Modifier.focusRequester(first), light = true, raw = true) { onRetry() }
                PillButton("Cancel", raw = true) { onClose() }
            }
        }
    }
}
