package com.ipvio.stream

/**
 * Continue watching across TVs and sources: an entry saved on another TV may use an id this TV does not know (another
 * playlist, playlist still loading, addon title not cached). Instead of dropping it, the same title is looked up by
 * its exact name and kind (only when exactly one title matches, so similar names never mix); otherwise the entry is
 * shown as it was saved and resumes with its saved address and position.
 */
object ResumeMatch {
    private var index: Map<String, List<Item>> = emptyMap()
    private var indexFor: Map<String, Item>? = null

    fun find(e: Entry, byId: Map<String, Item>): Item {
        val series = e.season > 0 || e.ep > 0 || ResumeLogic.seasonEp(e.title) != null
        val kind = if (series) Kind.SERIES else Kind.MOVIE
        val title = ResumeMatchLogic.baseTitle(e.title)
        if (indexFor !== byId) synchronized(this) {
            index = byId.values.filter { it.kind == Kind.MOVIE || it.kind == Kind.SERIES }.groupBy { ResumeMatchLogic.key(it.title, it.kind == Kind.SERIES) }
            indexFor = byId
        }
        index[ResumeMatchLogic.key(title, series)]?.singleOrNull()?.let { return it }
        return Item(e.itemId, kind, title, null, e.url, null, "")
    }
}

/** Pure parts (tested). */
object ResumeMatchLogic {
    private val SE = Regex("""\s*[Ss](\d{1,3})\s*[Ee](\d{1,4})\s*$""")
    fun baseTitle(t: String): String = t.replace(SE, "").trim()
    fun key(title: String, series: Boolean): String =
        (if (series) "s:" else "m:") + baseTitle(title).lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
}
