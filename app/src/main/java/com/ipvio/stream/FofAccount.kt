package com.ipvio.stream

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Family account (Supabase project "Fof TV", schema in docs/supabase/schema.sql).
 * The owner's account holds the shared setup: provider playlist and Stremio addons. Family members sign up with an
 * invite code, their own name and avatar; their TV then loads the shared setup by itself and keeps it current.
 * Each TV keeps its own pages and settings. The public key below is meant to be in the app: every table is protected
 * by row level security, so it only reaches what the signed-in person may see.
 */
object FofAccount {
    const val URL = "https://msooylkizqvdrgxrdjww.supabase.co"
    private const val KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Im1zb295bGtpenF2ZHJneHJkand3Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTQ3MTAzMTksImV4cCI6MjExMDI4NjMxOX0.PBF00osXJngq78z74Q2jVKFkc5yvJ41z9d7E0jJjryo"
    private val JSON = "application/json".toMediaType()

    class Session(val access: String, val refresh: String, val userId: String, val email: String, val expiresAt: Long)
    class Member(val householdId: String, val role: String, val name: String, val avatar: String) { val owner get() = role == "owner" }

    @Volatile private var session: Session? = null
    /** Signed-in person's membership (null = signed in but not in a family yet, or signed out). */
    var member by mutableStateOf<Member?>(null)
        private set
    var email by mutableStateOf<String?>(null)
        private set
    /** Last sync with the family setup, for the phone page ("synced 2 min ago", or the error). */
    var status by mutableStateOf("")
        private set

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("fof_account", Context.MODE_PRIVATE)

    fun init(c: Context) {
        if (session != null) return
        val enc = prefs(c).getString("s", null) ?: return
        val raw = Secrets.decrypt(enc) ?: return
        session = AccountLogic.readSession(raw)
        email = session?.email
        prefs(c).getString("m", null)?.let { member = AccountLogic.readMember(it) }
    }

    val signedIn: Boolean get() = session != null

    private fun save(c: Context, s: Session?) {
        session = s; email = s?.email
        prefs(c).edit().apply {
            if (s == null) { remove("s"); remove("m") } else Secrets.encrypt(AccountLogic.writeSession(s))?.let { putString("s", it) }
        }.apply()
    }

    private fun saveMember(c: Context, m: Member?) {
        member = m
        prefs(c).edit().apply { if (m == null) remove("m") else putString("m", AccountLogic.writeMember(m)) }.apply()
    }

    // ---------------------------------------------------------------- HTTP

    private fun call(method: String, path: String, body: String?, token: String?): Pair<Int, String> {
        val b = Request.Builder().url(URL + path).header("apikey", KEY).header("Authorization", "Bearer " + (token ?: KEY))
        if (method == "POST") b.post((body ?: "{}").toRequestBody(JSON)) else if (method == "PATCH") b.patch((body ?: "{}").toRequestBody(JSON)).header("Prefer", "return=minimal")
        Net.withTimeouts(8, 12).newCall(b.build()).execute().use { r -> return r.code to (r.body?.string().orEmpty()) }
    }

