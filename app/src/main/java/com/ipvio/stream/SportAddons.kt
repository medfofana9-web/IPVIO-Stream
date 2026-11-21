package com.ipvio.stream

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Live games from the Stremio addons (Pengu and other live/sports addons): their catalogs of live channels and events
 * are searched for the game (both team names), and the streams of the matching entries become channels for that game.
 * Results are kept per game for 2 minutes; [SportWatch.resolve] adds them to the game's channels.
 */
object SportAddons {
    /** Catalog types that hold live content (movies and series are never live games). */
    private val LIVE_TYPES = setOf("tv", "channel", "channels", "events", "event", "sports", "sport", "live", "other")

    private val cache = ConcurrentHashMap<String, Pair<Long, List<Item>>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = HashMap<String, Deferred<List<Item>>>()
    private val reports = ConcurrentHashMap<String, String>()
    fun report(g: SportData.Game): String? = reports[g.key]

    private val catCache = ConcurrentHashMap<String, Pair<Long, JSONArray>>()

    /** Addon streams found for [g] (empty until [find] has run). */
    internal fun cacheFresh(nowMs: Long, atMs: Long, hasResults: Boolean = true): Boolean =
        nowMs - atMs in 0L until (if (hasResults) 120_000L else 3_000L)

    fun cached(g: SportData.Game): List<Item> =
        cache[g.key]?.takeIf { cacheFresh(System.currentTimeMillis(), it.first, it.second.isNotEmpty()) }?.second.orEmpty()

    /** Bumped when streams were found, so the Watch Live page shows them. */
    var version by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    /** Share an in-flight lookup between prefetch, Play and the source picker. */
    suspend fun find(c: Context, g: SportData.Game, refresh: Boolean = false): List<Item> {
        val task = synchronized(pending) {
            pending[g.key]?.let { return@synchronized it }
            if (!refresh) cache[g.key]?.let {
                if (cacheFresh(System.currentTimeMillis(), it.first, it.second.isNotEmpty())) return it.second
            }
            scope.async { search(c.applicationContext, g, refresh) }.also { request ->
                pending[g.key] = request
                request.invokeOnCompletion {
                    synchronized(pending) { if (pending[g.key] === request) pending.remove(g.key) }
                }
            }
        }
        return task.await()
    }

    private suspend fun search(c: Context, g: SportData.Game, refresh: Boolean): List<Item> {
        Stremio.ensure(c)
        val addons = Stremio.addons.toList()
        val results = coroutineScope {
            addons.map { addon -> async {
                val catalogs = addon.cats.filter { it.type.lowercase() in LIVE_TYPES }
                if (catalogs.isEmpty()) return@async (addon.name + ": no live catalog") to emptyList<Item>()
                var incomplete = false
                val streams = catalogs.flatMap { catalog ->
                    try {
                        val metas = metasOf(catalog, refresh)
                        (0 until metas.length()).asSequence().mapNotNull { metas.optJSONObject(it) }
                            .filter { SportData.matches(g, it.optString("name")) }.take(4).flatMap { event ->
                                try {
                                    streamsOf(addon, event.optString("type").ifBlank { catalog.type }, event.optString("id"), event.optString("name")).asSequence()
                                } catch (e: CancellationException) { throw e }
                                catch (_: Exception) { incomplete = true; emptySequence() }
                            }.toList()
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { incomplete = true; emptyList() }
                }
                val outcome = when {
                    streams.isNotEmpty() -> "${streams.size} matched"
                    incomplete -> "lookup incomplete"
                    else -> "no exact event stream"
                }
                (addon.name + ": " + outcome) to streams
            } }.awaitAll()
        }
        val streams = results.flatMap { it.second }.distinctBy { it.url }
        if (cache.size > 150) { cache.clear(); reports.clear() }
        reports[g.key] = results.joinToString(" · ") { it.first }.ifBlank { "No add-ons configured" }
        cache[g.key] = System.currentTimeMillis() to streams
        withContext(Dispatchers.Main) { version++ }
        return streams
    }

    private fun metasOf(cat: Stremio.Cat, refresh: Boolean): JSONArray {
        val url = "${cat.base}/catalog/${cat.type}/${cat.id}.json"
        if (!refresh) catCache[url]?.let { if (it.second.length() > 0 && System.currentTimeMillis() - it.first < 60_000L) return it.second }
        val arr = JSONObject(Stremio.rawGet(url)).optJSONArray("metas") ?: JSONArray()
        if (arr.length() > 0) catCache[url] = System.currentTimeMillis() to arr
        return arr
    }

    private fun streamsOf(a: Stremio.Addon, type: String, id: String, name: String): List<Item> {
        if (id.isBlank()) return emptyList()
        val body = Stremio.rawGet("${a.base}/stream/$type/${java.net.URLEncoder.encode(id, "UTF-8").replace("+", "%20")}.json")
        return Stremio.parseStreams(a.name, body).filter { it.url.startsWith("http") }.mapIndexed { i, s ->
            Item("addonlive:" + a.name + ":" + id + ":" + i, Kind.LIVE, a.name + " · " + name + (if (s.label.isNotBlank()) " · " + s.label else ""),
                null, s.url, null, "Addon: " + a.name)
        }
    }
}
