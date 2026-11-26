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

    /**
     * One sign-in per TV (T-007): every profile on this TV uses it. Before, each profile signed in on its own
     * (store "fof_account" per profile); [init] takes the best of those once ([migrateDevice]) and leaves them in place.
     */
    internal fun devicePrefs(c: Context) = c.applicationContext.getSharedPreferences("fof_device_account", Context.MODE_PRIVATE)
    private fun prefs(c: Context) = devicePrefs(c)

    val userId: String? get() = session?.userId
    /** The public (anon) key, for the realtime feed ([LiveSync]); the same one every request sends. */
    internal val publicKey: String get() = KEY

    /** Signed in? As Compose state, so screens (sign-in on the TV) react the moment the phone finishes. */
    var signedInState by mutableStateOf(false)
        private set

    /** After switching profile: the TV's sign-in stays (kept for callers from before one account per household). */
    fun reload(c: Context) { init(c); signedInState = session != null }

    /** Takes over a sign-in from the per-profile stores once: the family owner's, else the main profile's, else any. */
    private fun migrateDevice(c: Context) {
        val dp = devicePrefs(c)
        if (dp.getBoolean("migrated", false)) return
        val meta = c.applicationContext.getSharedPreferences(Profiles.META, Context.MODE_PRIVATE)
        val ids = (meta.getString("ids", "0") ?: "0").split(",").mapNotNull { it.trim().toIntOrNull() }.ifEmpty { listOf(0) }
        val found = ids.mapNotNull { pid ->
            val sp = c.applicationContext.getSharedPreferences(Profiles.scopedName("fof_account", pid), Context.MODE_PRIVATE)
            val enc = sp.getString("s", null) ?: return@mapNotNull null
            Triple(pid, enc, sp.getString("m", null))
        }
        val pick = found.firstOrNull { t -> t.third?.let { AccountLogic.readMember(it)?.owner } == true }
            ?: found.firstOrNull { it.first == 0 } ?: found.firstOrNull()
        dp.edit().apply {
            if (pick != null) { putString("s", pick.second); pick.third?.let { putString("m", it) } }
            putBoolean("migrated", true)
        }.commit()
    }

    fun init(c: Context) {
        if (session != null) return
        migrateDevice(c)
        val enc = prefs(c).getString("s", null) ?: return
        val raw = Secrets.decrypt(enc) ?: return
        session = AccountLogic.readSession(raw)
        email = session?.email
        prefs(c).getString("m", null)?.let { member = AccountLogic.readMember(it) }
        signedInState = session != null
    }

    val signedIn: Boolean get() = session != null

    private fun save(c: Context, s: Session?) {
        session = s; email = s?.email; signedInState = s != null
        prefs(c).edit().apply {
            if (s == null) { remove("s"); remove("m") } else Secrets.encrypt(AccountLogic.writeSession(s))?.let { putString("s", it) }
        }.apply()
    }

    private fun saveMember(c: Context, m: Member?) {
        member = m
        prefs(c).edit().apply { if (m == null) remove("m") else putString("m", AccountLogic.writeMember(m)) }.apply()
    }

    // ---------------------------------------------------------------- HTTP

    internal fun call(method: String, path: String, body: String?, token: String?, prefer: String? = null): Pair<Int, String> {
        val b = Request.Builder().url(URL + path).header("apikey", KEY).header("Authorization", "Bearer " + (token ?: KEY))
        if (prefer != null) b.header("Prefer", prefer)
        if (method == "POST") b.post((body ?: "{}").toRequestBody(JSON)) else if (method == "PATCH") b.patch((body ?: "{}").toRequestBody(JSON)).header("Prefer", prefer ?: "return=minimal")
        Net.withTimeouts(8, 12).newCall(b.build()).execute().use { r -> return r.code to (r.body?.string().orEmpty()) }
    }

    /**
     * A valid access token (refreshed when it is about to run out), or null when signed out. One refresh at a time
     * (the refresh token changes with every use). No connection: the sign-in stays; the account service refusing the
     * refresh (signed out on another device, password changed): this TV signs out.
     */
    @Synchronized
    private fun token(c: Context): String? {
        val s = session ?: return null
        if (System.currentTimeMillis() / 1000 < s.expiresAt - 120) return s.access
        val (code, body) = call("POST", "/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", s.refresh).toString(), null)
        if (code in 400..403) { android.os.Handler(android.os.Looper.getMainLooper()).post { signOut(c, revoked = true) }; return null }
        if (code !in 200..299) return null
        val n = AccountLogic.sessionFrom(body) ?: return null
        save(c, n)
        return n.access
    }

    internal fun tokenFor(c: Context): String? = token(c)

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
            // The whole TV is now on this account: profiles, addons and everyone's progress come from it
            refreshMember(c)
            AccountCloud.afterSignIn(c)
            null
        }.getOrElse { "No connection to the account service." }
    }

    /**
     * One step for people: signs in, and creates the account when the email is new. Null = ok, else a readable error.
     */
    suspend fun signInOrCreate(c: Context, mail: String, password: String): String? {
        val e = signUpOrIn(c, mail, password, create = false) ?: return null
        val made = signUpOrIn(c, mail, password, create = true) ?: return null
        // The email exists: the password was wrong
        return if (made.contains("already", true) || made.contains("registered", true)) "Wrong password for this email." else made
    }

    /** Signs this TV out ([revoked] = it was signed out from another device, the session is already gone). */
    fun signOut(c: Context, revoked: Boolean = false) {
        val old = session
        if (old != null && !revoked) Thread { runCatching { call("POST", "/auth/v1/logout?scope=local", null, old.access) } }.start()
        save(c, null); saveMember(c, null)
        status = if (revoked) "This TV was signed out from the account." else ""
        CloudSync.unlink(c); AccountCloud.onSignedOut(); LiveSync.stop()
    }

    /**
     * Signs in again with the account password (forgotten PIN): the new sign-in counts as "just confirmed" for 10 minutes,
     * which lets this TV set any profile's PIN. The old sign-in of this TV is ended. Null = ok, else a readable error.
     */
    suspend fun confirmPassword(c: Context, password: String): String? = withContext(Dispatchers.IO) {
        val mail = session?.email ?: return@withContext "Sign in first."
        val old = session
        runCatching {
            val (code, res) = call("POST", "/auth/v1/token?grant_type=password", JSONObject().put("email", mail).put("password", password).toString(), null)
            if (code !in 200..299) return@runCatching "That is not the account password."
            val s = AccountLogic.sessionFrom(res) ?: return@runCatching "That did not work."
            save(c, s)
            if (old != null) runCatching { call("POST", "/auth/v1/logout?scope=local", null, old.access) }
            devicePrefs(c).edit().remove("reg_at").apply()
            AccountCloud.run(c)
            null
        }.getOrElse { "No connection to the account service." }
    }

    // ---------------------------------------------------------------- Personal data (CloudSync)

    /** Time of the last change of this person's saved data (null = nothing saved yet), or an error. */
    internal fun userDataAt(c: Context): Result<String?> = runCatching {
        val t = token(c) ?: error("signed out")
        val uid = session?.userId ?: error("signed out")
        val (code, body) = call("GET", "/rest/v1/user_data?select=updated_at&user_id=eq.$uid", null, t)
        if (code !in 200..299) error("HTTP $code")
        org.json.JSONArray(body).optJSONObject(0)?.optString("updated_at")?.ifBlank { null }
    }

    /** This person's saved data and its time. */
    internal fun userDataGet(c: Context): Result<Pair<JSONObject, String>?> = runCatching {
        val t = token(c) ?: error("signed out")
        val uid = session?.userId ?: error("signed out")
        val (code, body) = call("GET", "/rest/v1/user_data?select=data,updated_at&user_id=eq.$uid", null, t)
        if (code !in 200..299) error("HTTP $code")
        org.json.JSONArray(body).optJSONObject(0)?.let { (it.optJSONObject("data") ?: JSONObject()) to it.optString("updated_at") }
    }

    /** Saves this person's data; returns the server's time of the change. */
    internal fun userDataPut(c: Context, data: JSONObject): Result<String> = runCatching {
        val t = token(c) ?: error("signed out")
        val uid = session?.userId ?: error("signed out")
        val body = JSONObject().put("user_id", uid).put("data", data).toString()
        val (code, res) = call("POST", "/rest/v1/user_data?on_conflict=user_id", body, t, "resolution=merge-duplicates,return=representation")
        if (code !in 200..299) error("HTTP $code")
        org.json.JSONArray(res).optJSONObject(0)?.optString("updated_at")?.ifBlank { null } ?: error("no time")
    }

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

    // ---------------------------------------------------------------- Problem reports

    /** Sends this TV's last crash, freeze or forced close to the family (once per problem); the owner sees the list. */
    private suspend fun report(c: Context, m: Member, t: String) = withContext(Dispatchers.IO) {
        val (kind, at) = CrashLog.last(c) ?: return@withContext
        // Once per problem, and nothing older than a week (a first sign-in does not send an old crash)
        if (at <= prefs(c).getLong("rep_at", 0L) || System.currentTimeMillis() - at > 7 * 86_400_000L) return@withContext
        val body = AccountLogic.reportJson(m.householdId, m.name, kind, AppUpdate.currentName(c),
            (android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL).trim(), at)
        val (code, _) = call("POST", "/rest/v1/reports", body, t)
        if (code in 200..299) prefs(c).edit().putLong("rep_at", at).apply()
    }

    class Report(val who: String, val kind: String, val version: String, val device: String, val at: String)

    /** Owner: the latest problem reports of all TVs on the account (and of older family TVs). */
    suspend fun problems(c: Context): List<Report> = withContext(Dispatchers.IO) {
        val t = token(c) ?: return@withContext emptyList()
        val acct = runCatching { call("GET", "/rest/v1/fof_reports?select=member_name:profile,kind,version,device,happened_at&order=happened_at.desc&limit=25", null, t) }.getOrNull()
            ?.takeIf { it.first in 200..299 }?.second?.let { AccountLogic.reportsFrom(it) } ?: emptyList()
        val fam = runCatching { call("GET", "/rest/v1/reports?select=member_name,kind,version,device,happened_at&order=happened_at.desc&limit=25", null, t) }.getOrNull()
            ?.takeIf { it.first in 200..299 }?.second?.let { AccountLogic.reportsFrom(it) } ?: emptyList()
        (acct + fam).sortedByDescending { it.at }.take(25)
    }

    /** This TV's last crash or freeze, to the account (once per problem, nothing older than a week). */
    suspend fun reportToAccount(c: Context) = withContext(Dispatchers.IO) {
        val t = token(c) ?: return@withContext
        val (kind, at) = CrashLog.last(c) ?: return@withContext
        if (at <= prefs(c).getLong("rep_acct_at", 0L) || System.currentTimeMillis() - at > 7 * 86_400_000L) return@withContext
        // The problem's name stays as before; raw frames, memory and the last steps follow as details (split into rows)
        val code = AutoReport.post(c, t, Profiles.activeName(), ReportLogic.join(kind.take(380), CrashLog.details(c)), at)
        if (code in 200..299) prefs(c).edit().putLong("rep_acct_at", at).apply()
    }

    /** New account password (this TV stays signed in). Null = ok, else a readable error. */
    suspend fun changePassword(c: Context, password: String): String? = withContext(Dispatchers.IO) {
        if (password.length < 8) return@withContext "Use at least 8 characters."
        val t = token(c) ?: return@withContext "Sign in first."
        runCatching {
            val b = Request.Builder().url("$URL/auth/v1/user").header("apikey", KEY).header("Authorization", "Bearer $t")
                .put(JSONObject().put("password", password).toString().toRequestBody(JSON)).build()
            Net.withTimeouts(8, 12).newCall(b).execute().use { r -> if (r.isSuccessful) null else err(r.body?.string().orEmpty(), "Not changed (${r.code}).") }
        }.getOrElse { "No connection to the account service." }
    }

    /** Forgot password, step 1: the account service emails a 6-digit code (also when the email is unknown: says nothing about who has an account). */
    suspend fun sendReset(mail: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val (code, res) = call("POST", "/auth/v1/recover", JSONObject().put("email", mail.trim()).toString(), null)
            if (code in 200..299) null else err(res, "Could not send the code (${code}).")
        }.getOrElse { "No connection to the account service." }
    }

    /** Forgot password, step 2: the emailed code signs this TV in, then the new password is set. Null = done. */
    suspend fun resetWithCode(c: Context, mail: String, token: String, password: String): String? = withContext(Dispatchers.IO) {
        if (password.length < 8) return@withContext "Use at least 8 characters for the password."
        runCatching {
            val body = JSONObject().put("type", "recovery").put("email", mail.trim()).put("token", token.trim()).toString()
            val (code, res) = call("POST", "/auth/v1/verify", body, null)
            if (code !in 200..299) return@runCatching "That code is not right or has expired. Ask for a new one."
            val s = AccountLogic.sessionFrom(res) ?: return@runCatching "That code is not right or has expired. Ask for a new one."
            save(c, s)
            changePassword(c, password)?.let { return@runCatching it }
            refreshMember(c)
            AccountCloud.afterSignIn(c)
            null
        }.getOrElse { "No connection to the account service." }
    }

    /**
     * Deletes the account with every profile, PIN, addon list, device and saved progress (after the password is
     * confirmed again). This TV is signed out. Null = done, else a readable error.
     */
    suspend fun deleteAccount(c: Context, password: String): String? {
        confirmPassword(c, password)?.let { return it }
        val t = withContext(Dispatchers.IO) { token(c) } ?: return "Sign in first."
        val r = withContext(Dispatchers.IO) { runCatching { call("POST", "/rest/v1/rpc/fof_delete_account", "{}", t) }.getOrNull() }
            ?: return "No connection to the account service."
        if (r.first !in 200..299 || !r.second.contains("\"ok\": true") && !r.second.contains("\"ok\":true")) return "The account was not deleted."
        withContext(Dispatchers.Main) { signOut(c, revoked = true) }
        return null
    }

    // ---------------------------------------------------------------- Shared setup

    /** Owner: put this TV's playlist and addons into the family setup. */
    suspend fun share(c: Context, keepUrl: String? = null): String? = withContext(Dispatchers.IO) {
        val m = member ?: return@withContext "Not in a family yet."
        if (!m.owner) return@withContext "Only the owner can share the setup."
        val t = token(c) ?: return@withContext "Sign in first."
        Stremio.ensure(c)
        val url = keepUrl?.takeIf { it.isNotBlank() } ?: PlaylistStore.savedUrl(c).orEmpty()
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
        runCatching { report(c, m, t) }
        if (cfg == null) { status = "Could not reach the family setup"; return }
        // Addons and the playlist come from the account now ([AccountCloud]); the old shared setup only fills an empty TV
        Stremio.ensure(c)
        if (Stremio.addons.isEmpty()) for (a in cfg.second) Stremio.add(c, a)
        if (cfg.first.isNotBlank() && PlaylistStore.savedUrl(c) == null) withContext(Dispatchers.Main) { PlaylistStore.load(c, cfg.first, save = true) }
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
    fun reportJson(household: String, who: String, kind: String, version: String, device: String, atMs: Long): String =
        JSONObject().put("household_id", household).put("member_name", who.take(60)).put("kind", kind.take(400))
            .put("version", version.take(40)).put("device", device.take(80))
            .put("happened_at", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(atMs))).toString()

    fun reportsFrom(body: String): List<FofAccount.Report> = runCatching {
        val a = JSONArray(body)
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { FofAccount.Report(it.optString("member_name"), it.optString("kind"), it.optString("version"), it.optString("device"), it.optString("happened_at")) } }
    }.getOrDefault(emptyList())

    fun message(body: String): String? = runCatching {
        val o = JSONObject(body)
        listOf("msg", "error_description", "message", "error").map { o.optString(it) }.firstOrNull { it.isNotBlank() }
    }.getOrNull()?.replaceFirstChar { it.uppercase() }
}
