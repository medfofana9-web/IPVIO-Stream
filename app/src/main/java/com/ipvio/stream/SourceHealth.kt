package com.ipvio.stream

import android.content.Context

/**
 * How sources did recently, per title/episode (content id, see [SourceMemory.contentId]) and source key
 * ([Stremio.Source.key]): a source that did not start in the player, or that failed the background check
 * ([SourceCheck]), is tried after the others for [SourceHealthLogic.TTL_MS]; one-click no longer starts it first just
 * because it played before. "No seek" (the server cannot jump into the file) only counts when resuming.
 * Device-wide prefs "fof_source_health", at most [SourceHealthLogic.MAX] entries.
 */
object SourceHealth {
    private const val PREFS = "fof_source_health"
    private val m = LinkedHashMap<String, SourceHealthLogic.Mark>()
    @Volatile private var loaded = false

    @Synchronized
    private fun init(c: Context?) {
        if (loaded || c == null) return
        loaded = true
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("m", "")?.let { m.putAll(SourceHealthLogic.decode(it)) }
    }

    @Synchronized
    fun mark(c: Context, content: String, key: String, state: Int) {
        init(c)
        SourceHealthLogic.put(m, content + "\t" + key, SourceHealthLogic.Mark(state, System.currentTimeMillis()))
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("m", SourceHealthLogic.encode(m)).apply()
    }

    fun failed(c: Context, content: String, key: String) = mark(c, content, key, SourceHealthLogic.FAILED)
    fun ok(c: Context, content: String, key: String) = mark(c, content, key, SourceHealthLogic.OK)

    /** 0 = fine or unknown, 1 = try after the others ([resuming]: a server that cannot seek counts too). */
    @Synchronized
    fun penalty(content: String, key: String, resuming: Boolean): Int =
        SourceHealthLogic.penalty(m[content + "\t" + key], System.currentTimeMillis(), resuming)

    @Synchronized
    fun state(content: String, key: String): Int = m[content + "\t" + key]?.takeIf { System.currentTimeMillis() - it.at < SourceHealthLogic.TTL_MS }?.state ?: SourceHealthLogic.UNKNOWN

    fun load(c: Context) = init(c)
}

/** Pure part (tested in SourceHealthLogicTest). */
object SourceHealthLogic {
    const val UNKNOWN = 0
    const val OK = 1
    const val FAILED = 2
    const val NO_SEEK = 3
    const val TTL_MS = 6 * 3600_000L
    const val MAX = 400

    data class Mark(val state: Int, val at: Long)

    fun penalty(mark: Mark?, now: Long, resuming: Boolean): Int {
        if (mark == null || now - mark.at >= TTL_MS) return 0
        return when (mark.state) { FAILED -> 1; NO_SEEK -> if (resuming) 1 else 0; else -> 0 }
    }

    fun put(m: LinkedHashMap<String, Mark>, k: String, v: Mark) {
        m.remove(k); m[k] = v
        while (m.size > MAX) m.remove(m.keys.first())
    }

    fun encode(m: Map<String, Mark>): String = m.entries.joinToString("\n") { it.key.replace('\n', ' ') + "\t" + it.value.state + "\t" + it.value.at }

    fun decode(s: String): Map<String, Mark> {
        val out = LinkedHashMap<String, Mark>()
        for (l in s.split('\n')) {
            val p = l.split('\t')
            if (p.size < 4) continue
            val at = p.last().toLongOrNull() ?: continue
            val st = p[p.size - 2].toIntOrNull() ?: continue
            out[p.dropLast(2).joinToString("\t")] = Mark(st, at)
        }
        return out
    }
}
