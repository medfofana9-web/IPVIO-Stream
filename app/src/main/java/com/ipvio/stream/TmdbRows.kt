package com.ipvio.stream

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * Netflix-style rows built from TMDB lists (trending, popular per genre), filled only with titles the playlist has.
 * A TMDB title matches a playlist title when the cleaned names are equal and the years are at most one apart
 * (so trending "Carrie" from this year never pulls in a 1952 film of the same name).
 */
object TmdbRows {
    class Row(val title: String, val items: List<Item>)
    private class T(val key: String, val alt: String, val year: Int?)

    private val MOVIE_GENRES = listOf(28 to "Action", 35 to "Comedy", 53 to "Thriller", 18 to "Drama", 80 to "Crime", 27 to "Horror",
        878 to "Sci-Fi", 10751 to "Family", 16 to "Animation", 10749 to "Romance", 99 to "Documentaries")
    private val TV_GENRES = listOf(18 to "Drama", 35 to "Comedy", 80 to "Crime", 10759 to "Action & Adventure", 9648 to "Mystery",
        10765 to "Sci-Fi & Fantasy", 16 to "Animation", 10764 to "Reality TV", 10762 to "Kids", 99 to "Documentaries")

    private val lists = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<T>>>()

    private val YEAR_TAIL = Regex("\\s*\\b(19|20)\\d{2}\\s*$")
    /** Comparable title: provider tags, brackets and a trailing year removed, letters and digits only. */
    fun key(title: String): String =
        StremioLogic.cleanTitle(title).replace(YEAR_TAIL, "").lowercase().filter { it.isLetterOrDigit() }

