package com.ipvio.stream

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Watching now: while a movie or episode plays, this profile's title and position go to the account (table
 * fof_presence, see docs/supabase/presence.sql) about every 25 s; Home shows what the OTHER profiles are playing,
 * with Join. Only title, episode and position are shared, never the stream address. Each profile can turn it off.
 */
object Presence {
    data class Now(val profileId: String, val name: String, val avatar: String, val itemId: String, val title: String,
                   val season: Int, val ep: Int, val posMs: Long, val durMs: Long, val img: String, val device: String, val at: Long)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var lastBeat = 0L
    var others by mutableStateOf<List<Now>>(emptyList())
        private set

    private fun prefs(c: Context) = Profiles.sp(c, "fof_share")
    fun sharing(c: Context): Boolean = prefs(c).getBoolean("watching", true)
    fun setSharing(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean("watching", on).apply()
        if (!on) clear(c)
    }

    /** Called with every saved resume point (about every 5 s while playing); sends at most every 25 s. */
    fun beat(c: Context, e: Entry) {
        val now = System.currentTimeMillis()
        if (now - lastBeat < 25_000 || !FofAccount.signedIn || !sharing(c)) return
        lastBeat = now
        val app = c.applicationContext
        scope.launch {
            runCatching {
                val cid = AccountCloud.cloudId(app, Profiles.active) ?: return@runCatching
                val t = FofAccount.tokenFor(app) ?: return@runCatching
                val p = Profiles.list.firstOrNull { it.id == Profiles.active }
                val body = JSONObject().put("profile_id", cid)
                    .put("name", (p?.let { Profiles.displayName(it) } ?: "").take(40)).put("avatar", "")
                    .put("item_id", e.itemId.take(300)).put("title", PresenceLogic.cleanTitle(e.title).take(200))
                    .put("season", e.season).put("ep", e.ep).put("pos_ms", e.pos).put("dur_ms", e.dur)
                    .put("device", AccountCloud.deviceName(app).take(60)).put("updated_at", PresenceLogic.iso(now))
                FofAccount.call("POST", "/rest/v1/fof_presence?on_conflict=profile_id", body.toString(), t, "resolution=merge-duplicates,return=minimal")
            }
        }
    }

    /** Stop showing this profile as watching (sharing turned off). */
    fun clear(c: Context) {
        val app = c.applicationContext
        scope.launch {
            runCatching {
                val cid = AccountCloud.cloudId(app, Profiles.active) ?: return@runCatching
                val t = FofAccount.tokenFor(app) ?: return@runCatching
                FofAccount.call("DELETE", "/rest/v1/fof_presence?profile_id=eq.$cid", null, t)
            }
        }
    }

    /** What the other profiles play right now (rows updated in the last 75 s). */
    suspend fun refresh(c: Context) = withContext(Dispatchers.IO) {
        val app = c.applicationContext
        if (!FofAccount.signedIn) { others = emptyList(); return@withContext }
        runCatching {
            val t = FofAccount.tokenFor(app) ?: return@runCatching
            val mine = AccountCloud.cloudId(app, Profiles.active)
            val since = PresenceLogic.iso(System.currentTimeMillis() - 75_000)
            val (code, res) = FofAccount.call("GET", "/rest/v1/fof_presence?select=*&updated_at=gt." + java.net.URLEncoder.encode(since, "UTF-8") + "&order=updated_at.desc", null, t)
            if (code !in 200..299) return@runCatching
            val a = JSONArray(res)
            others = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.optString("profile_id") != mine }.map { o ->
                Now(o.optString("profile_id"), o.optString("name"), o.optString("avatar"), o.optString("item_id"), o.optString("title"),
                    o.optInt("season"), o.optInt("ep"), o.optLong("pos_ms"), o.optLong("dur_ms"), o.optString("img"), o.optString("device"), 0L)
            }
        }
    }
}

object PresenceLogic {
    private val ISO = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
    fun iso(ms: Long): String = synchronized(ISO) { ISO.format(java.util.Date(ms)) }
    /** "Reasonable Doubt S2E4" → "Reasonable Doubt" (the episode is sent separately). */
    fun cleanTitle(t: String): String = t.replace(Regex("\\s+S\\d{1,2}\\s*E\\d{1,3}.*$", RegexOption.IGNORE_CASE), "").trim()
    fun minutesLeft(pos: Long, dur: Long): Int = if (dur <= 0 || pos >= dur) 0 else ((dur - pos) / 60_000L).toInt()
}
