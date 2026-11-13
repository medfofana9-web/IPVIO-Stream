package com.ipvio.stream

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Fof TV: catalog titles (movies and series from the Stremio addons) are not in the provider's playlist. This store keeps
 * the ones you opened, played or saved, so Continue watching, My List, History and Favorites can show them (per profile).
 */
object CatalogItems {
    private val map = mutableStateMapOf<String, Item>()
    private var loadedFor: String? = null

    private fun sp(c: Context) = Profiles.sp(c, "fof_catalog_items")

    fun init(c: Context) {
        val key = Profiles.active.toString()
        if (loadedFor == key) return
        loadedFor = key
        map.clear()
        runCatching {
            val a = JSONArray(sp(c).getString("a", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val it = Item(o.getString("i"), if (o.optBoolean("s")) Kind.SERIES else Kind.MOVIE, o.getString("t"),
                    if (o.has("y")) o.getInt("y") else null, "", o.optString("p").ifBlank { null }, "Stremio")
                map[it.id] = it
            }
        }
    }

    fun get(id: String): Item? = if (id.startsWith("stremio:")) map[id] else null

    /** Remembers a catalog title (keeps the newest 400). */
    fun put(c: Context, i: Item) {
        if (!i.id.startsWith("stremio:") || i.kind == Kind.LIVE) return
        init(c)
        if (map[i.id]?.let { it.title == i.title && it.logo == i.logo } == true) return
        map[i.id] = i
        val a = JSONArray()
        map.values.toList().takeLast(400).forEach { x ->
            val o = JSONObject().put("i", x.id).put("t", x.title).put("s", x.kind == Kind.SERIES)
            x.year?.let { o.put("y", it) }; x.logo?.let { o.put("p", it) }
            a.put(o)
        }
        sp(c).edit().putString("a", a.toString()).apply()
    }
}

/**
 * Extra home rows from TMDB (needs the TMDB key): "Because you watched …", "New on Netflix / Max / Disney+ / Prime Video"
 * and collections (sagas). TMDB titles are turned into catalog titles through their IMDb id, so they open and play with the
 * Stremio addons like every other catalog title. Lists and IMDb ids are kept on disk (lists 12 hours, ids for good).
 */
object TmdbExtra {
    private const val API = "https://api.themoviedb.org/3/"
    private const val IMG = "https://image.tmdb.org/t/p/w342"
    private const val FRESH = 12 * 3600_000L
    private val gate = Semaphore(6)
    private val imdb = ConcurrentHashMap<String, String>()
    private val lists = ConcurrentHashMap<String, Pair<Long, List<Item>>>()
    @Volatile private var file: File? = null
    @Volatile private var loaded = false

    data class Service(val id: Int, val name: String)
    val SERVICES = listOf(Service(8, "Netflix"), Service(1899, "Max"), Service(337, "Disney+"), Service(9, "Prime Video"),
        Service(350, "Apple TV+"), Service(15, "Hulu"))

    data class Coll(val id: Int, val name: String)
    val COLLECTIONS = listOf(Coll(86311, "The Avengers"), Coll(10, "Star Wars"), Coll(1241, "Harry Potter"), Coll(9485, "Fast & Furious"),
        Coll(404609, "John Wick"), Coll(87359, "Mission: Impossible"), Coll(119, "The Lord of the Rings"), Coll(328, "Jurassic Park"),
        Coll(263, "The Dark Knight"), Coll(531241, "Spider-Man (MCU)"), Coll(10194, "Toy Story"), Coll(645, "James Bond"))

    fun enabled() = Config.TMDB_KEY.isNotBlank()

    fun init(c: Context) { if (file == null) file = File(c.applicationContext.cacheDir, "tmdb_extra.json") }

    @Synchronized private fun load() {
        if (loaded) return
        loaded = true
        val f = file ?: return
        runCatching {
            if (!f.exists()) return
            val o = JSONObject(f.readText())
            o.optJSONObject("ids")?.let { m -> for (k in m.keys()) imdb[k] = m.getString(k) }
            o.optJSONObject("lists")?.let { m ->
                for (k in m.keys()) {
                    val e = m.getJSONObject(k); val a = e.getJSONArray("i")
                    lists[k] = e.getLong("t") to (0 until a.length()).map { i ->
                        val x = a.getJSONObject(i)
                        Item(x.getString("i"), if (x.optBoolean("s")) Kind.SERIES else Kind.MOVIE, x.getString("t"),
                            if (x.has("y")) x.getInt("y") else null, "", x.optString("p").ifBlank { null }, "Stremio")
                    }
                }
            }
        }
    }

    @Synchronized private fun save() {
        val f = file ?: return
        runCatching {
            val ids = JSONObject(); imdb.forEach { (k, v) -> ids.put(k, v) }
            val ls = JSONObject()
            lists.forEach { (k, v) ->
                val a = JSONArray()
                v.second.forEach { x ->
                    val o = JSONObject().put("i", x.id).put("t", x.title).put("s", x.kind == Kind.SERIES)
                    x.year?.let { o.put("y", it) }; x.logo?.let { o.put("p", it) }; a.put(o)
                }
                ls.put(k, JSONObject().put("t", v.first).put("i", a))
            }
            f.writeText(JSONObject().put("ids", ids).put("lists", ls).toString())
        }
    }

