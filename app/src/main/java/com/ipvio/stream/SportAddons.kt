package com.ipvio.stream

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
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
    private val catCache = ConcurrentHashMap<String, Pair<Long, JSONArray>>()

    /** Addon streams found for [g] (empty until [find] has run). */
    internal fun cacheFresh(nowMs: Long, atMs: Long, hasResults: Boolean = true): Boolean =
        nowMs - atMs in 0L until (if (hasResults) 120_000L else 3_000L)

    fun cached(g: SportData.Game): List<Item> =
        cache[g.key]?.takeIf { cacheFresh(System.currentTimeMillis(), it.first, it.second.isNotEmpty()) }?.second.orEmpty()

    /** Bumped when streams were found, so the Watch Live page shows them. */
    var version by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    /** Looks the game up in every addon's live catalogs; at most a few seconds. */
    suspend fun find(c: Context, g: SportData.Game): List<Item> = withContext(Dispatchers.IO) {
        cache[g.key]?.let { if (cacheFresh(System.currentTimeMillis(), it.first, it.second.isNotEmpty())) return@withContext it.second }
        if (g.solo) return@withContext emptyList()
        Stremio.ensure(c)
        val cats = Stremio.addons.flatMap { a -> a.cats.filter { it.type.lowercase() in LIVE_TYPES }.map { a to it } }
        val found = coroutineScope {
            cats.map { (a, cat) ->
                async {
                    val metas = runCatching { metasOf(cat) }.getOrNull() ?: return@async emptyList<Item>()
                    val hits = (0 until metas.length()).mapNotNull { metas.optJSONObject(it) }.filter { m ->
                        val text = m.optString("name") + " " + m.optString("description") + " " + m.optJSONArray("genres")?.join(" ").orEmpty()
                        SportData.matches(g, text)
                    }.take(4)
                    hits.flatMap { m -> runCatching { streamsOf(a, m.optString("type").ifBlank { cat.type }, m.optString("id"), m.optString("name")) }.getOrDefault(emptyList()) }
                }
            }.awaitAll().flatten()
        }.distinctBy { it.url }
        cache[g.key] = System.currentTimeMillis() to found
        // Even an empty discovery result is a completed attempt, but cache it only
        // briefly: a slow addon may become ready on the next try.
        version++
        found
    }

    private fun metasOf(cat: Stremio.Cat): JSONArray {
        val url = "${cat.base}/catalog/${cat.type}/${cat.id}.json"
        catCache[url]?.let { if (System.currentTimeMillis() - it.first < 60_000L) return it.second }
        val arr = JSONObject(Stremio.rawGet(url)).optJSONArray("metas") ?: JSONArray()
        catCache[url] = System.currentTimeMillis() to arr
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
