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
    data class Addon(val base: String, val name: String, val cats: List<Cat> = emptyList(), val streams: Boolean = true)
    /** A catalog of an addon ("Popular movies"): read from base/catalog/{type}/{id}.json. */
    data class Cat(val base: String, val type: String, val id: String, val name: String, val addon: String)
    /** One title of a catalog. [imdb] is the IMDb id when the addon uses one. */
    data class Meta(val imdb: String, val name: String, val poster: String?, val year: Int?, val series: Boolean)

    /** Stremio's own free catalog: always shown as the first rows (popular movies and series). */
    private const val CINEMETA = "https://v3-cinemeta.strem.io"
    val builtIn = listOf(
        Cat(CINEMETA, "movie", "top", "Popular movies", "Cinemeta"),
        Cat(CINEMETA, "series", "top", "Popular series", "Cinemeta")
    )
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
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { o ->
                val b = o.optString("base"); val n = o.optString("name")
                val cs = o.optJSONArray("cats")
                val cats = if (cs == null) emptyList() else (0 until cs.length()).mapNotNull { j ->
                    cs.optJSONObject(j)?.let { c -> Cat(b, c.optString("type"), c.optString("id"), c.optString("name"), n) }
                }
                Addon(b, n, cats, o.optBoolean("streams", true))
            } }.filter { it.base.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    private fun store(c: Context, list: List<Addon>) {
        addons = list
        val a = JSONArray()
        list.forEach { ad ->
            val cs = JSONArray()
            ad.cats.forEach { c -> cs.put(JSONObject().put("type", c.type).put("id", c.id).put("name", c.name)) }
            a.put(JSONObject().put("base", ad.base).put("name", ad.name).put("cats", cs).put("streams", ad.streams))
        }
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
        val name = m.optString("name").ifBlank { base.substringAfter("://").substringBefore('/') }
        val streams = hasStreams(m)
        val cats = catalogs(base, name, m)
        if (!streams && cats.isEmpty()) return@withContext "This addon has no streams or catalogs this app can use (it may only add subtitles)."
        withContext(Dispatchers.Main) { store(c, addons + Addon(base, name, cats, streams)) }
        null
    }

    fun remove(c: Context, base: String) = store(c, addons.filterNot { it.base == base })

    /** Movie and series catalogs that need no search text or other required input. */
    private fun catalogs(base: String, addon: String, m: JSONObject): List<Cat> {
        val arr = m.optJSONArray("catalogs") ?: return emptyList()
        val out = ArrayList<Cat>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val type = o.optString("type")
            if (type != "movie" && type != "series") continue
            val extra = o.optJSONArray("extra")
            val required = extra != null && (0 until extra.length()).any { extra.optJSONObject(it)?.optBoolean("isRequired") == true }
            val extraReq = o.optJSONArray("extraRequired")
            if (required || (extraReq != null && extraReq.length() > 0)) continue
            val nm = o.optString("name").ifBlank { o.optString("id") }
            out.add(Cat(base, type, o.optString("id"), "$nm (${if (type == "movie") "movies" else "series"})", addon))
        }
        return out.take(8)
    }

    /** All catalog rows: Stremio's popular lists first, then the catalogs of added addons. */
    fun allCatalogs(): List<Cat> = builtIn + addons.flatMap { it.cats }

    private val catCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<Meta>>>()

    /** Titles of a catalog (first page), kept for 30 minutes. Only titles with an IMDb id (streams are found by it). */
    suspend fun catalog(cat: Cat): List<Meta> = withContext(Dispatchers.IO) {
        val url = "${cat.base}/catalog/${cat.type}/${cat.id}.json"
        catCache[url]?.let { if (System.currentTimeMillis() - it.first < 30 * 60_000L) return@withContext it.second }
        val list = runCatching {
            val metas = JSONObject(get(url)).optJSONArray("metas") ?: JSONArray()
            (0 until metas.length()).mapNotNull { i ->
                val o = metas.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("imdb_id").ifBlank { o.optString("id") }
                if (!StremioLogic.isImdb(id)) return@mapNotNull null
                Meta(id, o.optString("name"), o.optString("poster").takeIf { it.startsWith("http") },
                    StremioLogic.year(o.optString("releaseInfo").ifBlank { o.optString("year") }), cat.type == "series")
            }.take(40)
        }.getOrDefault(emptyList())
        if (list.isNotEmpty()) catCache[url] = System.currentTimeMillis() to list
        list
    }

    /** Search Stremio's catalog (movies and series) for [q]; no key needed. Best matches first, at most 20 per type. */
    suspend fun search(q: String): List<Meta> = coroutineScope {
        listOf(Kind.MOVIE, Kind.SERIES).map { k ->
            async(Dispatchers.IO) {
                runCatching {
                    val metas = JSONObject(get(StremioLogic.searchUrl(k, q))).optJSONArray("metas") ?: JSONArray()
                    (0 until metas.length()).mapNotNull { i ->
                        val o = metas.optJSONObject(i) ?: return@mapNotNull null
                        val id = o.optString("imdb_id").ifBlank { o.optString("id") }
                        if (!StremioLogic.isImdb(id)) null
                        else Meta(id, o.optString("name"), o.optString("poster").takeIf { it.startsWith("http") },
                            StremioLogic.year(o.optString("releaseInfo")), k == Kind.SERIES)
                    }.take(20)
                }.getOrDefault(emptyList())
            }
        }.awaitAll().flatten()
    }

    /** A catalog title as an app item (opens the stream list; plays like any movie). */
    fun itemOf(m: Meta): Item = Item("stremio:" + m.imdb, if (m.series) Kind.SERIES else Kind.MOVIE, m.name, m.year, "", m.poster, "Stremio")

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
        addons.filter { it.streams }.map { a ->
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
