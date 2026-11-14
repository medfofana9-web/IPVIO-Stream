package com.ipvio.stream

import android.content.Context

/** Pure order rules of a 24/7 show channel (no Android, testable). */
object MarathonLogic {
    /** Episode order: in order (season, episode) or shuffled; [seed] makes a shuffle repeatable. */
    fun order(eps: List<Pair<Int, Int>>, shuffle: Boolean, seed: Long): List<Pair<Int, Int>> {
        val sorted = eps.distinct().sortedWith(compareBy({ it.first }, { it.second }))
        return if (shuffle) sorted.shuffled(java.util.Random(seed)) else sorted
    }

    /** In order: start at [from] (the episode last watched) when the show has it, otherwise at the first episode. */
    fun startIndex(order: List<Pair<Int, Int>>, from: Pair<Int, Int>?): Int =
        from?.let { f -> order.indexOf(f) }?.takeIf { it >= 0 } ?: 0
}

/**
 * 24/7 show channel: plays one show without end, in order or shuffled. Every episode gets a fresh link from the
 * Stremio addons (they expire); an episode without a working link is skipped. At the end it starts over (a new shuffle).
 */
object Marathon {
    class Pick(val url: String, val title: String, val season: Int, val ep: Int, val alts: List<Stremio.Source>)

    @Volatile var showId: String? = null
        private set
    var shuffle = false
        private set
    private var title = ""
    private var imdb = ""
    private var year: Int? = null
    private var order: List<Pair<Int, Int>> = emptyList()
    private var idx = 0

    fun owns(itemId: String?): Boolean = itemId != null && itemId == showId

    fun stop() { showId = null; order = emptyList() }

    /** Starts the channel for [item] (an addon show); null when the show has no episodes or no episode would play. */
    suspend fun start(c: Context, item: Item, shuffle: Boolean): Pick? {
        val id = item.id.removePrefix("stremio:").takeIf { StremioLogic.isImdb(it) } ?: return null
        val eps = (LazySeries.load(c, item) ?: item).episodes.map { it.season to it.num }
        if (eps.isEmpty()) return null
        this.shuffle = shuffle
        title = item.title; imdb = id; year = item.year ?: Catalog.meta(item)?.year?.toString()?.take(4)?.toIntOrNull()
        order = MarathonLogic.order(eps, shuffle, System.currentTimeMillis())
        val last = WatchStore.entries.firstOrNull { it.itemId == item.id && it.season > 0 }?.let { it.season to it.ep }
        idx = if (shuffle) 0 else MarathonLogic.startIndex(order, last)
        showId = item.id
        return resolve(0)
    }

    /** The next episode with a working link (null when the channel was stopped meanwhile or nothing plays). */
    suspend fun next(): Pick? {
        if (showId == null || order.isEmpty()) return null
        return resolve(1)
    }

    private suspend fun resolve(step: Int): Pick? {
        val owner = showId
        // Up to 8 episodes in a row may have no link before the channel gives up
        repeat(8) { tryNo ->
            if (showId != owner || order.isEmpty()) return null
            if (tryNo > 0 || step > 0) {
                idx++
                if (idx >= order.size) {
                    idx = 0
                    if (shuffle) order = MarathonLogic.order(order, true, System.currentTimeMillis())
                }
            }
            val (s, e) = order[idx]
            val list = runCatching { Stremio.streams(Kind.SERIES, imdb, s, e) }.getOrDefault(emptyList()).filter { it.url.isNotBlank() }
            // Only a stream confirmed as this episode plays on its own; otherwise the episode is skipped (never a guess)
            val want = Stremio.Want(true, title, year, s, e)
            val cid = SourceMemory.contentId(imdb, true, s, e)
            val best = if (showId == owner) Stremio.bestVerified(list, want, cid) else null
            if (best != null) {
                NowSources.set(owner ?: "", imdb, want, Stremio.rank(list, want, cid), best)
                return Pick(best.url, "$title S${s}E$e", s, e, list)
            }
        }
        return null
    }
}
