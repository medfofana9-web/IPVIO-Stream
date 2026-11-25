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
import kotlinx.coroutines.async
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
    /** Streams started here since [last] was read (counted on top of its active streams). */
    @Volatile private var startedSince = 0
    private val bg = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    @Volatile private var fetching: kotlinx.coroutines.Deferred<State?>? = null

    /** Streams this device itself holds (player, multi view tiles): they are replaced, not added. */
    @Volatile var own = 0

    private fun fetchAsync(a: XtreamLogic.Acct, delayMs: Long = 0): kotlinx.coroutines.Deferred<State?> {
        fetching?.let { if (it.isActive) return it }
        val d = bg.async {
            if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
            val s = runCatching {
                Net.withTimeouts(2, 2).newCall(Request.Builder().url(XtreamLogic.api(a)).build()).execute().use { r ->
                    if (!r.isSuccessful) null else ConnLogic.parse(r.body?.string().orEmpty())
                }
            }.getOrNull()
            if (s != null) { last = System.currentTimeMillis() to s; startedSince = 0 }
            s
        }
        fetching = d
        return d
    }

    suspend fun state(url: String): State? {
        val a = acctOf(url) ?: return null
        last?.let { if (System.currentTimeMillis() - it.first < 8_000L) return it.second.let { s -> s.copy(active = s.active + startedSince) } }
        return withTimeoutOrNull(ConnLogic.LIVE_CHECK_MS) { fetchAsync(a).await() }
    }

    /** Ask the provider ahead (Sports or Home live row on screen), so pressing OK can start at once. */
    fun prefetch(url: String?) {
        val a = url?.let { acctOf(it) } ?: return
        val l = last
        if (l != null && System.currentTimeMillis() - l.first < ConnLogic.PREFETCH_EVERY_MS) return
        fetchAsync(a)
    }

    /**
     * null = a stream may start; otherwise the state that blocks it. [replacing] = this device stops one of its own for it.
     * A recent answer with at least two free streams starts at once (it was a provider round trip, up to 2.5 s, before
     * every channel or game); close to the limit the provider is asked again, so the family cap still holds.
     */
    suspend fun blockedBy(url: String, replacing: Boolean = false): State? {
        val freed = if (replacing) maxOf(own, 1) else 0
        val l = last
        if (l != null && ConnLogic.clearlyFree(l.second.active + startedSince, l.second.max, freed, System.currentTimeMillis() - l.first)) {
            // Keep the count current for the next start, once this stream is connected and counted by the provider
            acctOf(url)?.let { fetchAsync(it, delayMs = 6_000) }
            return null
        }
        val s = state(url) ?: return null
        return if (ConnLogic.full(s.active, s.max, freed)) s else null
    }

    /** A new stream started here: counted on top of the last answer until the provider is asked again ([replacing]:
     * this device stopped one of its own for it, so the count stays). */
    fun changed(replacing: Boolean = false) { if (!replacing) startedSince++ }

    /** "Try again" after the limit dialog: the next check asks the provider. */
    fun invalidate() { last = null; startedSince = 0 }
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

    /** Longest wait for the provider's stream count before a start is allowed anyway (was 2.5 s). */
    const val LIVE_CHECK_MS = 1_500L
    /** How old a stream count may be to start without asking again. */
    const val TRUST_MS = 60_000L
    const val PREFETCH_EVERY_MS = 30_000L

    /** A recent count ([ageMs]) leaves at least two streams free: start without waiting for the provider. */
    fun clearlyFree(active: Int, max: Int, freedHere: Int, ageMs: Long): Boolean =
        max > 0 && ageMs in 0 until TRUST_MS && active - freedHere + 2 <= max

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