    private fun http(url: String): JSONObject? = runCatching {
        Net.base.newCall(Request.Builder().url(url).build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    }.getOrNull()?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun url(path: String): String = API + path + (if ('?' in path) "&" else "?") + "api_key=" + Config.TMDB_KEY + "&language=en-US"

    /** Saved titles of a list, without waiting (empty when not loaded yet). */
    fun cached(key: String): List<Item> = lists[key]?.second.orEmpty()

    /** TMDB result objects → catalog titles (IMDb ids looked up once, in parallel). */
    private suspend fun toItems(results: List<JSONObject>, forceTv: Boolean?): List<Item> = coroutineScope {
        results.take(20).map { o ->
            async(Dispatchers.IO) {
                val tv = forceTv ?: (o.optString("media_type") == "tv" || (o.has("name") && !o.has("title")))
                val id = o.optInt("id"); if (id <= 0) return@async null
                val k = (if (tv) "tv:" else "movie:") + id
                val tt = imdb[k] ?: gate.withPermit {
                    http(url((if (tv) "tv/" else "movie/") + id + "/external_ids"))?.optString("imdb_id")
                }?.takeIf { StremioLogic.isImdb(it) }?.also { imdb[k] = it } ?: return@async null
                val title = o.optString(if (tv) "name" else "title").ifBlank { return@async null }
                val date = o.optString(if (tv) "first_air_date" else "release_date")
                Item("stremio:$tt", if (tv) Kind.SERIES else Kind.MOVIE, title, date.take(4).toIntOrNull(), "",
                    o.optString("poster_path").takeIf { it.startsWith("/") }?.let { IMG + it }, "Stremio")
            }
        }.awaitAll().filterNotNull().distinctBy { it.id }
    }

    private suspend fun list(key: String, build: suspend () -> List<Item>): List<Item> {
        withContext(Dispatchers.IO) { load() }
        lists[key]?.let { if (System.currentTimeMillis() - it.first < FRESH) return it.second }
        if (!enabled()) return lists[key]?.second.orEmpty()
        val got = runCatching { build() }.getOrDefault(emptyList())
        if (got.isEmpty()) return lists[key]?.second.orEmpty()
        lists[key] = System.currentTimeMillis() to got
        withContext(Dispatchers.IO) { save() }
        return got
    }

    private fun results(o: JSONObject?): List<JSONObject> {
        val a = o?.optJSONArray("results") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }

    /** "Because you watched [seed]": TMDB recommendations for the title (found by its IMDb id). */
    suspend fun because(seed: Item): List<Item> {
        val tt = seed.id.removePrefix("stremio:").takeIf { StremioLogic.isImdb(it) } ?: return emptyList()
        val tv = seed.kind == Kind.SERIES
        return list("because:$tt") {
            withContext(Dispatchers.IO) {
                val found = http(url("find/$tt?external_source=imdb_id"))
                val r = found?.optJSONArray(if (tv) "tv_results" else "movie_results")?.optJSONObject(0) ?: return@withContext emptyList()
                val tmdb = r.optInt("id")
                toItems(results(http(url((if (tv) "tv/" else "movie/") + tmdb + "/recommendations"))), tv).filter { it.id != seed.id }
            }
        }
    }

    /** Newest titles on a streaming service in the US (movies and series mixed). */
    suspend fun newOn(s: Service): List<Item> = list("new:${s.id}") {
        withContext(Dispatchers.IO) {
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
            val m = async { toItems(results(http(url("discover/movie?watch_region=US&with_watch_providers=${s.id}&sort_by=primary_release_date.desc&primary_release_date.lte=$today&vote_count.gte=25"))), false) }
            val t = async { toItems(results(http(url("discover/tv?watch_region=US&with_watch_providers=${s.id}&sort_by=first_air_date.desc&first_air_date.lte=$today&vote_count.gte=15"))), true) }
            val a = m.await(); val b = t.await()
            (0 until maxOf(a.size, b.size)).flatMap { listOfNotNull(a.getOrNull(it), b.getOrNull(it)) }
        }
    }

    /** Movies of a collection, in release order. */
    suspend fun collection(c: Coll): List<Item> = list("coll:${c.id}") {
        withContext(Dispatchers.IO) {
            val o = http(url("collection/${c.id}"))
            val parts = o?.optJSONArray("parts") ?: return@withContext emptyList()
            val l = (0 until parts.length()).mapNotNull { parts.optJSONObject(it) }
                .filter { it.optString("release_date").isNotBlank() }.sortedBy { it.optString("release_date") }
            toItems(l, false)
        }
    }

    private val collArt = ConcurrentHashMap<Int, String>()
    /** Wide picture of a collection for its card. */
    suspend fun collectionArt(c: Coll): String? {
        collArt[c.id]?.let { return it }
        if (!enabled()) return null
        return withContext(Dispatchers.IO) {
            http(url("collection/${c.id}"))?.optString("backdrop_path")?.takeIf { it.startsWith("/") }
                ?.let { "https://image.tmdb.org/t/p/w780$it" }?.also { collArt[c.id] = it }
        }
    }
}
