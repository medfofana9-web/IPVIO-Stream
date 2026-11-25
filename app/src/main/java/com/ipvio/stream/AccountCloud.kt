package com.ipvio.stream

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * One Fof TV account per household (T-007, schema docs/supabase/accounts.sql).
 *
 * ACCOUNT: signed in once per TV ([FofAccount], device-wide). Owns the profiles, the Stremio addons, the debrid token
 * and the provider playlist (encrypted on the server), and the list of signed-in devices.
 * PROFILE: a row in fof_profiles; each local profile on this TV is linked to one (map local id -> cloud id).
 * Its personal data syncs through [CloudSync]; the server only lets this TV reach a profile it opened with the PIN.
 *
 * [run] keeps this TV in line with the account: is the session still allowed, profiles (new, renamed, new picture,
 * deleted), PIN state, account addons / debrid / playlist. It runs after sign-in, at start, every 2 minutes and
 * shortly after an addon change.
 */
object AccountCloud {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    @Volatile private var pending: Job? = null

    data class PinState(val set: Boolean, val waitS: Int, val unlocked: Boolean)
    /** PIN state per cloud profile id (for the picker). */
    val pins = mutableStateMapOf<String, PinState>()
    /** Last result ("" = fine), for the account page. */
    var status by mutableStateOf("")
        private set
    /** The owner's choice: ask for the PIN even when the profile in use was already opened on this TV. */
    var pinAlways by mutableStateOf(false)
        private set

    private fun prefs(c: Context) = FofAccount.devicePrefs(c)

    // ---------------------------------------------------------------- local id <-> cloud profile

    private fun mapKey(c: Context) = "pmap_" + (FofAccount.userId ?: "")

    private fun map(c: Context): MutableMap<Int, String> =
        AccountCloudLogic.readMap(prefs(c).getString(mapKey(c), null)).toMutableMap()

    private fun saveMap(c: Context, m: Map<Int, String>) { prefs(c).edit().putString(mapKey(c), AccountCloudLogic.writeMap(m)).apply() }

    private fun psKey(lid: Int) = "ps_" + (FofAccount.userId ?: "") + "_" + lid

    /** Profiles this TV had before signing in to an account that already had profiles: on this TV only. */
    fun localOnly(c: Context): Set<Int> =
        prefs(c).getString("lo_" + (FofAccount.userId ?: ""), null).orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()

    /** "Add to account" on a profile that was on this TV only: it becomes its own profile in the account. */
    fun addToAccount(c: Context, localId: Int) {
        val lo = localOnly(c) - localId
        prefs(c).edit().putString("lo_" + (FofAccount.userId ?: ""), lo.joinToString(",")).apply()
        kick(c, 0)
    }

    /** Owner's profile: the account's lowest slot (fof_owner_profile on the server). */
    @Volatile var ownerCloudId: String? = null
        private set

    fun cloudId(c: Context, localId: Int): String? = if (FofAccount.signedIn) map(c)[localId] else null

    /** May this TV sync the profile's data now? (Not while it has a PIN and was not opened here.) */
    fun canSync(c: Context, localId: Int): Boolean {
        val id = cloudId(c, localId) ?: return false
        val p = pins[id] ?: return true
        return !p.set || p.unlocked
    }

    /** Profile opened on this TV with its PIN (the server holds the same; this copy is for working offline). */
    fun openedHere(c: Context): Int = prefs(c).getInt("unl_" + (FofAccount.userId ?: ""), -1)

    private fun setOpened(c: Context, localId: Int) { prefs(c).edit().putInt("unl_" + (FofAccount.userId ?: ""), localId).apply() }

    /** The profile's person chose no PIN (asked once; a PIN can still be set in Manage profiles). */
    fun noPin(c: Context, cloudId: String): Boolean = prefs(c).getBoolean("nopin_$cloudId", false)
    fun setNoPin(c: Context, cloudId: String) { prefs(c).edit().putBoolean("nopin_$cloudId", true).apply() }

    fun deviceId(c: Context): String {
        val p = prefs(c)
        p.getString("dev_id", null)?.let { return it }
        val id = java.util.UUID.randomUUID().toString()
        p.edit().putString("dev_id", id).apply()
        return id
    }

