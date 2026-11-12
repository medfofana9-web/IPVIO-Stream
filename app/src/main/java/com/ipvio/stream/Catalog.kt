package com.ipvio.stream

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Fof TV: the one catalog of the app (Home, Movies, Series, the big picture and the top buttons) comes from Cinemeta,
 * Stremio's free and current movie/series catalog, instead of the IPTV provider's categories.
 * Opening a title uses the provider's copy when the playlist has it ([resolve]); otherwise the Stremio addon streams.
 * Lists are kept on disk, so rows are there at once on the next start (refreshed after 6 hours).
 */
object Catalog {
    private const val CM = "https://v3-cinemeta.strem.io"
    private const val FRESH = 6 * 3600_000L

    /** A list of Cinemeta: [id] "top" (popular now), "year" (needs genre = a year) or "imdbRating" (featured). */
    data class Spec(val title: String, val type: String, val id: String, val genre: String? = null)

    val year: Int = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)

    private fun type(k: Kind) = if (k == Kind.SERIES) "series" else "movie"
    fun popular(k: Kind) = Spec(if (k == Kind.SERIES) "Popular series" else "Popular movies", type(k), "top")
    fun newest(k: Kind) = Spec(if (k == Kind.SERIES) "New series $year" else "New movies $year", type(k), "year", "$year")
    fun featured(k: Kind) = Spec(if (k == Kind.SERIES) "Top rated series" else "Top rated movies", type(k), "imdbRating")

    private val MOVIE_GENRES = listOf("Action", "Comedy", "Thriller", "Drama", "Crime", "Horror", "Sci-Fi", "Adventure",
        "Animation", "Family", "Romance", "Mystery", "Fantasy", "Documentary")
    private val SERIES_GENRES = listOf("Drama", "Comedy", "Crime", "Action", "Mystery", "Thriller", "Sci-Fi", "Animation",
        "Reality-TV", "Family", "Documentary")
    fun genres(k: Kind): List<Spec> =
        (if (k == Kind.SERIES) SERIES_GENRES else MOVIE_GENRES).map { Spec(it, type(k), "top", it) }

    /** Rows of the Movies or Series page. */
    fun page(k: Kind): List<Spec> = listOf(popular(k), newest(k), Spec(if (k == Kind.SERIES) "Last year's series" else "Last year's movies", type(k), "year", "${year - 1}")) + genres(k) + featured(k)

    /** Rows of the home screen (after Top 10). */
    fun home(): List<Spec> = listOf(popular(Kind.MOVIE), popular(Kind.SERIES), newest(Kind.MOVIE), newest(Kind.SERIES))

    private class Entry(val at: Long, val items: List<Item>)

    /** Picture, logo and text that Cinemeta sends with each title: the big picture and the cards need no TMDB lookup. */
    class Info(val bg: String?, val logo: String?, val desc: String?, val rating: String?, val genres: List<String>, val runtime: String?, val year: String?)
    private val infos = ConcurrentHashMap<String, Info>()
    private val metas = ConcurrentHashMap<String, Meta>()

    fun logo(id: String): String? = infos[id]?.logo

    /** Card and big-picture info of a catalog title, null for other titles. */
    fun meta(i: Item): Meta? {
        if (!i.id.startsWith("stremio:")) return null
        metas[i.id]?.let { return it }
        val f = infos[i.id] ?: return null
        val m = Meta(0, i.kind == Kind.SERIES, i.logo, f.bg, f.desc, f.rating?.toDoubleOrNull(), f.year, f.genres,
            minutes(f.runtime), imdbId = i.id.removePrefix("stremio:"), imdbRating = f.rating)
        metas[i.id] = m
        return m
    }

    /** "130 min" or "2h 10min" → 130. */
    private fun minutes(r: String?): Int? {
        if (r == null) return null
        val h = Regex("(\\d+)\\s*h").find(r)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val m = Regex("(\\d+)\\s*m").find(r)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return (h * 60 + m).takeIf { it > 0 }
    }

    private fun infoJson(f: Info): JSONObject = JSONObject().apply {
        f.bg?.let { put("bg", it) }; f.logo?.let { put("lg", it) }; f.desc?.let { put("ds", it) }; f.rating?.let { put("rt", it) }
        put("gn", JSONArray(f.genres)); f.runtime?.let { put("rn", it) }; f.year?.let { put("yr", it) }
    }

    private fun infoOf(o: JSONObject): Info? {
        val bg = o.optString("bg").ifBlank { o.optString("background") }.takeIf { it.startsWith("http") }
        val lg = o.optString("lg").ifBlank { o.optString("logo") }.takeIf { it.startsWith("http") }
        val ds = o.optString("ds").ifBlank { o.optString("description") }.ifBlank { null }
        val rt = o.optString("rt").ifBlank { o.optString("imdbRating") }.ifBlank { null }
        val ga = o.optJSONArray("gn") ?: o.optJSONArray("genres") ?: o.optJSONArray("genre")
        val gn = if (ga == null) emptyList() else (0 until ga.length()).map { ga.optString(it) }.filter { it.isNotBlank() }
        val rn = o.optString("rn").ifBlank { o.optString("runtime") }.ifBlank { null }
        val yr = o.optString("yr").ifBlank { o.optString("releaseInfo") }.ifBlank { null }
        if (bg == null && lg == null && ds == null) return null
        return Info(bg, lg, ds, rt, gn, rn, yr)
    }
    private val mem = ConcurrentHashMap<String, Entry>()
    @Volatile private var file: File? = null
    @Volatile private var diskRead = false

    fun init(c: Context) { if (file == null) file = File(c.applicationContext.cacheDir, "catalog_cm.json") }

    private fun url(s: Spec, skip: Int): String {
        val ex = ArrayList<String>()
        s.genre?.let { ex.add("genre=" + URLEncoder.encode(it, "UTF-8")) }
        if (skip > 0) ex.add("skip=$skip")
        return "$CM/catalog/${s.type}/${s.id}" + (if (ex.isEmpty()) "" else "/" + ex.joinToString("&")) + ".json"
    }

    @Synchronized private fun readDisk() {
        if (diskRead) return
        diskRead = true
        val f = file ?: return
        runCatching {
            if (!f.exists()) return
            val o = JSONObject(f.readText())
            for (k in o.keys()) {
                val e = o.getJSONObject(k)
                val a = e.getJSONArray("i")
                val l = (0 until a.length()).map { i ->
                    val x = a.getJSONObject(i)
                    x.optJSONObject("f")?.let { infoOf(it) }?.let { infos.putIfAbsent(x.getString("id"), it) }
                    Item(x.getString("id"), if (x.optBoolean("s")) Kind.SERIES else Kind.MOVIE, x.getString("n"),
                        if (x.has("y")) x.getInt("y") else null, "", x.optString("p").ifBlank { null }, "Stremio")
                }
                mem.putIfAbsent(k, Entry(e.getLong("t"), l))
            }
        }
    }

    @Synchronized private fun writeDisk() {
        val f = file ?: return
        runCatching {
            val o = JSONObject()
            mem.forEach { (k, e) ->
                val a = JSONArray()
                e.items.forEach { i ->
                    val x = JSONObject().put("id", i.id).put("n", i.title).put("s", i.kind == Kind.SERIES)
                    i.year?.let { x.put("y", it) }
                    i.logo?.let { x.put("p", it) }
                    infos[i.id]?.let { x.put("f", infoJson(it)) }
                    a.put(x)
                }
                o.put(k, JSONObject().put("t", e.at).put("i", a))
            }
            f.writeText(o.toString())
        }
    }

    private fun key(s: Spec, pages: Int) = url(s, 0) + "#" + pages

    /** Reads the saved lists in the background at app start, so the first screen has them without touching the disk. */
    fun preload() { kotlin.concurrent.thread(isDaemon = true, name = "catalog-preload") { readDisk() } }

    /** Already known titles of a list (memory only, never blocks), without waiting for the network. */
    fun cached(s: Spec, pages: Int = 1): List<Item> = mem[key(s, pages)]?.items.orEmpty()

    /** Titles of a list; [pages] pages of about 50 titles. Old titles are kept when the network fails. */
    suspend fun items(s: Spec, pages: Int = 1): List<Item> {
        withContext(Dispatchers.IO) { readDisk() }
        val k = key(s, pages)
        val have = mem[k]
        if (have != null && System.currentTimeMillis() - have.at < FRESH) return have.items
        val got = coroutineScope {
            (0 until pages).map { p -> async(Dispatchers.IO) { fetch(url(s, p * 50), s.type == "series") } }.awaitAll()
        }.flatten().distinctBy { it.id }
        if (got.isEmpty()) return have?.items.orEmpty()
        mem[k] = Entry(System.currentTimeMillis(), got)
        withContext(Dispatchers.IO) { writeDisk() }
        return got
    }

    private fun fetch(u: String, series: Boolean): List<Item> = runCatching {
        val body = Net.base.newCall(Request.Builder().url(u).build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
            ?: return emptyList()
        val metas = JSONObject(body).optJSONArray("metas") ?: return emptyList()
        (0 until metas.length()).mapNotNull { i ->
            val o = metas.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("imdb_id").ifBlank { o.optString("id") }
            if (!StremioLogic.isImdb(id)) return@mapNotNull null
            // Bigger poster than the default small one (sharper on a TV)
            val poster = o.optString("poster").takeIf { it.startsWith("http") }?.replace("/poster/small/", "/poster/medium/")
            infoOf(o)?.let { f -> infos["stremio:$id"] = f; this@Catalog.metas.remove("stremio:$id") }
            Item("stremio:$id", if (series) Kind.SERIES else Kind.MOVIE, o.optString("name"),
                StremioLogic.year(o.optString("releaseInfo").ifBlank { o.optString("year") }), "", poster, "Stremio")
        }
    }.getOrDefault(emptyList())

    /** Genre name → titles, for the "Categories" button. */
    suspend fun genreMap(k: Kind): Map<String, List<Item>> = coroutineScope {
        (listOf(newest(k), popular(k), featured(k)) + genres(k)).map { s -> async { s.title to items(s, 2) } }.awaitAll()
            .filter { it.second.isNotEmpty() }.toMap(LinkedHashMap())
    }

    private var ixOf: List<Item>? = null
    private var ix: Map<String, List<Item>> = emptyMap()
    @Synchronized private fun index(all: List<Item>): Map<String, List<Item>> {
        if (ixOf === all) return ix
        val m = HashMap<String, MutableList<Item>>()
        for (i in all) {
            if (i.kind == Kind.LIVE) continue
            val k = TmdbRows.key(i.title)
            if (k.length >= 2) m.getOrPut(i.kind.name + k) { ArrayList(1) }.add(i)
        }
        ix = m; ixOf = all
        return m
    }

    /** A Cinemeta title opens as the playlist's own copy when the provider has it (same name, year ±1). */
    suspend fun resolve(i: Item, all: List<Item>): Item {
        if (!i.id.startsWith("stremio:")) return i
        return withContext(Dispatchers.Default) {
            val c = index(all)[i.kind.name + TmdbRows.key(i.title)]
                ?.filter { c -> val y = c.year; i.year == null || y == null || kotlin.math.abs(y - i.year) <= 1 }
            // Several copies (languages, years of remakes, qualities): English first, then the exact year, then 1080p
            c?.minWithOrNull(compareBy<Item>(
                { when (it.lang) { "en" -> 0; LangTagLogic.UNKNOWN -> 1; else -> 2 } },
                { if (i.year != null && it.year == i.year) 0 else 1 },
                { when (it.quality) { "FHD" -> 0; "HD" -> 1; "4K" -> 2; else -> 3 } }
            ))
        } ?: i
    }
}
