package com.ipvio.stream

/**
 * Word index of sports channel titles (pure, tested in TeamIndexTest): the team directory behind game-card starts.
 * Titles are normalized and padded with spaces (" nba detroit pistons "); a team key such as " detroit pistons " can only
 * be inside a title that has its first word as a whole word, so looking that word up finds every candidate.
 */
object TeamIndex {
    fun build(titles: List<String>): Map<String, IntArray> {
        val m = HashMap<String, MutableList<Int>>()
        titles.forEachIndexed { i, t ->
            for (w in t.split(' ').toSet()) if (w.length >= 2) m.getOrPut(w) { ArrayList(4) }.add(i)
        }
        return m.mapValues { it.value.toIntArray() }
    }

    /**
     * Channel positions that may contain any of [paddedKeys] (" detroit pistons "), in list order. With no index (built by
     * an older list) every position is a candidate, as before.
     */
    fun candidates(index: Map<String, IntArray>, paddedKeys: List<String>, size: Int): IntArray {
        if (index.isEmpty()) return IntArray(size) { it }
        val set = java.util.TreeSet<Int>()
        for (k in paddedKeys) {
            val first = k.trim().substringBefore(' ')
            index[first]?.forEach { set.add(it) }
        }
        return set.toIntArray()
    }
}