    // ---------------------------------------------------------------- HTTP

    private fun req(c: Context, method: String, path: String, body: String? = null, prefer: String? = null): Pair<Int, String> {
        val t = FofAccount.tokenFor(c) ?: error("signed out")
        return FofAccount.call(method, path, body, t, prefer)
    }

    private fun rpc(c: Context, fn: String, args: JSONObject = JSONObject()): Pair<Int, String> = req(c, "POST", "/rest/v1/rpc/$fn", args.toString())

    private fun ok(r: Pair<Int, String>): String = if (r.first in 200..299) r.second else error("HTTP ${r.first}")

    // ---------------------------------------------------------------- run

    /** Soon (after an addon, debrid or playlist change); repeated calls are merged. */
    fun kick(c: Context, delayMs: Long = 5_000) {
        if (!FofAccount.signedIn) return
        val app = c.applicationContext
        pending?.cancel()
        pending = scope.launch { delay(delayMs); run(app) }
    }

    fun afterSignIn(c: Context) {
        val app = c.applicationContext
        prefs(app).edit().remove("reg_at").apply()
        scope.launch { run(app); CloudSync.run(app) }
    }

    fun onSignedOut() { pins.clear(); status = "" }

    suspend fun run(c: Context) = withContext(Dispatchers.IO) {
        FofAccount.init(c)
        if (!FofAccount.signedIn) return@withContext
        lock.withLock {
            runCatching {
                if (!sessionAllowed(c)) return@runCatching
                register(c)
                profiles(c)
                pinStatus(c)
                settings(c)
                secrets(c)
                runCatching { FofAccount.reportToAccount(c) }
                status = ""
            }.onFailure { status = "Not synced: " + (it.message ?: "no connection").take(60) }
        }
    }

    /** False after this TV was signed out from another device: then it signs itself out. */
    private suspend fun sessionAllowed(c: Context): Boolean {
        val r = rpc(c, "fof_session_ok")
        if (r.first in 200..299 && r.second.trim() == "false") {
            withContext(Dispatchers.Main) { FofAccount.signOut(c, revoked = true) }
            return false
        }
        return true
    }

    private fun register(c: Context, force: Boolean = false) {
        val p = prefs(c)
        if (!force && System.currentTimeMillis() - p.getLong("reg_at", 0L) < 30 * 60_000L) return
        val args = JSONObject().put("p_id", deviceId(c)).put("p_name", deviceName(c)).put("p_kind", deviceKind(c)).put("p_version", AppUpdate.currentName(c))
        if (rpc(c, "fof_register_device", args).first in 200..299) p.edit().putLong("reg_at", System.currentTimeMillis()).apply()
    }

