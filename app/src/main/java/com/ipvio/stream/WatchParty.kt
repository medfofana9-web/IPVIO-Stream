package com.ipvio.stream

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Watch Together: friends and family watch the same movie, episode or live game at the same time, each TV playing it
 * from its own sources (nothing is streamed through the party). The party only shares which title, play/pause and the
 * position (tables fof_parties, fof_party_members, fof_party_msgs; only members can see a party).
 *
 * The host's TV sends its state on play, pause and seek and every 5 s while playing; the guests' players follow
 * (re-seek when more than 2.5 s apart). Live: only the channel/game is shared, everybody stays at the live edge.
 * Reactions and short messages appear for a few seconds over the picture.
 */
object WatchParty {
    class State(val code: String, val host: Boolean, val itemId: String, val title: String, val kind: String, val season: Int, val ep: Int,
                val live: Boolean, val playing: Boolean, val posMs: Long, val ended: Boolean, val hostName: String = "")
    class Msg(val name: String, val body: String, val at: Long)

    var state by mutableStateOf<State?>(null)
        private set
    val members = mutableStateListOf<String>()
    val messages = mutableStateListOf<Msg>()
    var error by mutableStateOf<String?>(null)
    /** A guest joined: open this title (MainActivity plays it from this TV's own sources). */
    var openReq by mutableStateOf<State?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http by lazy { Net.withTimeouts(10, 0).newBuilder().pingInterval(25, TimeUnit.SECONDS).build() }
    @Volatile private var ws: WebSocket? = null
    @Volatile private var ref = 0
    /** Ignore the local player events caused by applying the host's state (no echo back). */
    @Volatile private var applyingUntil = 0L

    private fun req(c: Context, method: String, path: String, body: String? = null, prefer: String? = null): Pair<Int, String> {
        val t = FofAccount.tokenFor(c) ?: error("Sign in to use Watch Together.")
        return FofAccount.call(method, path, body, t, prefer)
    }

    private fun me(): String = Profiles.activeName().take(40)

    /** Starts a party for what is playing; returns the 6-digit code (or null with [error]). */
    suspend fun start(c: Context, itemId: String, title: String, kind: Kind, season: Int, ep: Int, live: Boolean, posMs: Long, playing: Boolean): String? = withContext(Dispatchers.IO) {
        error = null
        if (!FofAccount.signedIn) { error = "Sign in to your Fof TV account to start a watch party."; return@withContext null }
        repeat(5) {
            val code = WatchPartyLogic.newCode()
            val body = JSONObject().put("code", code).put("host_device", AccountCloud.deviceId(c)).put("host_name", me())
                .put("item_id", itemId).put("title", title.take(200)).put("kind", kind.name).put("season", season).put("ep", ep)
                .put("live", live).put("playing", playing).put("pos_ms", posMs)
            val r = runCatching { req(c, "POST", "/rest/v1/fof_parties", body.toString(), "return=minimal") }.getOrNull()
            if (r != null && r.first in 200..299) {
                state = State(code, true, itemId, title, kind.name, season, ep, live, playing, posMs, false)
                members.clear(); messages.clear()
                listen(c, code)
                return@withContext code
            }
            if (r == null) { error = "No connection to the account service."; return@withContext null }
        }
        error = "Could not start the party. Try again."
        null
    }

    /** Joins with a code; the title opens on this TV and follows the host. Null = ok, else a readable error. */
    suspend fun join(c: Context, code: String): String? = withContext(Dispatchers.IO) {
        if (!FofAccount.signedIn) return@withContext "Sign in to your Fof TV account to join a watch party."
        val r = runCatching { req(c, "POST", "/rest/v1/rpc/fof_join_party", JSONObject().put("p_code", code).put("p_device", AccountCloud.deviceId(c)).put("p_name", me()).toString()) }.getOrNull()
            ?: return@withContext "No connection to the account service."
        val o = runCatching { JSONObject(r.second) }.getOrNull()
        if (o?.optBoolean("ok") != true) return@withContext if (o?.optString("err") == "no_party") "No watch party with that code (or it has ended)." else "Could not join."
        val p = load(c, code) ?: return@withContext "Could not read the party."
        state = p
        members.clear(); messages.clear()
        listen(c, code)
        loadMembers(c, code)
        withContext(Dispatchers.Main) { openReq = p }
        null
    }

