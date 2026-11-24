package com.ipvio.stream

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
    /** A stream: a direct [url], or a torrent ([hash], optional [file] name) that Real-Debrid turns into a link. */
    /** [info]: everything the addon says about the stream (name, title, description, file name) – used to check the match. */
    data class Source(val addon: String, val label: String, val url: String, val q: Int, val hash: String? = null, val file: String? = null, val info: String = "") {
        /** Stable identity of a stream across lookups (links can be re-signed): addon + label. */
        val key: String get() = addon + "|" + label
    }

    private const val PREFS = "fof_stremio"
    private val http by lazy { Net.withTimeouts(10, 20) }

    var addons by mutableStateOf<List<Addon>>(emptyList())
        private set
    private var loaded = false

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun ensure(c: Context) {
        Debrid.init(c)
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
        // Addons belong to the account: every TV signed in to it gets the change
        AccountCloud.kick(c)
        addons = list
        val a = JSONArray()
        list.forEach { ad ->
            val cs = JSONArray()
            ad.cats.forEach { c -> cs.put(JSONObject().put("type", c.type).put("id", c.id).put("name", c.name)) }
            a.put(JSONObject().put("base", ad.base).put("name", ad.name).put("cats", cs).put("streams", ad.streams))
        }
        prefs(c).edit().putString("addons", a.toString()).apply()
    }

    /** Raw addon request (sports lookup in [SportAddons]). */
    internal fun rawGet(url: String): String = get(url)
    internal fun parseStreams(addon: String, body: String): List<Source> = parse(addon, body)

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
        return out.take(30)
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
                val hits = (0 until metas.length()).mapNotNull { i ->
                    val o = metas.optJSONObject(i) ?: return@mapNotNull null
                    val id = o.optString("imdb_id").ifBlank { o.optString("id") }
                    if (!StremioLogic.isImdb(id)) return@mapNotNull null
                    StremioLogic.ImdbHit(id, o.optString("name"),
                        StremioLogic.year(o.optString("releaseInfo")), o.optString("type"))
                }
                StremioLogic.chooseImdb(q, item.year, item.kind == Kind.SERIES, hits)
            }.getOrNull()?.also { idCache[item.mk] = it }
        }
    }

    /**
     * Uncached retry for a failed provider IMDb ID. The regular imdbFor()
     * intentionally uses a per-title cache, which cannot find another ID.
     * Never substitute a similarly named spinoff or different release year.
     */
    suspend fun alternateImdbFor(item: Item, failedId: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val title = StremioLogic.cleanTitle(item.title).ifBlank { item.title }
            val year = item.year ?: return@runCatching null
            val metas = JSONObject(get(StremioLogic.searchUrl(item.kind, title)))
                .optJSONArray("metas") ?: return@runCatching null
            val hits = (0 until metas.length()).mapNotNull { i ->
                val o = metas.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("imdb_id").ifBlank { o.optString("id") }
                if (!StremioLogic.isImdb(id)) return@mapNotNull null
                StremioLogic.ImdbHit(id, o.optString("name"),
                    StremioLogic.year(o.optString("releaseInfo")), o.optString("type"))
            }
            StremioLogic.chooseAlternateImdb(title, year, item.kind == Kind.SERIES, failedId, hits)
        }.getOrNull()
    }

    data class StreamLookup(val sources: List<Source>, val report: List<String>)
    private val streamCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, StreamLookup>>()

    /** Streams, kept 10 minutes: the detail page asks ahead, so Play has them at once. */
    /** [onPart]: called with everything found so far each time an addon answers (the list fills as answers arrive). */
    suspend fun streams(kind: Kind, imdb: String, season: Int, ep: Int): List<Source> =
        lookupStreams(kind, imdb, season, ep).sources

    suspend fun lookupStreams(kind: Kind, imdb: String, season: Int, ep: Int, refresh: Boolean = false, onPart: ((List<Source>) -> Unit)? = null): StreamLookup {
        // Include the installed addon set so adding/updating DragonStreams immediately
        // fetches its streams instead of replaying a stale ten-minute cache.
        val streamAddons = addons.filter { it.streams }
        val k = "$kind|$imdb|$season|$ep|" + streamAddons.joinToString(";") { it.base }
        if (refresh) {
            streamCache.remove(k)
        } else {
            streamCache[k]?.let { if (System.currentTimeMillis() - it.first < 10 * 60_000L) return it.second }
        }
        // A manual refresh must bypass a partially cached result from another addon
        // while DragonStreams was temporarily slow/offline.
        val requestKey = if (refresh) "$k|manual-refresh" else k
        // Already being looked up (the title page asked ahead): join that lookup instead of asking every addon again,
        // and show its answers as they arrive. Play then starts as soon as the first lookup has its sources.
        inflight[requestKey]?.let { (job, part) ->
            val joined: StreamLookup? = kotlinx.coroutines.coroutineScope {
                val feed = if (onPart == null) null else launch {
                    var n = -1
                    while (true) { val snap = synchronized(part) { part.toList() }; if (snap.size != n) { n = snap.size; if (snap.isNotEmpty()) onPart(snap) }; kotlinx.coroutines.delay(250) }
                }
                // The first lookup was cancelled (its page closed): look up afresh below
                val r = runCatching { job.await() }.getOrNull(); feed?.cancel(); r
            }
            if (joined != null) return joined
        }
        val part = java.util.Collections.synchronizedList(ArrayList<Source>())
        val job = kotlinx.coroutines.CompletableDeferred<StreamLookup>()
        inflight[requestKey] = job to part
        try {
            val lookup = streamsNow(streamAddons, kind, imdb, season, ep) { p ->
                synchronized(part) { part.clear(); part.addAll(p) }
                onPart?.invoke(p)
            }
            val missingDragon = streamAddons.any { addon ->
                addon.name.contains("dragon", ignoreCase = true) &&
                    lookup.sources.none { it.addon == addon.name }
            }
            if (lookup.sources.isNotEmpty() && !missingDragon) {
                streamCache[k] = System.currentTimeMillis() to lookup
            }
            job.complete(lookup)
            return lookup
        } catch (e: Throwable) { job.completeExceptionally(e); throw e } finally { inflight.remove(requestKey, job to part) }
    }
    private val inflight = java.util.concurrent.ConcurrentHashMap<String, Pair<kotlinx.coroutines.CompletableDeferred<StreamLookup>, MutableList<Source>>>()

    /** Requested title for the match check: series or movie, name, year, season and episode. */
    data class Want(val series: Boolean, val title: String, val year: Int?, val season: Int, val ep: Int) {
        val key: String get() = title.lowercase() + "|" + year + "|" + (if (series) "$season|$ep" else "movie")
    }

    fun match(s: Source, w: Want): Int = StremioLogic.matchCheck(s.info.ifBlank { s.label }, w.series, w.title, w.year, w.season, w.ep)

    /**
     * Order for automatic play and for the source list: the right content first (verified, then unverified, mismatches
     * last), sources reported as wrong at the very end, then the last source that played this title, then 1080p
     * direct links (smooth on a Fire TV), 720p, 4K, unknown; Real-Debrid after direct links.
     */
    fun rank(l: List<Source>, w: Want, contentId: String): List<Source> {
        val good = SourceMemory.lastGood(contentId)
        val qOrder = mapOf(2 to 0, 1 to 1, 3 to 2, 0 to 3)
        return l.sortedWith(compareBy<Source>(
            { if (SourceMemory.isWrong(contentId, it.key)) 1 else 0 },
            // Another title/episode always last, whatever the addon
            { if (match(it, w) == StremioLogic.MISMATCH) 1 else 0 },
            // DragonStreams, then Your IPTV, ahead of every other addon
            { StremioLogic.sourcePriority(it.addon) },
            { -match(it, w) },
            { if (it.key == good) 0 else 1 },
            { if (it.hash != null) 1 else 0 },
            { qOrder[it.q] ?: 4 }
        ))
    }

    /** Automatic choice: only a verified match that was not reported wrong; null = let the viewer choose. */
    /**
     * Source for the next episode, like the one that just worked: same addon and quality first, then same addon, then the
     * best other confirmed one. Only sources whose file name confirms this very episode.
     */
    fun pickLike(l: List<Source>, w: Want, contentId: String, likeAddon: String?, likeQ: Int?): Source? {
        val ok = rank(l, w, contentId).filter { match(it, w) == StremioLogic.VERIFIED && !SourceMemory.isWrong(contentId, it.key) && !it.label.startsWith(StremioLogic.LAST_RESORT) }
        return ok.firstOrNull { it.addon == likeAddon && it.q == likeQ } ?: ok.firstOrNull { it.addon == likeAddon } ?: ok.firstOrNull()
    }

    /** Quick one-click choice when no file name confirms the title yet: the best source that is not another title/episode. */
    fun bestPlayable(l: List<Source>, w: Want, contentId: String): Source? =
        rank(l, w, contentId).firstOrNull { match(it, w) != StremioLogic.MISMATCH && !SourceMemory.isWrong(contentId, it.key) && !it.label.startsWith(StremioLogic.LAST_RESORT) }

    fun bestVerified(l: List<Source>, w: Want, contentId: String): Source? =
        rank(l, w, contentId).firstOrNull { match(it, w) == StremioLogic.VERIFIED && !SourceMemory.isWrong(contentId, it.key) && !it.label.startsWith(StremioLogic.LAST_RESORT) }

    /** One-click choice: 1080p direct link first (smooth on a Fire TV), then 720p, 4K, unknown; Real-Debrid last. */
    fun best(l: List<Source>): Source {
        val direct = l.filter { it.hash == null }
        for (q in listOf(2, 1, 3, 0)) direct.firstOrNull { it.q == q }?.let { return it }
        return direct.firstOrNull() ?: l.first()
    }

    private suspend fun streamsNow(stream: List<Addon>, kind: Kind, imdb: String, season: Int, ep: Int, onPart: (List<Source>) -> Unit): StreamLookup = coroutineScope {
        val partial = java.util.Collections.synchronizedList(ArrayList<Source>())
        if (stream.isEmpty()) {
            return@coroutineScope StreamLookup(emptyList(), listOf("No stream addon is installed (Settings > Stremio addons)."))
        }
        val res = stream.map { a ->
            async(Dispatchers.IO) {
                val body = runCatching { get(StremioLogic.streamUrl(a.base, kind, imdb, season, ep)) }
                body.fold({ b ->
                    val raw = runCatching { JSONObject(b).getJSONArray("streams").length() }.getOrNull()
                    // DragonStreams and all other addons use the same protocol: do not
                    // silently discard MP4 or unnamed-CDN streams. Previously this filter
                    // hid results that Nuvio could display for Survivor and other titles.
                    // The manual picker must show every usable HTTP(S)/RD source the addon returns.
                    // Safety is enforced independently by content-match ranking and auto-play checks.
                    val l = runCatching { parse(a.name, b) }.getOrDefault(emptyList()).map { source ->
                        if (StremioLogic.dragonManualOnly(a.name, source.info, source.url)) {
                            source.copy(label = StremioLogic.LAST_RESORT + source.label)
                        } else source
                    }
                    if (l.isNotEmpty()) { partial.addAll(l); val snap = synchronized(partial) { partial.toList() }; withContext(Dispatchers.Main) { onPart(snap) } }
                    l to (a.name + ": " + when {
                        l.isNotEmpty() -> "${l.size} playable"
                        raw == null -> "invalid stream response"
                        raw == 0 -> "no links for this title"
                        else -> "$raw links, none playable here (torrent-only, no direct link)"
                    })
                }, { e ->
                    val why = e.message?.takeIf { it.startsWith("HTTP") } ?: if (e is java.net.SocketTimeoutException) "timed out" else "not responding"
                    emptyList<Source>() to (a.name + ": " + why)
                })
            }
        }.awaitAll()
        // Retain identical URLs from different addons: dropping a cross-addon
        // duplicate can make DragonStreams vanish from the source picker.
        val sources = res.flatMap { it.first }.distinctBy { it.addon.lowercase() + "|" + it.url.ifEmpty { "h:" + it.hash + "|" + it.file } }
            .sortedWith(compareByDescending<Source> { it.q }.thenBy { if (it.hash != null) 1 else 0 })
        StreamLookup(sources, res.map { it.second })
    }

    private fun parse(addon: String, body: String): List<Source> {
        val arr = JSONObject(body).optJSONArray("streams") ?: return emptyList()
        val out = ArrayList<Source>()
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val url = s.optString("url")
            val hash = s.optString("infoHash").takeIf { it.length >= 32 }
            // Torrent result: playable through Real-Debrid when a token is saved, otherwise skipped
            if (!url.startsWith("http") && (hash == null || !Debrid.on)) continue
            // Streams that need extra request headers (Referer, Origin …): the player sends them for this link
            s.optJSONObject("behaviorHints")?.optJSONObject("proxyHeaders")?.optJSONObject("request")?.let { h ->
                val m = LinkedHashMap<String, String>()
                for (n in h.keys()) { val v = h.optString(n); if (StreamHeaders.allowed(n) && v.isNotBlank()) m[n] = v }
                if (url.startsWith("http")) StreamHeaders.remember(url, m)
            }
            val title = s.optString("title").ifBlank { s.optString("description") }
            val name = s.optString("name").ifBlank { addon }
            val fname = s.optJSONObject("behaviorHints")?.optString("filename")?.takeIf { it.isNotBlank() }
            val info = listOf(name, s.optString("title"), s.optString("description"), fname.orEmpty()).filter { it.isNotBlank() }.joinToString("\n")
            if (url.startsWith("http")) out.add(Source(addon, StremioLogic.label(name, title), url, StremioLogic.quality("$name $title"), info = info))
            else out.add(Source(addon, "RD · " + StremioLogic.label(name, title), "", StremioLogic.quality("$name $title"), hash, fname, info))
        }
        return out
    }
}