    fun deviceName(c: Context): String =
        runCatching { android.provider.Settings.Global.getString(c.contentResolver, "device_name") }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: (android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() } + " " + android.os.Build.MODEL).trim()

    private fun deviceKind(c: Context): String {
        val pm = c.packageManager
        return when {
            pm.hasSystemFeature("amazon.hardware.fire_tv") -> "Fire TV"
            pm.hasSystemFeature("com.google.android.feature.GOOGLE_EXPERIENCE") && pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK) -> "Google TV"
            pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK) -> "Android TV"
            else -> "Android"
        }
    }

    // ---------------------------------------------------------------- profiles

    private suspend fun profiles(c: Context) {
        val cloud = AccountCloudLogic.profilesFrom(ok(req(c, "GET", "/rest/v1/fof_profiles?select=id,slot,name,avatar,deleted&order=slot")))
        withContext(Dispatchers.Main) { Profiles.init(c) }
        // Personal data saved under an earlier sign-in of a profile (before one account per household): into the profile first
        for (p in Profiles.list.toList()) runCatching { legacyMerge(c, p.id) }
        val local = Profiles.list.map { AccountCloudLogic.LocalP(it.id, Profiles.displayName(it), it.id == 0 && it.name == Profiles.defaultName(0)) }
        val old = map(c)
        // Once per account on this TV: if the account already has profiles, the TV's unlinked profiles are its own
        val loKey = "lo_" + (FofAccount.userId ?: "")
        if (!prefs(c).contains(loKey)) {
            val lo = if (cloud.none { !it.deleted }) emptySet() else Profiles.list.map { it.id }.filter { it !in old }.toSet()
            prefs(c).edit().putString(loKey, lo.joinToString(",")).apply()
        }
        val plan = AccountCloudLogic.plan(local, cloud, old, Profiles.MAX, localOnly(c))
        val links = plan.links.toMutableMap()
        for (id in plan.removeLocal) {
            val gone = withContext(Dispatchers.Main) { id != Profiles.active && Profiles.remove(c, id) }
            // The profile in use stays until someone leaves it (its link is kept so it is not added again)
            if (gone) ProfileAvatars.set(c, id, null) else old[id]?.let { links[id] = it }
        }
        val byId = cloud.associateBy { it.id }.toMutableMap()
        for ((lid, slot) in plan.upload) {
            val nm = Profiles.list.firstOrNull { it.id == lid }?.let { Profiles.displayName(it) } ?: continue
            val body = JSONObject().put("slot", slot).put("name", nm.take(40)).put("avatar", ProfileAvatars.keyOf(c, lid).orEmpty().take(600))
            val r = req(c, "POST", "/rest/v1/fof_profiles", body.toString(), "return=representation")
            val made = AccountCloudLogic.profilesFrom(if (r.first in 200..299) r.second else "[]").firstOrNull() ?: continue
            links[lid] = made.id; byId[made.id] = made
            prefs(c).edit().remove(psKey(lid)).apply()
        }
        for (cp in plan.createLocal) {
            val lid = withContext(Dispatchers.Main) { Profiles.addNamed(c, cp.name) } ?: break
            links[lid] = cp.id
            // Set up already (on another TV, or skipped there): no taste questions again on this TV
            c.applicationContext.getSharedPreferences(Profiles.scopedName("fof_taste", lid), Context.MODE_PRIVATE).edit().putBoolean("done", true).apply()
            // The account's name and picture win on this new copy
            prefs(c).edit().putString(psKey(lid), AccountCloudLogic.snap(cp.name, "")).apply()
        }
        saveMap(c, links)
        ownerCloudId = cloud.filter { !it.deleted }.minByOrNull { it.slot }?.id
        // Names and pictures: the side that changed since the last run wins
        for ((lid, cid) in links) {
            val cp = byId[cid] ?: continue
            val p = Profiles.list.firstOrNull { it.id == lid } ?: continue
            val localName = Profiles.displayName(p)
            val localAv = ProfileAvatars.keyOf(c, lid).orEmpty()
            val snapRaw = prefs(c).getString(psKey(lid), null)
            val (sName, sAv) = AccountCloudLogic.readSnap(snapRaw) ?: (localName to localAv)
            var name = localName; var av = localAv
            val pushName = AccountCloudLogic.side(localName, cp.name, sName)
            val pushAv = AccountCloudLogic.side(localAv, cp.avatar, sAv)
            val patch = JSONObject()
            when (pushName) {
                AccountCloudLogic.Side.PUSH -> patch.put("name", localName.take(40))
                AccountCloudLogic.Side.APPLY -> if (cp.name.isNotBlank()) { name = cp.name; withContext(Dispatchers.Main) { Profiles.rename(c, lid, cp.name) } }
                AccountCloudLogic.Side.NONE -> {}
            }
            when (pushAv) {
                AccountCloudLogic.Side.PUSH -> {
                    patch.put("avatar", localAv.take(600))
                    patch.put("photo", if (localAv.startsWith("photo:")) ProfileAvatars.photoBytes(c, lid)?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }.orEmpty() else "")
                }
                AccountCloudLogic.Side.APPLY -> { av = cp.avatar; applyAvatar(c, lid, cid, cp.avatar) }
                AccountCloudLogic.Side.NONE -> {}
            }
            if (patch.length() > 0) runCatching { req(c, "PATCH", "/rest/v1/fof_profiles?id=eq.$cid", patch.toString()) }
            prefs(c).edit().putString(psKey(lid), AccountCloudLogic.snap(name, av)).apply()
        }
    }

    private fun applyAvatar(c: Context, lid: Int, cid: String, key: String) {
        when {
            key.isBlank() -> ProfileAvatars.set(c, lid, null)
            key.startsWith("photo:") -> {
                val b64 = runCatching { JSONArray(ok(req(c, "GET", "/rest/v1/fof_profiles?select=photo&id=eq.$cid"))).optJSONObject(0)?.optString("photo") }.getOrNull()
                val bytes = b64?.takeIf { it.isNotBlank() }?.let { runCatching { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }.getOrNull() }
                if (bytes != null) ProfileAvatars.savePhoto(c, lid, bytes, fromAccount = key)
            }
            AvatarValue.parse(key) != null -> ProfileAvatars.set(c, lid, key)
        }
    }

    /** Removes the profile from the account (owner's profile or the profile itself must be open on this TV). */
    suspend fun deleteProfile(c: Context, localId: Int): String? = withContext(Dispatchers.IO) {
        val cid = cloudId(c, localId) ?: return@withContext null
        runCatching {
            val r = req(c, "PATCH", "/rest/v1/fof_profiles?id=eq.$cid", JSONObject().put("deleted", true).toString())
            if (r.first !in 200..299) return@runCatching "Could not remove it (${r.first})."
            // PATCH answers 204 also when the server refused the row: check it is gone
            val still = AccountCloudLogic.profilesFrom(ok(req(c, "GET", "/rest/v1/fof_profiles?select=id,slot,name,avatar,deleted&id=eq.$cid")))
            if (still.firstOrNull()?.deleted == false) "Open the owner's profile (or this profile) first, then remove it." else {
                saveMap(c, map(c).also { it.remove(localId) }); null
            }
        }.getOrElse { "No connection to the account." }
    }

    // ---------------------------------------------------------------- PINs

    private fun pinStatus(c: Context) {
        val a = JSONArray(ok(rpc(c, "fof_pin_status")))
        val m = HashMap<String, PinState>()
        for (i in 0 until a.length()) a.optJSONObject(i)?.let { m[it.optString("profile_id")] = PinState(it.optBoolean("pin_set"), it.optInt("wait_s"), it.optBoolean("unlocked")) }
        pins.clear(); pins.putAll(m)
    }

    /** Fresh PIN state (picker), quietly ignoring a missing connection. */
    suspend fun refreshPins(c: Context) = withContext(Dispatchers.IO) { if (FofAccount.signedIn) runCatching { lock.withLock { profiles(c); pinStatus(c) } } }

    sealed class PinResult {
        object Ok : PinResult()
        class Wrong(val left: Int) : PinResult()
        class Locked(val waitS: Int) : PinResult()
        object Offline : PinResult()
        class Failed(val text: String) : PinResult()
    }

    suspend fun verifyPin(c: Context, localId: Int, pin: String): PinResult = withContext(Dispatchers.IO) {
        val cid = cloudId(c, localId) ?: return@withContext PinResult.Failed("This profile is not in the account yet.")
        val r = runCatching { rpc(c, "fof_verify_pin", JSONObject().put("p_profile", cid).put("p_pin", pin)) }.getOrNull() ?: return@withContext PinResult.Offline
        if (r.first !in 200..299) return@withContext PinResult.Failed("The account did not answer (${r.first}).")
        val o = runCatching { JSONObject(r.second) }.getOrNull() ?: return@withContext PinResult.Failed("The account did not answer.")
        when {
            o.optBoolean("ok") -> { setOpened(c, localId); pins[cid] = PinState(true, 0, true); PinResult.Ok }
            o.optString("err") == "locked" -> PinResult.Locked(o.optInt("wait_s", 60))
            o.optString("err") == "wrong" -> PinResult.Wrong(o.optInt("left"))
            o.optString("err") == "no_pin" -> PinResult.Failed("no_pin")
            else -> PinResult.Failed("This profile could not be opened.")
        }
    }

    /** Sets the PIN (4 to 6 digits); the profile is then open on this TV. Null = ok, else a readable error. */
    suspend fun setPin(c: Context, localId: Int, pin: String): String? = withContext(Dispatchers.IO) {
        if (!AccountCloudLogic.validPin(pin)) return@withContext "Use 4 to 6 digits."
        val cid = cloudId(c, localId) ?: return@withContext "This profile is not in the account yet."
        val r = runCatching { rpc(c, "fof_set_pin", JSONObject().put("p_profile", cid).put("p_pin", pin)) }.getOrNull()
            ?: return@withContext "No connection to the account."
        val o = runCatching { JSONObject(r.second) }.getOrNull()
        if (r.first in 200..299 && o?.optBoolean("ok") == true) { setOpened(c, localId); pins[cid] = PinState(true, 0, true); null }
        else when (o?.optString("err")) {
            "denied" -> "Open this profile (or the owner's) first, or use \"Forgot PIN\"."
            "format" -> "Use 4 to 6 digits."
            else -> "The PIN was not saved."
        }
    }

    // ---------------------------------------------------------------- account settings

    private fun settings(c: Context) {
        val o = JSONArray(ok(req(c, "GET", "/rest/v1/fof_account_config?select=settings"))).optJSONObject(0)?.optJSONObject("settings")
        pinAlways = o?.optBoolean("pin_always") ?: false
    }

    suspend fun setPinAlways(c: Context, v: Boolean): String? = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().put("settings", JSONObject().put("pin_always", v)).toString()
            val r = req(c, "POST", "/rest/v1/fof_account_config?on_conflict=account_id", body, "resolution=merge-duplicates,return=minimal")
            if (r.first in 200..299) { pinAlways = v; null } else "Not saved (${r.first})."
        }.getOrElse { "No connection to the account." }
    }

    // ---------------------------------------------------------------- devices

    class Device(val id: String, val name: String, val kind: String, val version: String, val lastSeen: String, val revoked: Boolean)

    suspend fun devices(c: Context): List<Device>? = withContext(Dispatchers.IO) {
        runCatching {
            AccountCloudLogic.devicesFrom(ok(req(c, "GET", "/rest/v1/fof_devices?select=id,name,kind,app_version,last_seen,revoked&order=last_seen.desc")))
        }.getOrNull()
    }

    suspend fun renameDevice(c: Context, id: String, name: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { rpc(c, "fof_rename_device", JSONObject().put("p_id", id).put("p_name", name.trim().take(40))).first in 200..299 }.getOrDefault(false)
    }

    suspend fun revokeDevice(c: Context, id: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { rpc(c, "fof_revoke_device", JSONObject().put("p_id", id)).first in 200..299 }.getOrDefault(false)
    }

    /** Signs out every other device for real (their sessions end at the account service), this TV stays. */
    suspend fun signOutOthers(c: Context): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val a = rpc(c, "fof_revoke_others").first in 200..299
            val b = req(c, "POST", "/auth/v1/logout?scope=others").first in 200..299
            a && b
        }.getOrDefault(false)
    }

    // ---------------------------------------------------------------- account addons, debrid, playlist

    private suspend fun secrets(c: Context) {
        val a = JSONArray(ok(rpc(c, "fof_secrets_get")))
        val cloud = HashMap<String, Pair<String, String>>()
        for (i in 0 until a.length()) a.optJSONObject(i)?.let { cloud[it.optString("name")] = it.optString("value") to it.optString("updated_at") }
        Stremio.ensure(c)
        syncSecret(c, "addons", cloud["addons"], { AccountCloudLogic.addonsJson(Stremio.addons.map { it.base }) }) { cloudVal, first ->
            val want = AccountCloudLogic.parseAddons(cloudVal)
            applyAddons(c, want, removeOthers = !first)
        }
        Debrid.init(c)
        syncSecret(c, "debrid", cloud["debrid"], { Debrid.raw() }) { v, _ -> if (v.isNotBlank() || Debrid.on) Debrid.save(c, v) }
        syncSecret(c, "playlist", cloud["playlist"], { PlaylistStore.savedUrl(c).orEmpty() }) { v, _ ->
            if (v.isNotBlank() && v != PlaylistStore.savedUrl(c)) restorePlaylistOnce(c, v)
        }
        // Account sync runs every two minutes. Never re-download a failed large playlist
        // on every sync: retry after a cooldown, or when the user explicitly retries.
        val cv = cloud["playlist"]?.first.orEmpty()
        if (cv.isNotBlank() && PlaylistStore.savedUrl(c) == null) restorePlaylistOnce(c, cv)
    }

    /** One automatic cloud-restore attempt per account/playlist per 30 minutes.
     * A failed provider or network must not cause an endless download loop.
     * Manual playlist loading is deliberately unaffected.
     */
    private suspend fun restorePlaylistOnce(c: Context, url: String) {
        if (PlaylistStore.savedUrl(c) == url) return
        val key = "restore_try_" + (FofAccount.userId ?: return) + "_" + AccountCloudLogic.hash(url)
        val p = prefs(c)
        val now = System.currentTimeMillis()
        val last = p.getLong(key, 0L)
        if (last > 0L && now >= last && now - last < 30 * 60_000L) return
        // Persist before starting: a crash or cancelled load must not trigger a retry storm.
        p.edit().putLong(key, now).apply()
        withContext(Dispatchers.Main) {
            PlaylistStore.load(c, url, save = true, ask = false)
        }
    }

    /**
     * One account value: [read] gives this TV's value, [apply] takes the account's (second argument: first sync on this TV).
     * Same steps as the profile data ([SyncLogic.decide]); addons are joined when both sides changed, otherwise on a TV
     * that syncs for the first time the account's value wins, later this TV's.
     */
    private suspend fun syncSecret(c: Context, name: String, cloud: Pair<String, String>?, read: () -> String, apply: suspend (String, Boolean) -> Unit) {
        val p = prefs(c)
        val uid = FofAccount.userId ?: return
        val hk = "xh_${uid}_$name"; val ak = "xa_${uid}_$name"
        val first = p.getString(hk, null) == null
        val local = read()
        val localChanged = AccountCloudLogic.hash(local) != p.getString(hk, null)
        val cloudChanged = cloud != null && cloud.second != p.getString(ak, null)
        fun mark(v: String, at: String) { p.edit().putString(hk, AccountCloudLogic.hash(v)).putString(ak, at).remove("xp_${uid}_$name").apply() }
        // Addons that cannot be added yet are retried on every sync; an addon that stays unreachable for a day (removed by
        // its author) no longer blocks the account's addon sync
        fun waitMore(): Boolean {
            val pk = "xp_${uid}_$name"; val now = System.currentTimeMillis()
            val since = p.getLong(pk, 0L).takeIf { it in 1..now } ?: now.also { p.edit().putLong(pk, it).apply() }
            return now - since < 24 * 3600_000L
        }
        suspend fun upload(v: String) {
            val r = rpc(c, "fof_secret_put", JSONObject().put("p_name", name).put("p_value", v))
            if (r.first in 200..299) mark(v, r.second.trim().trim('"'))
        }
        when (SyncLogic.decide(localChanged, cloudChanged, cloud != null)) {
            SyncLogic.Step.NOTHING -> {}
            // A TV that never synced does not send "nothing"; later an emptied value (addons removed) is sent
            SyncLogic.Step.UPLOAD -> if (!(first && (local.isBlank() || local == "[]"))) upload(local)
            SyncLogic.Step.APPLY -> {
                apply(cloud!!.first, false)
                // Addons: an addon whose manifest could not be read right now (network change, slow server) is not on this
                // TV yet. Marking the account value as applied would leave it missing for good – and the next local change
                // would even upload the shorter list and remove it from every TV. Leave it unmarked: the next sync retries.
                if (name != "addons" || AccountCloudLogic.covers(read(), cloud.first) || !waitMore()) mark(read(), cloud.second)
            }
            SyncLogic.Step.MERGE -> {
                val cv = cloud!!.first
                if (name == "addons") {
                    val joined = AccountCloudLogic.mergeAddons(AccountCloudLogic.parseAddons(local), AccountCloudLogic.parseAddons(cv))
                    applyAddons(c, joined, removeOthers = false)
                    val now = read()
                    // Not every account addon could be added (manifest unreachable): neither upload the shorter list
                    // (that would remove the addon from the account) nor mark it done; the next sync retries
                    if (!AccountCloudLogic.covers(now, AccountCloudLogic.addonsJson(joined)) && waitMore()) return
                    if (AccountCloudLogic.parseAddons(now).map { it.lowercase() }.toSet() != AccountCloudLogic.parseAddons(cv).map { it.lowercase() }.toSet()) upload(now) else mark(now, cloud.second)
                } else if (local.isBlank() || (first && cv.isNotBlank())) { apply(cv, true); mark(read(), cloud.second) }
                else upload(local)
            }
        }
    }

    private suspend fun applyAddons(c: Context, want: List<String>, removeOthers: Boolean) {
        fun k(u: String) = (StremioLogic.base(u) ?: u).lowercase()
        val have = Stremio.addons.map { k(it.base) }.toSet()
        for (a in want) if (k(a) !in have) {
            val err = runCatching { Stremio.add(c, a) }.getOrElse { it.message ?: "failed" }
            if (err != null) Diag.report("Addon sync: could not add " + AccountCloudLogic.addonHost(a) + " – " + err.take(80))
        }
        if (removeOthers) {
            val keep = want.map(::k).toSet()
            for (a in Stremio.addons.toList()) if (k(a.base) !in keep) withContext(Dispatchers.Main) { Stremio.remove(c, a.base) }
        }
    }

    // ---------------------------------------------------------------- data of earlier per-profile sign-ins

    /**
     * Before one account per household, each profile signed in on its own. That person's saved data (user_data) is merged
     * into the local profile once, so it goes into the account's profile and nothing is lost.
     */
    private suspend fun legacyMerge(c: Context, pid: Int) {
        val p = prefs(c)
        val key = "mig_" + pid
        if (p.getBoolean(key, false)) return
        val sp = c.applicationContext.getSharedPreferences(Profiles.scopedName("fof_account", pid), Context.MODE_PRIVATE)
        val s = sp.getString("s", null)?.let { Secrets.decrypt(it) }?.let { AccountLogic.readSession(it) }
        if (s == null) { p.edit().putBoolean(key, true).apply(); return }
        var token = s.access
        if (System.currentTimeMillis() / 1000 >= s.expiresAt - 120) {
            val (code, body) = FofAccount.call("POST", "/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", s.refresh).toString(), null)
            if (code in 400..499) { p.edit().putBoolean(key, true).apply(); return }   // that sign-in is gone
            val n = AccountLogic.sessionFrom(body) ?: error("refresh $code")
            Secrets.encrypt(AccountLogic.writeSession(n))?.let { sp.edit().putString("s", it).apply() }
            token = n.access
        }
        val (code, body) = FofAccount.call("GET", "/rest/v1/user_data?select=data&user_id=eq.${s.userId}", null, token)
        if (code in 400..499) { p.edit().putBoolean(key, true).apply(); return }
        if (code !in 200..299) error("HTTP $code")
        val files = JSONArray(body).optJSONObject(0)?.optJSONObject("data")?.optJSONObject("files")
        if (files != null) CloudSync.mergeLocal(c, pid, files)
        p.edit().putBoolean(key, true).apply()
    }
}

