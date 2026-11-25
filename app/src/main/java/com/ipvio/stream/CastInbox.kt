package com.ipvio.stream

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Play on TV": the iPhone and Mac apps send a title, channel or remote button to this TV through the account
 * (docs/supabase/cast.sql, table fof_cast, reached only through fof_cast_send / fof_cast_take). While the app is in
 * front, this TV asks every 2.5 s for commands addressed to its device id and hands them to the same paths the phone
 * remote and Watch Together already use: [WatchParty.openReq] for addon titles (opens the title, picks the source,
 * resumes), [RemoteHub.playReq] for channels and provider titles, [RemoteHub.control] for pause / skip / stop / volume.
 */
object CastInbox {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var job: Job? = null
    /** Off for good on this server version when the function does not exist yet (cast.sql not run): no request spam. */
    @Volatile private var missingUntil = 0L

    fun start(c: Context) {
        if (job?.isActive == true) return
        val app = c.applicationContext
        RemoteHub.attach(app)
        job = scope.launch {
            while (isActive) {
                if (FofAccount.signedIn && System.currentTimeMillis() > missingUntil) runCatching { poll(app) }
                delay(2_500)
            }
        }
    }

    fun stop() { job?.cancel(); job = null }

    private suspend fun poll(c: Context) {
        val t = FofAccount.tokenFor(c) ?: return
        val r = FofAccount.call("POST", "/rest/v1/rpc/fof_cast_take", JSONObject().put("p_device", AccountCloud.deviceId(c)).toString(), t)
        if (r.first == 404) { missingUntil = System.currentTimeMillis() + 30 * 60_000L; return }
        if (r.first !in 200..299) return
        val arr = runCatching { JSONArray(r.second) }.getOrNull() ?: return
        for (i in 0 until arr.length()) {
            val cmd = arr.optJSONObject(i) ?: continue
            withContext(Dispatchers.Main) { runCatching { apply(cmd) } }
        }
    }

    private fun apply(cmd: JSONObject) {
        when (cmd.optString("t")) {
            // Addon movie or episode: open it like a guest joining Watch Together (own sources, resume point)
            "play" -> {
                val id = cmd.optString("id").take(200)
                if (!id.startsWith("stremio:tt")) return
                val series = cmd.optString("kind") == "series"
                WatchParty.openReq = WatchParty.State("", false, id, cmd.optString("title").take(200), if (series) "SERIES" else "MOVIE",
                    cmd.optInt("s"), cmd.optInt("e"), false, true, cmd.optLong("pos").coerceAtLeast(0L), false)
            }
            // Live channel: the phone knows the provider's stream id; find this TV's channel with that id
            "live" -> {
                val sid = cmd.optString("sid").filter { it.isDigit() }.take(12)
                val name = cmd.optString("name").take(200)
                val all = PlaylistStore.channels.filter { it.kind == Kind.LIVE }
                val ch = (if (sid.isNotEmpty()) all.firstOrNull { PlayOnTvLogic.sameStream(it.url, sid) } else null)
                    ?: all.firstOrNull { it.title.equals(name, ignoreCase = true) } ?: return
                RemoteHub.playReq = RemoteHub.Play(ch.id, 0, 0)
            }
            "ctl" -> RemoteHub.control(cmd.optString("c").take(10), cmd.optString("v").take(10))
        }
    }
}

object PlayOnTvLogic {
    /** Xtream live link of stream [sid]: .../live/user/pass/<sid>.ts (or .m3u8, or without extension). */
    fun sameStream(url: String, sid: String): Boolean {
        val last = url.substringBefore('?').trimEnd('/').substringAfterLast('/')
        return last.substringBefore('.') == sid
    }
}
