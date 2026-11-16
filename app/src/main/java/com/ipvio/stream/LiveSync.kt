package com.ipvio.stream

import android.content.Context
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Instant Continue watching between TVs: listens to the account service's realtime feed (Supabase Realtime) for changes
 * of the profile in use (table fof_profile_data, only rows this TV may read). A change from another TV starts a
 * sync right away; the 20 s check in MainActivity stays as the fallback when the feed is down.
 * One socket while the app is in front; closed when it leaves, reconnects with a growing pause after errors, and
 * starts over every 50 minutes with a fresh sign-in token.
 */
object LiveSync {
    private val http by lazy { Net.withTimeouts(10, 0).newBuilder().pingInterval(25, TimeUnit.SECONDS).build() }
    @Volatile private var ws: WebSocket? = null
    @Volatile private var watching: String? = null
    @Volatile private var openedAt = 0L
    @Volatile private var fails = 0
    @Volatile private var ref = 0

    /** Connected and listening (the 20 s check can then slow down). */
    @Volatile var live = false
        private set

    /** Start or keep the feed for the profile in use (called from the sync loop and when the app comes back). */
    fun ensure(c: Context) {
        val app = c.applicationContext
        val cid = if (FofAccount.signedIn) AccountCloud.cloudId(app, Profiles.active) else null
        if (cid == null || !AccountCloud.canSync(app, Profiles.active)) { stop(); return }
        val fresh = System.currentTimeMillis() - openedAt < 50 * 60_000L
        if (ws != null && watching == cid && fresh) return
        stop()
        val token = FofAccount.tokenFor(app) ?: return
        watching = cid
        openedAt = System.currentTimeMillis()
        val url = FofAccount.URL.replace("https://", "wss://") + "/realtime/v1/websocket?apikey=" + FofAccount.publicKey + "&vsn=1.0.0"
        ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val join = JSONObject().put("topic", "realtime:fof-$cid").put("event", "phx_join").put("ref", (++ref).toString())
                    .put("payload", JSONObject().put("access_token", token).put("config", JSONObject()
                        .put("broadcast", JSONObject().put("self", false)).put("presence", JSONObject().put("key", ""))
                        .put("postgres_changes", JSONArray().put(JSONObject().put("event", "*").put("schema", "public")
                            .put("table", "fof_profile_data").put("filter", "profile_id=eq.$cid")))))
                webSocket.send(join.toString())
                // Phoenix heartbeat (the account service closes quiet sockets)
                Thread {
                    while (ws === webSocket) {
                        runCatching { Thread.sleep(25_000) }
                        if (ws !== webSocket) break
                        webSocket.send(JSONObject().put("topic", "phoenix").put("event", "heartbeat").put("payload", JSONObject()).put("ref", (++ref).toString()).toString())
                    }
                }.apply { isDaemon = true; name = "livesync-beat" }.start()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val o = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (o.optString("event")) {
                    "phx_reply" -> if (o.optJSONObject("payload")?.optString("status") == "ok") { live = true; fails = 0 }
                    // Another TV saved this profile (our own saves arrive too; the sync then finds nothing new)
                    "postgres_changes" -> CloudSync.kick(app, 300)
                    "phx_error", "system" -> if (o.optJSONObject("payload")?.optString("status") == "error") webSocket.close(1000, null)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { if (ws === webSocket) retry(app) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { if (ws === webSocket) retry(app) }
        })
    }

    private fun retry(c: Context) {
        ws = null; live = false
        val wait = (5_000L shl fails.coerceAtMost(5)).coerceAtMost(120_000L)
        fails++
        Thread { runCatching { Thread.sleep(wait); if (Reminders.foreground && ws == null) ensure(c) } }.apply { isDaemon = true }.start()
    }

    fun stop() {
        val w = ws
        ws = null; live = false; watching = null
        runCatching { w?.close(1000, null) }
    }
}
