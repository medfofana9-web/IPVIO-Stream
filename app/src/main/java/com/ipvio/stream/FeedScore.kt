package com.ipvio.stream

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * How well each live channel has actually played on this TV: starts, failures, start time, freezes, picture height.
 * Sports use it to put the best working feed of a game first (only among feeds that match the game equally well:
 * a channel that clearly shows the game always beats a better-looking guess).
 */
object FeedScore {
    class Stat(var ok: Int = 0, var fail: Int = 0, var startMs: Long = 0, var stalls: Int = 0, var height: Int = 0, var at: Long = 0)

    private val map = ConcurrentHashMap<String, Stat>()
    @Volatile private var loaded = false
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("fof_feed_score", Context.MODE_PRIVATE)

    private fun load(c: Context) {
        if (loaded) return
        loaded = true
        runCatching {
            val o = JSONObject(prefs(c).getString("m", "{}") ?: "{}")
            for (k in o.keys()) o.optJSONArray(k)?.let { a -> map[k] = Stat(a.optInt(0), a.optInt(1), a.optLong(2), a.optInt(3), a.optInt(4), a.optLong(5)) }
        }
    }

    private fun save(c: Context) {
        // Newest 600 channels only
        val keep = map.entries.sortedByDescending { it.value.at }.take(600)
        val o = JSONObject()
        keep.forEach { (k, s) -> o.put(k, org.json.JSONArray().put(s.ok).put(s.fail).put(s.startMs).put(s.stalls).put(s.height).put(s.at)) }
        prefs(c).edit().putString("m", o.toString()).apply()
    }

    private fun stat(c: Context, id: String): Stat { load(c); return map.getOrPut(id) { Stat() }.also { it.at = System.currentTimeMillis() } }

    fun started(c: Context, id: String, startMs: Long) {
        val s = stat(c, id)
        s.startMs = if (s.ok == 0) startMs else (s.startMs * 3 + startMs) / 4
        s.ok++; save(c)
    }
    fun failed(c: Context, id: String) { stat(c, id).fail++; save(c) }
    fun stalled(c: Context, id: String) { stat(c, id).stalls++; save(c) }
    fun height(c: Context, id: String, h: Int) { if (h > 0) { val s = stat(c, id); if (s.height != h) { s.height = h; save(c) } } }

    fun score(c: Context, id: String): Double { load(c); return map[id]?.let { score(it.ok, it.fail, it.startMs, it.stalls, it.height) } ?: score(0, 0, 0, 0, 0) }

    /**
     * Pure: higher is better. Reliability counts most (a feed that starts and keeps playing), then picture size,
     * then fewer freezes per start and a quick start. An unknown feed sits in the middle.
     */
    fun score(ok: Int, fail: Int, startMs: Long, stalls: Int, height: Int): Double {
        val rel = (ok + 1.0) / (ok + fail + 2.0)
        val q = when { height >= 2000 -> 3.0; height >= 1000 -> 2.0; height >= 700 -> 1.0; height > 0 -> 0.0; else -> 1.0 }
        val stallRate = minOf(stalls.toDouble() / (ok + 1), 3.0)
        val slow = if (ok > 0) minOf(startMs / 8000.0, 1.0) else 0.5
        return rel * 4.0 + q * 0.9 - stallRate * 0.8 - slow
    }
}