/** Pure parts of [AccountCloud] (testable without Android). */
object AccountCloudLogic {
    class LocalP(val id: Int, val name: String, val blank: Boolean)
    class CloudP(val id: String, val slot: Int, val name: String, val avatar: String, val deleted: Boolean)
    class Plan(val links: Map<Int, String>, val upload: List<Pair<Int, Int>>, val createLocal: List<CloudP>, val removeLocal: List<Int>)

    private fun norm(s: String) = s.trim().lowercase()

    /**
     * Lines up this TV's profiles with the account's.
     * - Existing links stay; a link to a profile deleted in the account removes the local copy.
     * - Profiles this TV had before it signed in to an account that already had profiles ([localOnly]) stay on this TV
     *   only (shown apart, never mixed in); any other unlinked profile is added to the account as its own profile.
     * - Account profiles this TV does not have yet are added here.
     * Both sides stay within [max] profiles.
     */
    fun plan(local: List<LocalP>, cloud: List<CloudP>, map: Map<Int, String>, max: Int = 10, localOnly: Set<Int> = emptySet()): Plan {
        val live = cloud.filter { !it.deleted }
        val liveIds = live.map { it.id }.toSet()
        val deletedIds = cloud.filter { it.deleted }.map { it.id }.toSet()
        val localIds = local.map { it.id }.toSet()
        val links = LinkedHashMap<Int, String>()
        val remove = ArrayList<Int>()
        for ((lid, cid) in map) {
            if (lid !in localIds) continue
            if (cid in liveIds) links[lid] = cid else if (cid in deletedIds) remove += lid
        }
        val taken = links.values.toMutableSet()
        val upload = ArrayList<Int>()
        // Never merged by name or position: a profile that is not linked is either this TV's own ([localOnly], kept apart)
        // or new on this TV (added to the account as its own profile)
        for (lp in local.sortedBy { it.id }) {
            if (lp.id in links || lp.id in remove || lp.id in localOnly) continue
            upload += lp.id
        }
        val usedSlots = cloud.map { it.slot }.toMutableSet()
        val room = (max - live.size).coerceAtLeast(0)
        val up = upload.take(room).map { lid ->
            var s = 0
            while (s in usedSlots) s++
            usedSlots += s
            lid to s
        }
        val localRoom = (max - (local.size - remove.size)).coerceAtLeast(0)
        val create = live.filter { it.id !in taken }.take(localRoom)
        return Plan(links, up, create, remove)
    }

