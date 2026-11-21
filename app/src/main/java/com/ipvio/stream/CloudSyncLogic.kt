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
        "ipvio_tracks",       // audio and subtitle language chosen per title
        "fof_intro_marks_v1", // exact episode intro/recap markers
        AVATAR,               // the profile's avatar (not a file: written through ProfileAvatars)
        NAME,                 // the profile's name (shown on every TV)
        KIDS                  // kids profile: adult content hidden (the PIN itself stays on the server)
    )

    const val KIDS = "fof_kids"

    const val AVATAR = "fof_avatar"
    const val NAME = "fof_pname"

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
                "ipvio_watch" -> {
                    val edits = mergeEdits(storedString(l, "edits"), storedString(c, "edits"))
                    if (edits.length() > 0) m.put("edits", s(edits.toString()))
                    if (l.has("e") || c.has("e")) m.put("e", s(mergeResumeWithEdits(
                        storedString(l, "e").orEmpty(), storedString(c, "e").orEmpty(), edits
                    )))
                }
                "ipvio_eprog" -> for (k in l.keys()) if (c.has(k)) m.put(k, newerProgress(l.optJSONArray(k), c.optJSONArray(k)))
                "ipvio_hist" -> both(l, c, "ids") { a, b -> s(joinLines(a, b, 80)) }?.let { m.put("ids", it) }
                "ipvio_lib" -> for (k in listOf("favs", "watch")) {
                    val editsKey = "${k}_edits"
                    val edits = mergeEdits(storedString(l, editsKey), storedString(c, editsKey))
                    if (edits.length() > 0) m.put(editsKey, s(edits.toString()))
                    if (l.has(k) || c.has(k)) m.put(k, s(mergeMarkedList(
                        storedString(l, k).orEmpty(), storedString(c, k).orEmpty(), edits, 2000
                    )))
                }
                "ipvio_catfavs" -> both(l, c, "c") { a, b -> s(joinLines(a, b, 500)) }?.let { m.put("c", it) }
                "fof_catalog_items" -> both(l, c, "a") { a, b -> s(joinById(a, b, "i", 800)) }?.let { m.put("a", it) }
                // ipvio_chlists: a channel list is edited as a whole, this TV's version stays
            }
            out.put(n, m)
        }
        return out
    }

    // Compatible with old profiles: the visible lists remain plain strings, while
    // per-title edit markers prevent a deliberate removal from being resurrected
    // when two TVs save different changes at the same time.
    private const val MAX_EDITS = 2000

    private fun storedString(store: JSONObject, key: String): String? =
        store.optJSONArray(key)?.takeIf { it.optString(0) == "s" }?.optString(1)

    private fun parseEdits(raw: String?): JSONObject =
        runCatching { JSONObject(raw.orEmpty()) }.getOrDefault(JSONObject())

    private fun capEdits(edits: JSONObject): JSONObject {
        val keep = edits.keys().asSequence()
            .filter { edits.optJSONObject(it) != null }
            .sortedWith(compareByDescending<String> { edits.optJSONObject(it)?.optLong("t") ?: 0L }.thenBy { it })
            .take(MAX_EDITS).toList().sorted()
        return JSONObject().also { out -> keep.forEach { key -> out.put(key, edits.getJSONObject(key)) } }
    }

    /** Record whether a title was added or removed; the format survives legacy clients. */
    fun recordEdit(raw: String?, id: String, present: Boolean, at: Long): String {
        val edits = parseEdits(raw)
        val previous = edits.optJSONObject(id)?.optLong("t") ?: 0L
        edits.put(id, JSONObject().put("t", maxOf(at, previous + 1L)).put("p", present))
        return capEdits(edits).toString()
    }

    /** Resolve per-title concurrent edits; on an exact timestamp tie, removal wins. */
    fun mergeEdits(local: String?, cloud: String?): JSONObject {
        val l = parseEdits(local)
        val c = parseEdits(cloud)
        val keys = (l.keys().asSequence().toList() + c.keys().asSequence().toList()).distinct().sorted()
        val out = JSONObject()
        for (key in keys) {
            val a = l.optJSONObject(key)
            val b = c.optJSONObject(key)
            val winner = when {
                a == null -> b
                b == null -> a
                a.optLong("t") > b.optLong("t") -> a
                b.optLong("t") > a.optLong("t") -> b
                !a.optBoolean("p", true) -> a
                else -> b
            }
            if (winner != null) out.put(key, winner)
        }
        return capEdits(out)
    }

    /** Keep legacy list order while honoring explicit removals and newer additions. */
    fun mergeMarkedList(local: String, cloud: String, edits: JSONObject, max: Int): String {
        val merged = joinLines(local, cloud, max).split('\n').filter { it.isNotBlank() }.toMutableList()
        val removed = edits.keys().asSequence().filter { edits.optJSONObject(it)?.optBoolean("p", true) == false }.toSet()
        merged.removeAll(removed)
        val added = edits.keys().asSequence()
            .filter { edits.optJSONObject(it)?.optBoolean("p", false) == true && it !in merged }
            .sortedBy { edits.optJSONObject(it)?.optLong("t") ?: 0L }.toList()
        for (id in added) merged.add(0, id)
        return merged.distinct().take(max).joinToString("\n")
    }

    /** Remove expired resume entries without losing newer playback started on another TV. */
    fun mergeResumeWithEdits(local: String, cloud: String, edits: JSONObject): String {
        val a = mergeResume(local, cloud)
        val all = JSONArray(a)
        val kept = JSONArray()
        for (i in 0 until all.length()) {
            val entry = all.optJSONObject(i) ?: continue
            val edit = edits.optJSONObject(entry.optString("i"))
            if (edit != null && !edit.optBoolean("p", true) && edit.optLong("t") >= entry.optLong("ts")) continue
            kept.put(entry)
        }
        return kept.toString()
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