    /** A valid access token (refreshed when it is about to run out), or null when signed out. */
    private fun token(c: Context): String? {
        val s = session ?: return null
        if (System.currentTimeMillis() / 1000 < s.expiresAt - 120) return s.access
        val (code, body) = call("POST", "/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", s.refresh).toString(), null)
        if (code !in 200..299) return null
        val n = AccountLogic.sessionFrom(body) ?: return null
        save(c, n)
        return n.access
    }

    private fun err(body: String, fallback: String) = AccountLogic.message(body) ?: fallback

    // ---------------------------------------------------------------- Sign up / in / out

    /** Create the account (or sign in if it exists with the same password). Null = ok, else a readable error. */
    suspend fun signUpOrIn(c: Context, mail: String, password: String, create: Boolean): String? = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().put("email", mail.trim()).put("password", password).toString()
            val (code, res) = if (create) call("POST", "/auth/v1/signup", body, null) else call("POST", "/auth/v1/token?grant_type=password", body, null)
            if (code !in 200..299) return@runCatching err(res, if (create) "Could not create the account." else "Email or password is not right.")
            val s = AccountLogic.sessionFrom(res) ?: return@runCatching "Account created – sign in with it now."
            save(c, s)
            refreshMember(c)
            null
        }.getOrElse { "No connection to the account service." }
    }

    fun signOut(c: Context) { save(c, null); saveMember(c, null); status = "" }

    /** Reads this person's family membership. */
    suspend fun refreshMember(c: Context): Member? = withContext(Dispatchers.IO) {
        val t = token(c) ?: return@withContext null
        val uid = session?.userId ?: return@withContext null
        val (code, body) = runCatching { call("GET", "/rest/v1/members?select=household_id,role,display_name,avatar&user_id=eq.$uid", null, t) }.getOrNull() ?: return@withContext member
        if (code !in 200..299) return@withContext member
        val m = AccountLogic.memberFrom(body)
        withContext(Dispatchers.Main) { saveMember(c, m) }
        m
    }

    /** Owner: start the family (once). */
    suspend fun createFamily(c: Context, family: String, name: String, avatar: String): String? = rpc(c, "create_household",
        JSONObject().put("p_name", family).put("p_display", name).put("p_avatar", avatar)).let { (ok, res) -> if (ok) { refreshMember(c); null } else res }

    /** Family member: join with the owner's invite code. */
    suspend fun join(c: Context, code: String, name: String, avatar: String): String? = rpc(c, "join_household",
        JSONObject().put("p_code", code).put("p_display", name).put("p_avatar", avatar)).let { (ok, res) -> if (ok) { refreshMember(c); null } else res }

    /** Owner: a new invite code (returned), or null with [status] set. */
    suspend fun invite(c: Context, uses: Int): Pair<String?, String?> = rpc(c, "create_invite", JSONObject().put("p_uses", uses)).let { (ok, res) ->
        if (ok) res.trim().trim('"') to null else null to res
    }

    private suspend fun rpc(c: Context, fn: String, args: JSONObject): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val t = token(c) ?: return@withContext false to "Sign in first."
        runCatching {
            val (code, res) = call("POST", "/rest/v1/rpc/$fn", args.toString(), t)
            if (code in 200..299) true to res else false to err(res, "That did not work ($code).")
        }.getOrElse { false to "No connection to the account service." }
    }

    /** Names and avatars of the family (for the phone page). */
    suspend fun members(c: Context): List<Member> = withContext(Dispatchers.IO) {
        val t = token(c) ?: return@withContext emptyList()
        runCatching { call("GET", "/rest/v1/members?select=household_id,role,display_name,avatar&order=created_at", null, t) }.getOrNull()
            ?.takeIf { it.first in 200..299 }?.second?.let { AccountLogic.membersFrom(it) } ?: emptyList()
    }

    // ---------------------------------------------------------------- Shared setup

    /** Owner: put this TV's playlist and addons into the family setup. */
    suspend fun share(c: Context): String? = withContext(Dispatchers.IO) {
        val m = member ?: return@withContext "Not in a family yet."
        if (!m.owner) return@withContext "Only the owner can share the setup."
        val t = token(c) ?: return@withContext "Sign in first."
        Stremio.ensure(c)
        val url = PlaylistStore.savedUrl(c).orEmpty()
        val addons = JSONArray().apply { Stremio.addons.forEach { put(it.base) } }
        val body = JSONObject().put("playlist_url", url).put("addons", addons).put("updated_at", "now").toString()
        runCatching {
            val (code, res) = call("PATCH", "/rest/v1/household_config?household_id=eq.${m.householdId}", body, t)
            if (code in 200..299) null else err(res, "Could not share ($code).")
        }.getOrElse { "No connection to the account service." }
    }

    /**
     * Family member's TV: load the shared playlist when it is not the one in use, add shared addons that are missing and
     * remove ones the owner removed (only addons that came from the family setup). Runs at start and every few hours.
     */
    suspend fun sync(c: Context) {
        init(c)
        if (!signedIn) return
        val m = refreshMember(c) ?: return
        val t = withContext(Dispatchers.IO) { token(c) } ?: return
        val cfg = withContext(Dispatchers.IO) {
            runCatching { call("GET", "/rest/v1/household_config?select=playlist_url,addons&household_id=eq.${m.householdId}", null, t) }.getOrNull()
        }?.takeIf { it.first in 200..299 }?.second?.let { AccountLogic.configFrom(it) }
        if (cfg == null) { status = "Could not reach the family setup"; return }
        // The owner's TV is the source of the setup; only a fresh install of it (no playlist yet) takes the saved setup back
        if (m.owner && PlaylistStore.savedUrl(c) != null) { status = "Owner of the family setup"; return }
        Stremio.ensure(c)
        val fromFamily = prefs(c).getStringSet("fam_addons", emptySet()).orEmpty()
        val have = Stremio.addons.map { it.base.lowercase() }.toSet()
        for (a in cfg.second) if (a.lowercase() !in have) Stremio.add(c, a)
        for (a in fromFamily) if (cfg.second.none { it.equals(a, true) }) withContext(Dispatchers.Main) { Stremio.remove(c, a) }
        prefs(c).edit().putStringSet("fam_addons", cfg.second.toSet()).apply()
        val url = cfg.first
        if (url.isNotBlank() && url != PlaylistStore.savedUrl(c)) withContext(Dispatchers.Main) { PlaylistStore.load(c, url, save = true) }
        status = "Using the family setup"
    }
}

