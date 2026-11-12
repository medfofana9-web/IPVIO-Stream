package com.ipvio.stream

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stremio addons: the user adds addon addresses (Settings > Stremio addons); the detail page of a movie or series
 * then asks every addon for streams of that title and plays the ones with a direct address.
 * Torrent-only results (infoHash without url) are skipped: the app has no torrent engine.
 */
object Stremio {
    data class Addon(val base: String, val name: String)
    data class Source(val addon: String, val label: String, val url: String, val q: Int)

    private const val PREFS = "fof_stremio"
    private val http by lazy { Net.withTimeouts(10, 20) }

    var addons by mutableStateOf<List<Addon>>(emptyList())
        private set
    private var loaded = false

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun ensure(c: Context) {
        if (loaded) return
        loaded = true
        val raw = prefs(c).getString("addons", null) ?: return
        addons = runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { Addon(it.optString("base"), it.optString("name")) } }
                .filter { it.base.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    private fun store(c: Context, list: List<Addon>) {
        addons = list
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("base", it.base).put("name", it.name)) }
        prefs(c).edit().putString("addons", a.toString()).apply()
    }

    private fun get(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Linux; Android) FofTV").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            return r.body?.string().orEmpty()
        }
    }

    /** Adds an addon. Returns an error text for the screen, or null on success. */
    suspend fun add(c: Context, input: String): String? = withContext(Dispatchers.IO) {
        ensure(c)
        val base = StremioLogic.base(input) ?: return@withContext "That doesn't look like an addon link."
        if (addons.any { it.base.equals(base, ignoreCase = true) }) return@withContext "This addon is already added."
        val m = runCatching { JSONObject(get(StremioLogic.manifestUrl(base))) }.getOrElse {
            return@withContext "Could not open the addon (${it.message ?: "no answer"})."
        }
        if (!hasStreams(m)) return@withContext "This addon has no streams (it may only add catalogs or subtitles)."
        val name = m.optString("name").ifBlank { base.substringAfter("://").substringBefore('/') }
        withContext(Dispatchers.Main) { store(c, addons + Addon(base, name)) }
        null
    }

    fun remove(c: Context, base: String) = store(c, addons.filterNot { it.base == base })

    /** Does the manifest offer the "stream" resource for IMDb ids? */
    private fun hasStreams(m: JSONObject): Boolean {
        val res = m.optJSONArray("resources") ?: return false
        val globalPrefixes = m.optJSONArray("idPrefixes")
        for (i in 0 until res.length()) {
            val s = res.opt(i)
            if (s is String && s == "stream") return prefixOk(globalPrefixes)
            if (s is JSONObject && s.optString("name") == "stream") return prefixOk(s.optJSONArray("idPrefixes") ?: globalPrefixes)
        }
        return false
    }

    private fun prefixOk(p: JSONArray?): Boolean {
        if (p == null || p.length() == 0) return true
        return (0 until p.length()).any { "tt".startsWith(p.optString(it)) || p.optString(it).startsWith("tt") }
    }

    /**
     * IMDb id of an item: from the TMDB/OMDb info if there is one, otherwise a search in Stremio's free catalog
     * (no key needed). Results are kept for the session.
     */
    private val idCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    suspend fun imdbFor(item: Item, known: String?): String? {
        if (StremioLogic.isImdb(known)) return known
        idCache[item.mk]?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val q = StremioLogic.cleanTitle(item.title).ifBlank { item.title }
                val metas = JSONObject(get(StremioLogic.searchUrl(item.kind, q))).optJSONArray("metas") ?: return@runCatching null
                var best: String? = null
                for (i in 0 until metas.length()) {
                    val o = metas.optJSONObject(i) ?: continue
                    val id = o.optString("imdb_id").ifBlank { o.optString("id") }
                    if (!StremioLogic.isImdb(id)) continue
                    if (best == null) best = id
                    val y = StremioLogic.year(o.optString("releaseInfo"))
                    if (item.year != null && y == item.year) { best = id; break }
                }
                best
            }.getOrNull()?.also { idCache[item.mk] = it }
        }
    }

    /** Streams from all addons at once; addons that fail or time out are left out. Best quality first. */
    suspend fun streams(kind: Kind, imdb: String, season: Int, ep: Int): List<Source> = coroutineScope {
        addons.map { a ->
            async(Dispatchers.IO) {
                runCatching { parse(a.name, get(StremioLogic.streamUrl(a.base, kind, imdb, season, ep))) }.getOrDefault(emptyList())
            }
        }.awaitAll().flatten().distinctBy { it.url }.sortedByDescending { it.q }
    }

    private fun parse(addon: String, body: String): List<Source> {
        val arr = JSONObject(body).optJSONArray("streams") ?: return emptyList()
        val out = ArrayList<Source>()
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val url = s.optString("url")
            if (!url.startsWith("http")) continue
            // Streams that only work with extra request headers cannot be played by address alone
            val headers = s.optJSONObject("behaviorHints")?.optJSONObject("proxyHeaders")?.optJSONObject("request")
            if (headers != null && headers.length() > 0) continue
            val title = s.optString("title").ifBlank { s.optString("description") }
            val name = s.optString("name").ifBlank { addon }
            out.add(Source(addon, StremioLogic.label(name, title), url, StremioLogic.quality("$name $title")))
        }
        return out
    }
}