    enum class Side { NONE, PUSH, APPLY }

    /** Which side wins for a value synced both ways: the one that changed since the last sync ([snap]); this TV on a tie. */
    fun side(local: String, cloud: String, snap: String): Side = when {
        local == cloud -> Side.NONE
        local != snap -> Side.PUSH
        else -> Side.APPLY
    }

    fun snap(name: String, avatar: String): String = JSONArray().put(name).put(avatar).toString()
    fun readSnap(raw: String?): Pair<String, String>? = raw?.let { runCatching { JSONArray(it).let { a -> a.getString(0) to a.getString(1) } }.getOrNull() }

    fun validPin(p: String): Boolean = p.length in 4..6 && p.all { it in '0'..'9' }

    fun readMap(raw: String?): Map<Int, String> = runCatching {
        val o = JSONObject(raw ?: return emptyMap())
        o.keys().asSequence().mapNotNull { k -> k.toIntOrNull()?.let { it to o.getString(k) } }.toMap()
    }.getOrDefault(emptyMap())

    fun writeMap(m: Map<Int, String>): String = JSONObject().apply { m.forEach { (k, v) -> put(k.toString(), v) } }.toString()

    fun profilesFrom(body: String): List<CloudP> = runCatching {
        val a = JSONArray(body)
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { CloudP(it.optString("id"), it.optInt("slot"), it.optString("name"), it.optString("avatar"), it.optBoolean("deleted")) } }
            .filter { it.id.isNotBlank() }
    }.getOrDefault(emptyList())

    fun devicesFrom(body: String): List<AccountCloud.Device> = runCatching {
        val a = JSONArray(body)
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let {
            AccountCloud.Device(it.optString("id"), it.optString("name"), it.optString("kind"), it.optString("app_version"), it.optString("last_seen"), it.optBoolean("revoked"))
        } }
    }.getOrDefault(emptyList())

    fun addonsJson(list: List<String>): String = JSONArray(list).toString()

    /** Does this TV's addon list [localJson] hold every addon of [wantJson] (case-insensitive base URLs)? */
    fun covers(localJson: String, wantJson: String): Boolean {
        fun k(u: String) = (StremioLogic.base(u) ?: u).lowercase()
        val have = parseAddons(localJson).map(::k).toSet()
        return parseAddons(wantJson).all { k(it) in have }
    }

    /** Host of an addon link for logs: never the path, which can carry the addon's private configuration. */
    fun addonHost(base: String): String = base.substringAfter("://").substringBefore('/').take(60)
    fun parseAddons(raw: String): List<String> = runCatching {
        val a = JSONArray(raw); (0 until a.length()).map { a.optString(it) }.filter { it.startsWith("http") }
    }.getOrDefault(emptyList())

    /** Joined addon list: this TV's order first, then the account's others (no duplicates). */
    fun mergeAddons(local: List<String>, cloud: List<String>): List<String> = (local + cloud).distinctBy { it.lowercase() }

    fun hash(v: String): String = java.security.MessageDigest.getInstance("SHA-256").digest(v.toByteArray()).joinToString("") { "%02x".format(it) }

    /** "Try again in 2 minutes" text for a locked PIN. */
    fun waitText(s: Int): String = if (s <= 60) "Try again in a minute." else "Try again in ${(s + 59) / 60} minutes."
}
