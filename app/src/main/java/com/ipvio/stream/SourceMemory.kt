package com.ipvio.stream

import android.content.Context

/**
 * Per title or episode (content id such as "tt1234567|4|1"): sources reported as wrong content and the last source that
 * played. Device-wide prefs "fof_sources"; at most 300 titles are kept (oldest dropped).
 */
object SourceMemory {
    private const val MAX = 300
    private val wrong = LinkedHashMap<String, MutableSet<String>>()
    private val good = LinkedHashMap<String, String>()
    @Volatile private var loaded = false
    private var app: Context? = null

    fun contentId(imdb: String?, series: Boolean, season: Int, ep: Int): String =
        (imdb ?: "?") + if (series) "|$season|$ep" else ""

    @Synchronized
    fun init(c: Context) {
        if (loaded) return
        app = c.applicationContext
        val p = prefs(c)
        p.getString("wrong", "")?.split('\n')?.forEach { l ->
            val i = l.indexOf('\t'); if (i > 0) wrong.getOrPut(l.substring(0, i)) { LinkedHashSet() }.add(l.substring(i + 1))
        }
        p.getString("good", "")?.split('\n')?.forEach { l -> val i = l.indexOf('\t'); if (i > 0) good[l.substring(0, i)] = l.substring(i + 1) }
        loaded = true
    }

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("fof_sources", Context.MODE_PRIVATE)

    @Synchronized fun isWrong(content: String, key: String): Boolean = wrong[content]?.contains(key) == true
    @Synchronized fun lastGood(content: String): String? = good[content]

    @Synchronized
    fun reportWrong(c: Context, content: String, key: String) {
        init(c)
        wrong.getOrPut(content) { LinkedHashSet() }.add(key)
        if (good[content] == key) good.remove(content)
        while (wrong.size > MAX) wrong.remove(wrong.keys.first())
        save(c)
    }

    @Synchronized
    fun played(c: Context, content: String, key: String) {
        init(c)
        if (good[content] == key || isWrong(content, key)) return
        good.remove(content); good[content] = key
        while (good.size > MAX) good.remove(good.keys.first())
        save(c)
    }

    private fun save(c: Context) {
        prefs(c).edit()
            .putString("wrong", wrong.flatMap { (k, v) -> v.map { "$k\t$it" } }.joinToString("\n"))
            .putString("good", good.entries.joinToString("\n") { it.key + "\t" + it.value })
            .apply()
    }
}

/**
 * The exact content being played from an addon and its sources: set by the source picker, read by the player
 * (Sources panel, Wrong content, fallbacks). The selected movie or episode stays the identity throughout –
 * the player never looks the title up again by name.
 */
object NowSources {
    @Volatile var itemId: String? = null
    @Volatile var imdb: String? = null
    @Volatile var want: Stremio.Want? = null
    @Volatile var list: List<Stremio.Source> = emptyList()
    @Volatile var currentKey: String? = null

    val contentId: String get() = SourceMemory.contentId(imdb, want?.series == true, want?.season ?: 0, want?.ep ?: 0)

    fun set(itemId: String, imdb: String?, want: Stremio.Want, list: List<Stremio.Source>, chosen: Stremio.Source?) {
        this.itemId = itemId; this.imdb = imdb; this.want = want; this.list = list; currentKey = chosen?.key
    }

    fun owns(itemId: String?, season: Int, ep: Int): Boolean {
        val w = want ?: return false
        return itemId != null && itemId == this.itemId && (!w.series || (w.season == season && w.ep == ep))
    }
}