/** Pure JSON parts of the account (testable without Android). */
object AccountLogic {
    fun sessionFrom(body: String): FofAccount.Session? = runCatching {
        val o = JSONObject(body)
        val access = o.optString("access_token").ifBlank { return null }
        val user = o.optJSONObject("user") ?: return null
        val exp = o.optLong("expires_at").takeIf { it > 0 } ?: (System.currentTimeMillis() / 1000 + o.optLong("expires_in", 3600))
        FofAccount.Session(access, o.optString("refresh_token"), user.optString("id"), user.optString("email"), exp)
    }.getOrNull()

    fun writeSession(s: FofAccount.Session): String = JSONObject().put("a", s.access).put("r", s.refresh).put("u", s.userId).put("e", s.email).put("x", s.expiresAt).toString()
    fun readSession(raw: String): FofAccount.Session? = runCatching {
        val o = JSONObject(raw); FofAccount.Session(o.getString("a"), o.getString("r"), o.getString("u"), o.optString("e"), o.optLong("x"))
    }.getOrNull()

    private fun member(o: JSONObject) = FofAccount.Member(o.optString("household_id"), o.optString("role"), o.optString("display_name"), o.optString("avatar"))
    fun memberFrom(body: String): FofAccount.Member? = runCatching { JSONArray(body).optJSONObject(0)?.let(::member) }.getOrNull()
    fun membersFrom(body: String): List<FofAccount.Member> = runCatching { val a = JSONArray(body); (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::member) } }.getOrDefault(emptyList())
    fun writeMember(m: FofAccount.Member): String = JSONObject().put("household_id", m.householdId).put("role", m.role).put("display_name", m.name).put("avatar", m.avatar).toString()
    fun readMember(raw: String): FofAccount.Member? = runCatching { member(JSONObject(raw)) }.getOrNull()

    /** household_config row: playlist address and addon links. */
    fun configFrom(body: String): Pair<String, List<String>>? = runCatching {
        val o = JSONArray(body).optJSONObject(0) ?: return null
        val a = o.optJSONArray("addons") ?: JSONArray()
        o.optString("playlist_url") to (0 until a.length()).map { a.optString(it) }.filter { it.startsWith("http") }
    }.getOrNull()

    /** Readable error text from Supabase (auth: "msg"/"error_description", database: "message"). */
    fun message(body: String): String? = runCatching {
        val o = JSONObject(body)
        listOf("msg", "error_description", "message", "error").map { o.optString(it) }.firstOrNull { it.isNotBlank() }
    }.getOrNull()?.replaceFirstChar { it.uppercase() }
}
