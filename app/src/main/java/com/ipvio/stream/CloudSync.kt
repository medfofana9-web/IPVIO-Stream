package com.ipvio.stream

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Netflix-style "your stuff follows you": each profile's Continue watching, watched episodes, Recently watched,
 * My List, favorites, sports teams and taste are saved with that profile in the account (table fof_profile_data) and
 * come back on every TV signed in to the account. The server only lets a TV reach a profile it opened with the PIN.
 *
 * The profile in use syncs; its link to the account profile comes from [AccountCloud].
 * When: shortly after a change (playback stopped, title added), when the app comes back or goes to the background,
 * and every 2 minutes while it is open. If both TVs changed something, both versions are merged ([SyncLogic.merge]).
 */
object CloudSync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    @Volatile private var pending: Job? = null

    /** Last result for the phone page and Diagnostics ("Synced 10:42", or the problem). */
    @Volatile var status: String = ""
        private set

    // Sync state per account and profile, on this TV
    private fun prefs(c: Context) = FofAccount.devicePrefs(c)
    private fun hk(pid: Int, cid: String) = "sh_${cid}_$pid"
    private fun ak(pid: Int, cid: String) = "sa_${cid}_$pid"

    /** Profile on this TV whose data syncs now (the one in use), -1 when signed out. */
    fun linked(c: Context): Int = if (FofAccount.signedIn) Profiles.active else -1

    fun unlink(c: Context) {
        pending?.cancel()
        status = ""
    }

    /** Merges saved data (e.g. of an earlier sign-in) into a profile on this TV; the next sync takes it to the account. */
    internal fun mergeLocal(c: Context, pid: Int, files: JSONObject) {
        val merged = SyncLogic.merge(read(c, pid), files)
        write(c, pid, merged)
        kotlinx.coroutines.runBlocking { reloadIfActive(c, pid) }
    }

    /**
     * Sync soon (after [delayMs]); called after every change of the personal stores. Repeated calls join the sync
     * already scheduled instead of pushing it back: playback saves progress every 5 s, so a restarting timer never
     * fired while watching (progress only reached the account every 2 minutes or after stopping).
     */
    fun kick(c: Context, delayMs: Long = 8_000) {
        val app = c.applicationContext
        if (!FofAccount.signedIn) return
        val p = pending
        if (delayMs > 0 && p != null && p.isActive) return
        p?.cancel()
        pending = scope.launch {
            delay(delayMs)
            run(app)
        }
    }

    /** Sync now (app back in front, app leaving, periodic). */
    fun now(c: Context) = kick(c, 0)

    suspend fun run(c: Context) = withContext(Dispatchers.IO) {
        FofAccount.init(c)
        if (!FofAccount.signedIn) return@withContext
        lock.withLock {
            runCatching { once(c) }.onFailure { status = "Not synced: " + (it.message ?: "no connection").take(60) }
        }
    }

    private suspend fun once(c: Context) {
        withContext(Dispatchers.Main) { Profiles.init(c) }
        val pid = Profiles.active
        if (AccountCloud.cloudId(c, pid) == null) AccountCloud.run(c)
        val cid = AccountCloud.cloudId(c, pid)
        if (cid == null) { status = "This profile is not in the account yet"; return }
        if (!AccountCloud.canSync(c, pid)) { status = "Open this profile with its PIN to sync it"; return }
        val p = prefs(c)
        val local = read(c, pid)
        val localChanged = SyncLogic.fingerprint(local) != p.getString(hk(pid, cid), null)
        val cloudAt = dataAt(c, cid)
        val cloudChanged = cloudAt != null && cloudAt != p.getString(ak(pid, cid), null)
        when (SyncLogic.decide(localChanged, cloudChanged, cloudAt != null)) {
            SyncLogic.Step.NOTHING -> {}
            SyncLogic.Step.UPLOAD -> upload(c, pid, cid, local)
            SyncLogic.Step.APPLY -> {
                val (data, at) = dataGet(c, cid) ?: return
                val files = filesOf(data) ?: return
                write(c, pid, files)
                p.edit().putString(hk(pid, cid), SyncLogic.fingerprint(read(c, pid))).putString(ak(pid, cid), at).apply()
                reloadIfActive(c, pid)
            }
            SyncLogic.Step.MERGE -> {
                val (data, _) = dataGet(c, cid) ?: return upload(c, pid, cid, local)
                val merged = SyncLogic.merge(local, filesOf(data) ?: JSONObject())
                write(c, pid, merged)
                reloadIfActive(c, pid)
                upload(c, pid, cid, read(c, pid))
            }
        }
        status = "Synced " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date())
    }

    private fun upload(c: Context, pid: Int, cid: String, files: JSONObject) {
        val at = dataPut(c, cid, JSONObject().put("v", 1).put("files", files))
        prefs(c).edit().putString(hk(pid, cid), SyncLogic.fingerprint(files)).putString(ak(pid, cid), at).apply()
    }

    private fun get(c: Context, path: String): String {
        val t = FofAccount.tokenFor(c) ?: error("signed out")
        val (code, body) = FofAccount.call("GET", path, null, t)
        if (code !in 200..299) error("HTTP $code")
        return body
    }

    /** Time of the profile's last saved change (null = nothing saved yet). */
    private fun dataAt(c: Context, cid: String): String? =
        org.json.JSONArray(get(c, "/rest/v1/fof_profile_data?select=updated_at&profile_id=eq.$cid")).optJSONObject(0)?.optString("updated_at")?.ifBlank { null }

    private fun dataGet(c: Context, cid: String): Pair<JSONObject, String>? =
        org.json.JSONArray(get(c, "/rest/v1/fof_profile_data?select=data,updated_at&profile_id=eq.$cid")).optJSONObject(0)
            ?.let { (it.optJSONObject("data") ?: JSONObject()) to it.optString("updated_at") }

    /** Saves the profile's data; returns the server's time of the change. */
    private fun dataPut(c: Context, cid: String, data: JSONObject): String {
        val t = FofAccount.tokenFor(c) ?: error("signed out")
        val body = JSONObject().put("profile_id", cid).put("data", data).toString()
        val (code, res) = FofAccount.call("POST", "/rest/v1/fof_profile_data?on_conflict=profile_id", body, t, "resolution=merge-duplicates,return=representation")
        if (code !in 200..299) error("HTTP $code")
        return org.json.JSONArray(res).optJSONObject(0)?.optString("updated_at")?.ifBlank { null } ?: error("no time")
    }

    private fun filesOf(data: JSONObject): JSONObject? =
        data.optJSONObject("files")?.takeIf { data.toString().length <= SyncLogic.MAX_CHARS }

    private fun store(c: Context, name: String, pid: Int) =
        c.applicationContext.getSharedPreferences(Profiles.scopedName(name, pid), Context.MODE_PRIVATE)

    private fun read(c: Context, pid: Int): JSONObject {
        val o = JSONObject()
        for (n in SyncLogic.STORES) {
            if (n == SyncLogic.NAME) {
                Profiles.list.firstOrNull { it.id == pid }?.let { p ->
                    val nm = Profiles.displayName(p)
                    if (nm.isNotBlank() && nm != Profiles.defaultName(pid)) o.put(n, SyncLogic.encode(mapOf("n" to nm)))
                }
                continue
            }
            if (n == SyncLogic.AVATAR) {
                ProfileAvatars.keyOf(c, pid)?.let { k ->
                    val m = HashMap<String, Any>(); m["a"] = k
                    if (k.startsWith("photo:")) ProfileAvatars.photoBytes(c, pid)?.let { m["ph"] = android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }
                    o.put(n, SyncLogic.encode(m))
                }
                continue
            }
            val all = store(c, n, pid).all
            if (all.isNotEmpty()) o.put(n, SyncLogic.encode(all))
        }
        return o
    }

    /** Replaces the synced stores of the profile with [files] (stores missing there are left alone). */
    private fun write(c: Context, pid: Int, files: JSONObject) {
        for (n in SyncLogic.STORES) {
            val o = files.optJSONObject(n) ?: continue
            if (n == SyncLogic.NAME) {
                (SyncLogic.decodeValue(o.optJSONArray("n")) as? String)?.trim()?.take(40)?.takeIf { it.isNotBlank() }?.let { nm ->
                    if (Profiles.list.firstOrNull { it.id == pid }?.let { Profiles.displayName(it) } != nm)
                        kotlinx.coroutines.runBlocking(Dispatchers.Main) { Profiles.rename(c, pid, nm) }
                }
                continue
            }
            if (n == SyncLogic.AVATAR) {
                val k = SyncLogic.decodeValue(o.optJSONArray("a")) as? String
                val ph = (SyncLogic.decodeValue(o.optJSONArray("ph")) as? String)?.let { runCatching { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }.getOrNull() }
                when {
                    k == null -> {}
                    k.startsWith("photo:") -> if (ph != null && k != ProfileAvatars.keyOf(c, pid)) ProfileAvatars.savePhoto(c, pid, ph, fromAccount = k)
                    AvatarValue.parse(k) != null -> if (k != ProfileAvatars.keyOf(c, pid)) ProfileAvatars.set(c, pid, k)
                }
                continue
            }
            val e = store(c, n, pid).edit().clear()
            for (k in o.keys()) {
                @Suppress("UNCHECKED_CAST")
                when (val v = SyncLogic.decodeValue(o.optJSONArray(k))) {
                    is String -> e.putString(k, v)
                    is Boolean -> e.putBoolean(k, v)
                    is Int -> e.putInt(k, v)
                    is Long -> e.putLong(k, v)
                    is Float -> e.putFloat(k, v)
                    is Set<*> -> e.putStringSet(k, v as Set<String>)
                }
            }
            e.commit()
        }
    }

    /** The screens show the new state right away when the synced profile is the one in use. */
    private suspend fun reloadIfActive(c: Context, pid: Int) {
        if (pid != Profiles.active) return
        withContext(Dispatchers.Main) {
            runCatching {
                WatchStore.reload(c); Library.reload(c); History.reload(c); EpProgress.dropCache()
                CatalogItems.reload(c); CatFavs.reload(c); ChannelLists.reload(c); SportLeagues.reload(c); SportFavs.reload(c)
            }
        }
    }
}