    private suspend fun list(path: String, pages: Int): List<T> {
        lists[path]?.let { if (System.currentTimeMillis() - it.first < 12 * 3600_000L) return it.second }
        if (!MetaRepo.enabled()) return emptyList()
        val out = ArrayList<T>()
        withContext(Dispatchers.IO) {
            for (page in 1..pages) runCatching {
                val sep = if ('?' in path) "&" else "?"
                val url = "https://api.themoviedb.org/3/$path${sep}api_key=${Config.TMDB_KEY}&language=en-US&page=$page"
                val body = Net.base.newCall(Request.Builder().url(url).build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
                    ?: return@runCatching
                val a = JSONObject(body).optJSONArray("results") ?: return@runCatching
                for (x in 0 until a.length()) {
                    val o = a.getJSONObject(x)
                    val name = o.optString("title").ifBlank { o.optString("name") }
                    val orig = o.optString("original_title").ifBlank { o.optString("original_name") }
                    val date = o.optString("release_date").ifBlank { o.optString("first_air_date") }
                    if (name.isBlank()) continue
                    out.add(T(key(name), key(orig), date.take(4).toIntOrNull()))
                }
            }
        }
        if (out.isNotEmpty()) lists[path] = System.currentTimeMillis() to out
        return out
    }

    // Playlist titles by comparable name, built once per list (big playlists: off the main thread)
    private var idxOf: List<Item>? = null
    private var idx: Map<String, List<Item>> = emptyMap()
    @Synchronized private fun index(items: List<Item>): Map<String, List<Item>> {
        if (idxOf === items) return idx
        val m = HashMap<String, MutableList<Item>>(items.size)
        for (i in items) { val k = key(i.title); if (k.length >= 2) m.getOrPut(k) { ArrayList(1) }.add(i) }
        idx = m; idxOf = items
        return m
    }

    private fun match(ts: List<T>, index: Map<String, List<Item>>, max: Int = 30): List<Item> {
        val out = LinkedHashMap<String, Item>()
        for (t in ts) {
            val cands = index[t.key] ?: index[t.alt] ?: continue
            val hit = cands.firstOrNull { c -> val y = c.year; t.year == null || y == null || kotlin.math.abs(y - t.year) <= 1 } ?: continue
            out.putIfAbsent(hit.mk, hit)
            if (out.size >= max) break
        }
        return out.values.toList()
    }

    /** Trending titles of the week that the playlist has (movies and series), most popular first. */
    suspend fun trending(movies: List<Item>, series: List<Item>): List<Item> = coroutineScope {
        val m = async { list("trending/movie/week", 3) }
        val s = async { list("trending/tv/week", 3) }
        withContext(Dispatchers.Default) { match(m.await(), index2(movies, series, Kind.MOVIE)) + match(s.await(), index2(movies, series, Kind.SERIES)) }
    }

    // Two indexes (movies, series) kept separately
    private var mIdx: Pair<List<Item>?, Map<String, List<Item>>> = null to emptyMap()
    private var sIdx: Pair<List<Item>?, Map<String, List<Item>>> = null to emptyMap()
    @Synchronized private fun index2(movies: List<Item>, series: List<Item>, kind: Kind): Map<String, List<Item>> {
        return if (kind == Kind.MOVIE) {
            if (mIdx.first !== movies) mIdx = movies to buildIndex(movies)
            mIdx.second
        } else {
            if (sIdx.first !== series) sIdx = series to buildIndex(series)
            sIdx.second
        }
    }
    private fun buildIndex(items: List<Item>): Map<String, List<Item>> {
        val m = HashMap<String, MutableList<Item>>(items.size)
        for (i in items) { val k = key(i.title); if (k.length >= 2) m.getOrPut(k) { ArrayList(1) }.add(i) }
        return m
    }

    /** Genre rows for movies or series: TMDB's most popular titles per genre that the playlist has (rows with at least 6). */
    suspend fun genres(movies: List<Item>, series: List<Item>, kind: Kind): List<Row> = coroutineScope {
        val tv = kind == Kind.SERIES
        val gs = if (tv) TV_GENRES else MOVIE_GENRES
        val type = if (tv) "tv" else "movie"
        val fetched = gs.map { (id, name) ->
            async { name to list("discover/$type?with_genres=$id&sort_by=popularity.desc&vote_count.gte=150", 3) }
        }.awaitAll()
        withContext(Dispatchers.Default) {
            val ix = index2(movies, series, kind)
            fetched.map { (name, ts) -> Row(name, match(ts, ix)) }.filter { it.items.size >= 6 }
        }
    }

    private val SERVICES = listOf(8 to "Netflix", 350 to "Apple TV+", 337 to "Disney+", 9 to "Prime Video", 1899 to "Max",
        15 to "Hulu", 531 to "Paramount+", 386 to "Peacock")

    /**
     * The full Netflix-style page for Movies or Series: Trending, Popular, New, Top rated, one row per streaming service
     * (what is on Netflix, Apple TV+ … in the US), then genres. Only rows with at least 6 titles the playlist has.
     */
    suspend fun catalog(movies: List<Item>, series: List<Item>, kind: Kind): List<Row> = coroutineScope {
        val tv = kind == Kind.SERIES
        val type = if (tv) "tv" else "movie"
        val heads = listOf(
            "Trending now" to "trending/$type/week",
            "Popular" to "$type/popular",
            (if (tv) "New episodes" else "New releases") to (if (tv) "tv/on_the_air" else "movie/now_playing"),
            "Top rated" to "$type/top_rated"
        )
        val services = SERVICES.map { (id, name) -> name to "discover/$type?with_watch_providers=$id&watch_region=US&sort_by=popularity.desc" }
        val genresL = (if (tv) TV_GENRES else MOVIE_GENRES).map { (id, name) -> name to "discover/$type?with_genres=$id&sort_by=popularity.desc&vote_count.gte=150" }
        val all = (heads + services + genresL).map { (name, path) -> async { name to list(path, 3) } }.awaitAll()
        withContext(Dispatchers.Default) {
            val ix = index2(movies, series, kind)
            val seen = HashSet<String>()
            all.map { (name, ts) -> Row(name, match(ts, ix)) }.filter { it.items.size >= 6 && seen.add(it.title) }
        }
    }

    /** Trending rows split per kind (for the Movies and Series pages). */
    suspend fun trendingOf(movies: List<Item>, series: List<Item>, kind: Kind): List<Item> {
        val ts = list(if (kind == Kind.SERIES) "trending/tv/week" else "trending/movie/week", 3)
        return withContext(Dispatchers.Default) { match(ts, index2(movies, series, kind)) }
    }
}
