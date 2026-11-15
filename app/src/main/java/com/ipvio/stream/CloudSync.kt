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
 * Netflix-style "your stuff follows you": a signed-in person's Continue watching, watched episodes, Recently watched,
 * My List and favorites are saved in their account (Supabase table user_data, only readable by that person) and come
 * back on every TV they sign in on.
 *
 * The account is linked to one profile on each TV: the profile that was active when signing in.
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

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("fof_account", Context.MODE_PRIVATE)

    /** Profile on this TV that follows the signed-in person. */
    fun linked(c: Context): Int = prefs(c).getInt("sync_profile", -1)

    fun link(c: Context, profileId: Int) {
        // A new link starts fresh: the first sync merges this TV's data with the saved one
        prefs(c).edit().putInt("sync_profile", profileId).remove("sync_hash").remove("sync_at").apply()
    }

    fun unlink(c: Context) {
        pending?.cancel()
        prefs(c).edit().remove("sync_profile").remove("sync_hash").remove("sync_at").apply()
        status = ""
    }

    /** Sync soon (after [delayMs]); called after every change of the personal stores. Repeated calls are merged. */
    fun kick(c: Context, delayMs: Long = 8_000) {
        val app = c.applicationContext
        if (!FofAccount.signedIn) return
        pending?.cancel()
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
        var pid = linked(c)
        if (pid < 0 || Profiles.list.none { it.id == pid }) { pid = Profiles.active; link(c, pid) }
        val p = prefs(c)
        val local = read(c, pid)
        val localChanged = SyncLogic.fingerprint(local) != p.getString("sync_hash", null)
        val cloudAt = FofAccount.userDataAt(c).getOrThrow()
        val cloudChanged = cloudAt != null && cloudAt != p.getString("sync_at", null)
        when (SyncLogic.decide(localChanged, cloudChanged, cloudAt != null)) {
            SyncLogic.Step.NOTHING -> {}
            SyncLogic.Step.UPLOAD -> upload(c, local)
            SyncLogic.Step.APPLY -> {
                val (data, at) = FofAccount.userDataGet(c).getOrThrow() ?: return
                val files = filesOf(data) ?: return
                write(c, pid, files)
                p.edit().putString("sync_hash", SyncLogic.fingerprint(read(c, pid))).putString("sync_at", at).apply()
                reloadIfActive(c, pid)
            }
            SyncLogic.Step.MERGE -> {
                val (data, _) = FofAccount.userDataGet(c).getOrThrow() ?: return upload(c, local)
                val merged = SyncLogic.merge(local, filesOf(data) ?: JSONObject())
                write(c, pid, merged)
                reloadIfActive(c, pid)
                upload(c, read(c, pid))
            }
        }
        status = "Synced " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date())
    }

    private fun upload(c: Context, files: JSONObject) {
        val at = FofAccount.userDataPut(c, JSONObject().put("v", 1).put("files", files)).getOrThrow()
        prefs(c).edit().putString("sync_hash", SyncLogic.fingerprint(files)).putString("sync_at", at).apply()
    }

    private fun filesOf(data: JSONObject): JSONObject? =
        data.optJSONObject("files")?.takeIf { data.toString().length <= SyncLogic.MAX_CHARS }

    private fun store(c: Context, name: String, pid: Int) =
        c.applicationContext.getSharedPreferences(Profiles.scopedName(name, pid), Context.MODE_PRIVATE)

    private fun read(c: Context, pid: Int): JSONObject {
        val o = JSONObject()
        for (n in SyncLogic.STORES) {
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
