package com.ipvio.stream

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Today's channel names for sports matching, like the Mac app: providers rename their event channels every day
 * ("UEFA Champions League 09: Real Madrid - PSV"), but the TV keeps the playlist it loaded at the last cold start, which on a
 * streaming box can be days old. This asks the provider (Xtream get_live_streams, live channels only, a few seconds) for the
 * current names and lays them over the loaded channels by stream id. Nothing is stored; the playlist itself is unchanged.
 */
object SportFresh {
    private const val MAX_AGE_MS = 10 * 60_000L
    private val lock = Mutex()
    @Volatile private var names: Map<String, String> = emptyMap()
    @Volatile private var fetchedAt = 0L

    /** Counts up when new names arrived (screens that match channels recompute). */
    var version by mutableIntStateOf(0)
        private set

    fun stale(): Boolean = System.currentTimeMillis() - fetchedAt > MAX_AGE_MS

    /** Stream id of an Xtream live address (".../live/user/pass/12345.ts" or ".../user/pass/12345"). */
    internal fun streamId(url: String): String? {
        val last = url.substringBefore('?').substringAfterLast('/').substringBefore('.')
        return last.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
    }

    /** Fetches the current names when the last fetch is older than 10 minutes (or [force]). Never throws. */
    suspend fun refresh(force: Boolean = false) {
        if (!force && !stale()) return
        lock.withLock {
            if (!force && !stale()) return
            val acct = PlaylistStore.channels.asSequence().filter { it.kind == Kind.LIVE && it.src == 0 }
                .firstNotNullOfOrNull { ConnLogic.acctOf(it.url) } ?: run { fetchedAt = System.currentTimeMillis(); return }
            val fresh = withContext(Dispatchers.IO) {
                runCatching {
                    val m = HashMap<String, String>(32_768)
                    PlaylistStore.xtreamApi().titles(acct, "get_live_streams") { f ->
                        val id = f["stream_id"]?.trim()
                        val n = XtreamLogic.name(f["name"])
                        if (!id.isNullOrEmpty() && n.isNotEmpty()) m[id] = n
                    }
                    m
                }.getOrNull()
            }
            fetchedAt = System.currentTimeMillis()
            if (!fresh.isNullOrEmpty()) {
                names = fresh
                withContext(Dispatchers.Main) { version++ }
            }
        }
    }

    private var lastIn: List<Item>? = null
    private var lastNames: Map<String, String>? = null
    private var lastOut: List<Item> = emptyList()

    /**
     * [live] with today's names on the channels the provider renamed. Returns the same list object for the same input and
     * names, so the matchers' caches keep working.
     */
    fun apply(live: List<Item>): List<Item> {
        val n = names
        if (n.isEmpty() || live.isEmpty()) return live
        synchronized(this) { if (lastIn === live && lastNames === n) return lastOut }
        var changed = false
        val out = live.map { c ->
            if (c.kind != Kind.LIVE || c.src != 0) return@map c
            val fresh = streamId(c.url)?.let { n[it] }
            if (fresh == null || fresh == c.title) c else { changed = true; c.copy(title = fresh) }
        }
        val r = if (changed) out else live
        synchronized(this) { lastIn = live; lastNames = n; lastOut = r }
        return r
    }
}
