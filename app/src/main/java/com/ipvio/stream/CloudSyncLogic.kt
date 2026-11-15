package com.ipvio.stream

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure part of [CloudSync] (no Android): which stores travel with a person, how a store is written as JSON, the
 * fingerprint of the local data and how two versions are merged when both TVs changed something.
 *
 * Store format (same as the backup): { storeName: { key: [type, value] } }, types s/b/i/l/f/t.
 */
object SyncLogic {
    /** Per-profile stores that follow a person from TV to TV (names without the profile suffix). */
    val STORES = listOf(
        "ipvio_watch",        // Continue watching (resume points)
        "ipvio_eprog",        // progress and "watched" per episode / movie
        "ipvio_hist",         // Recently watched
        "ipvio_lib",          // My List and favorites
        "fof_catalog_items",  // addon titles those lists refer to
        "ipvio_catfavs",      // favorite categories
        "ipvio_chlists",      // own channel lists
        "fof_taste",          // taste setup (sports, liked titles, genres)
        "ipvio_sportfavs",    // favorite teams
        "ipvio_sportleagues", // leagues on/off
        AVATAR                // the profile's avatar (not a file: written through ProfileAvatars)
    )

    const val AVATAR = "fof_avatar"

    /** A saved version that is bigger than this is not applied (a real one is far smaller). */
    const val MAX_CHARS = 2_000_000

    fun encode(all: Map<String, *>): JSONObject {
        val o = JSONObject()
        for ((k, v) in all) when (v) {
            is String -> o.put(k, JSONArray().put("s").put(v))
            is Boolean -> o.put(k, JSONArray().put("b").put(v))
            is Int -> o.put(k, JSONArray().put("i").put(v))
            is Long -> o.put(k, JSONArray().put("l").put(v))
            is Float -> o.put(k, JSONArray().put("f").put(v.toDouble()))
            is Set<*> -> o.put(k, JSONArray().put("t").put(JSONArray(v.map { it.toString() })))
        }
        return o
    }

    /** Typed value back (String, Boolean, Int, Long, Float or Set<String>); null for anything unknown. */
    fun decodeValue(a: JSONArray?): Any? {
        if (a == null || a.length() < 2) return null
        return when (a.optString(0)) {
            "s" -> a.optString(1)
            "b" -> a.optBoolean(1)
            "i" -> a.optInt(1)
            "l" -> a.optLong(1)
            "f" -> a.optDouble(1).toFloat()
            "t" -> a.optJSONArray(1)?.let { l -> (0 until l.length()).map { l.optString(it) }.toSet() }
            else -> null
        }
    }

    /** Fingerprint of the stores, independent of key order. */
    fun fingerprint(files: JSONObject): String {
        val sb = StringBuilder()
        for (n in STORES) {
            val o = files.optJSONObject(n) ?: continue
            if (o.length() == 0) continue
            sb.append(n).append('{')
            o.keys().asSequence().sorted().forEach { k -> sb.append(k).append('=').append(o.opt(k).toString()).append(';') }
            sb.append('}')
        }
        val d = java.security.MessageDigest.getInstance("SHA-256").digest(sb.toString().toByteArray())
        return d.joinToString("") { "%02x".format(it) }.take(24)
    }

    enum class Step { NOTHING, UPLOAD, APPLY, MERGE }

    fun decide(localChanged: Boolean, cloudChanged: Boolean, cloudExists: Boolean): Step = when {
        !cloudExists -> if (localChanged) Step.UPLOAD else Step.NOTHING
        localChanged && cloudChanged -> Step.MERGE
        localChanged -> Step.UPLOAD
        cloudChanged -> Step.APPLY
        else -> Step.NOTHING
    }

    // ---------------------------------------------------------------- Merge (both TVs changed something)

    /** Merges two versions: per title the newer state wins, lists are joined. [local] wins where nothing is newer. */
    fun merge(local: JSONObject, cloud: JSONObject): JSONObject {
        val out = JSONObject()
        for (n in STORES) {
            val l = local.optJSONObject(n) ?: JSONObject()
            val c = cloud.optJSONObject(n) ?: JSONObject()
            if (l.length() == 0 && c.length() == 0) continue
            val m = JSONObject()
            // Keys only in one version are kept; keys in both are merged per store
            for (k in c.keys()) m.put(k, c.get(k))
            for (k in l.keys()) m.put(k, l.get(k))
            when (n) {
                "ipvio_watch" -> both(l, c, "e") { a, b -> s(mergeResume(a, b)) }?.let { m.put("e", it) }
                "ipvio_eprog" -> for (k in l.keys()) if (c.has(k)) m.put(k, newerProgress(l.optJSONArray(k), c.optJSONArray(k)))
                "ipvio_hist" -> both(l, c, "ids") { a, b -> s(joinLines(a, b, 80)) }?.let { m.put("ids", it) }
                "ipvio_lib" -> for (k in listOf("favs", "watch")) both(l, c, k) { a, b -> s(joinLines(a, b, 2000)) }?.let { m.put(k, it) }
                "ipvio_catfavs" -> both(l, c, "c") { a, b -> s(joinLines(a, b, 500)) }?.let { m.put("c", it) }
                "fof_catalog_items" -> both(l, c, "a") { a, b -> s(joinById(a, b, "i", 800)) }?.let { m.put("a", it) }
                // ipvio_chlists: a channel list is edited as a whole, this TV's version stays
            }
            out.put(n, m)
        }
        return out
    }

    private fun s(v: String) = JSONArray().put("s").put(v)

    private inline fun both(l: JSONObject, c: JSONObject, key: String, f: (String, String) -> JSONArray): JSONArray? {
        val a = l.optJSONArray(key)?.takeIf { it.optString(0) == "s" }?.optString(1) ?: return null
        val b = c.optJSONArray(key)?.takeIf { it.optString(0) == "s" }?.optString(1) ?: return null
        return f(a, b)
    }

    /** Resume points: one per title, the newer one ([ts]) wins; newest first, at most 30. */
    fun mergeResume(local: String, cloud: String): String {
        val best = LinkedHashMap<String, JSONObject>()
        for (src in listOf(local, cloud)) {
            val a = runCatching { JSONArray(src) }.getOrNull() ?: continue
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val id = o.optString("i")
                if (id.isBlank()) continue
                val old = best[id]
                if (old == null || o.optLong("ts") > old.optLong("ts")) best[id] = o
            }
        }
        return JSONArray(best.values.sortedByDescending { it.optLong("ts") }.take(30)).toString()
    }

    /** Episode progress of a series: stored as "time|progress"; the newer one wins. */
    fun newerProgress(a: JSONArray?, b: JSONArray?): JSONArray? {
        fun t(x: JSONArray?) = x?.optString(1)?.substringBefore('|')?.toLongOrNull() ?: -1L
        return if (t(b) > t(a)) b else a
    }

    /** Newline lists: this TV's order first, then the other's extra entries. */
    fun joinLines(local: String, cloud: String, max: Int): String =
        (local.split('\n') + cloud.split('\n')).filter { it.isNotBlank() }.distinct().take(max).joinToString("\n")

    /** JSON arrays of objects joined by an id field (this TV's entry wins). */
    fun joinById(local: String, cloud: String, idKey: String, max: Int): String {
        val seen = LinkedHashMap<String, JSONObject>()
        for (src in listOf(local, cloud)) {
            val a = runCatching { JSONArray(src) }.getOrNull() ?: continue
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val id = o.optString(idKey)
                if (id.isBlank()) continue
                if (!seen.containsKey(id)) seen[id] = o
            }
        }
        return JSONArray(seen.values.take(max)).toString()
    }
}