    private fun parse(o: JSONObject, host: Boolean) = State(o.optString("code"), host, o.optString("item_id"), o.optString("title"), o.optString("kind"),
        o.optInt("season"), o.optInt("ep"), o.optBoolean("live"), o.optBoolean("playing"), o.optLong("pos_ms"), o.optBoolean("ended"), o.optString("host_name"))

    private fun load(c: Context, code: String): State? = runCatching {
        val r = req(c, "GET", "/rest/v1/fof_parties?select=*&code=eq.$code")
        JSONArray(r.second).optJSONObject(0)?.let { parse(it, it.optString("host_device") == AccountCloud.deviceId(c)) }
    }.getOrNull()

    private fun loadMembers(c: Context, code: String) {
        val r = runCatching { req(c, "GET", "/rest/v1/fof_party_members?select=name&left_at=is.null&code=eq.$code") }.getOrNull() ?: return
        val a = runCatching { JSONArray(r.second) }.getOrNull() ?: return
        val names = (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name")?.ifBlank { null } }
        scope.launch(Dispatchers.Main) { members.clear(); members.addAll(names) }
    }

    /** Leaves (a guest) or ends the party (the host). */
    fun leave(c: Context) {
        val s = state ?: return
        state = null; members.clear(); messages.clear(); Presence.autoParty = false
        runCatching { ws?.close(1000, null) }; ws = null
        scope.launch {
            runCatching {
                if (s.host) req(c, "PATCH", "/rest/v1/fof_parties?code=eq.${s.code}", JSONObject().put("ended", true).toString())
                else req(c, "PATCH", "/rest/v1/fof_party_members?code=eq.${s.code}&device=eq.${AccountCloud.deviceId(c)}",
                    JSONObject().put("left_at", "now").toString())
            }
        }
    }

    fun send(c: Context, body: String) {
        val s = state ?: return
        scope.launch { runCatching { req(c, "POST", "/rest/v1/fof_party_msgs", JSONObject().put("code", s.code).put("name", me()).put("body", body.take(200)).toString(), "return=minimal") } }
    }

    // ---------------------------------------------------------------- player sync

    @Volatile private var lastPush = 0L

    /** Host: the player's state goes to the party (on play, pause, seek, and every 5 s from [tick]). */
    fun push(c: Context, playing: Boolean, posMs: Long, force: Boolean = false) {
        val s = state ?: return
        if (!s.host || s.ended) return
        val now = System.currentTimeMillis()
        if (!force && now - lastPush < 1_000) return
        lastPush = now
        state = State(s.code, true, s.itemId, s.title, s.kind, s.season, s.ep, s.live, playing, posMs, false)
        scope.launch {
            runCatching { req(c, "PATCH", "/rest/v1/fof_parties?code=eq.${s.code}",
                JSONObject().put("playing", playing).put("pos_ms", posMs).put("state_at", "now").toString()) }
        }
    }

    /** Follows the player that plays [itemId] (host sends, guest follows). Call from the player; returns the detach action. */
    fun attach(c: Context, player: Player, itemId: String, season: Int, ep: Int): () -> Unit {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { if (owns(itemId, season, ep) && System.currentTimeMillis() > applyingUntil) push(c, isPlaying, player.currentPosition, true) }
            override fun onPositionDiscontinuity(o: Player.PositionInfo, n: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK && owns(itemId, season, ep) && System.currentTimeMillis() > applyingUntil) push(c, player.isPlaying, player.currentPosition, true)
            }
        }
        player.addListener(l)
        follower = { s -> if (owns(itemId, season, ep)) apply(player, s) }
        return { player.removeListener(l); follower = null }
    }

    /** Host's periodic state while playing (call every few seconds from the player's tick). */
    fun tick(c: Context, player: Player, itemId: String, season: Int, ep: Int) {
        val s = state ?: return
        if (s.host && owns(itemId, season, ep) && !s.live && player.isPlaying && System.currentTimeMillis() - lastPush > 5_000) push(c, true, player.currentPosition, true)
    }

    fun owns(itemId: String, season: Int, ep: Int): Boolean {
        val s = state ?: return false
        return s.itemId == itemId && (s.season == 0 && s.ep == 0 || (s.season == season && s.ep == ep))
    }

    @Volatile private var follower: ((State) -> Unit)? = null

    private fun apply(player: Player, s: State) {
        if (s.host || s.live) return
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            applyingUntil = System.currentTimeMillis() + 1_500
            val want = WatchPartyLogic.target(s.posMs, s.playing, 400)
            if (WatchPartyLogic.needsSeek(player.currentPosition, want)) player.seekTo(want)
            if (s.playing && !player.isPlaying) player.play()
            if (!s.playing && player.isPlaying) player.pause()
        }
    }

    // ---------------------------------------------------------------- realtime

    private fun listen(c: Context, code: String) {
        runCatching { ws?.close(1000, null) }
        val token = FofAccount.tokenFor(c) ?: return
        val url = FofAccount.URL.replace("https://", "wss://") + "/realtime/v1/websocket?apikey=" + FofAccount.publicKey + "&vsn=1.0.0"
        ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val changes = JSONArray()
                for (t in listOf("fof_parties", "fof_party_msgs", "fof_party_members"))
                    changes.put(JSONObject().put("event", "*").put("schema", "public").put("table", t).put("filter", "code=eq.$code"))
                webSocket.send(JSONObject().put("topic", "realtime:party-$code").put("event", "phx_join").put("ref", (++ref).toString())
                    .put("payload", JSONObject().put("access_token", token).put("config", JSONObject().put("postgres_changes", changes))).toString())
                Thread {
                    while (ws === webSocket) {
                        runCatching { Thread.sleep(25_000) }
                        if (ws !== webSocket) break
                        webSocket.send(JSONObject().put("topic", "phoenix").put("event", "heartbeat").put("payload", JSONObject()).put("ref", (++ref).toString()).toString())
                    }
                }.apply { isDaemon = true }.start()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val o = runCatching { JSONObject(text) }.getOrNull() ?: return
                if (o.optString("event") != "postgres_changes") return
                val d = o.optJSONObject("payload")?.optJSONObject("data") ?: return
                val rec = d.optJSONObject("record") ?: return
                when (d.optString("table")) {
                    "fof_parties" -> {
                        val cur = state ?: return
                        val s = parse(rec, cur.host)
                        state = s
                        if (s.ended && !cur.host) { scope.launch(Dispatchers.Main) { error = "The host ended the watch party." }; leave(c) }
                        else follower?.invoke(s)
                    }
                    "fof_party_msgs" -> scope.launch(Dispatchers.Main) {
                        messages.add(Msg(rec.optString("name"), rec.optString("body"), System.currentTimeMillis()))
                        while (messages.size > 20) messages.removeAt(0)
                    }
                    "fof_party_members" -> loadMembers(c, code)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (ws !== webSocket) return
                // Reconnect after a pause while the party is on (the state is read again, so nothing is missed)
                scope.launch { kotlinx.coroutines.delay(5_000); if (state?.code == code) { load(c, code)?.let { s -> state = s; follower?.invoke(s) }; listen(c, code) } }
            }
        })
    }
}

/** Pure parts (tested). */
object WatchPartyLogic {
    private val rnd = java.security.SecureRandom()
    fun newCode(): String = (100000 + rnd.nextInt(900000)).toString()

    /** Where the guest should be: the host's position plus the travel time while playing. */
    fun target(hostPosMs: Long, playing: Boolean, latencyMs: Long): Long = if (playing) hostPosMs + latencyMs else hostPosMs

    /** More than 2.5 s apart: seek (small drift is left alone, so the picture does not jump). */
    fun needsSeek(currentMs: Long, targetMs: Long): Boolean = kotlin.math.abs(currentMs - targetMs) > 2_500
}
