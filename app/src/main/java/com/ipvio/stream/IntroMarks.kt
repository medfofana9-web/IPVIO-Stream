package com.ipvio.stream

import android.content.Context

/** User-confirmed intro windows stored separately for each active profile and episode. */
internal object IntroMarks {
    internal data class Window(val startMs: Long, val endMs: Long) {
        fun contains(positionMs: Long): Boolean = positionMs >= startMs && positionMs < endMs - 1_000L
    }
    private fun prefs(context: Context) = Profiles.sp(context, "fof_intro_marks_v1")
    private fun key(itemId: String, season: Int, episode: Int, kind: String = "intro"): String =
        "episode_" + itemId.hashCode().toUInt().toString(16) + "_s" + season + "_e" + episode +
            (if (kind == "recap") "_recap" else "")
    internal fun valid(startMs: Long, endMs: Long, durationMs: Long): Boolean =
        startMs >= 0L && endMs > startMs + 1_000L &&
            endMs - startMs <= 15 * 60_000L &&
            (durationMs <= 0L || endMs <= durationMs)
    fun load(context: Context, itemId: String, season: Int, episode: Int, durationMs: Long, kind: String = "intro"): Window? {
        if (season <= 0 || episode <= 0) return null
        val p = prefs(context)
        val k = key(itemId, season, episode, kind)
        val start = p.getLong("${k}_start", -1L)
        val end = p.getLong("${k}_end", -1L)
        return if (valid(start, end, durationMs)) Window(start, end) else null
    }
    fun markStart(context: Context, itemId: String, season: Int, episode: Int, atMs: Long, kind: String = "intro") {
        if (season <= 0 || episode <= 0) return
        val k = key(itemId, season, episode, kind)
        prefs(context).edit().putLong("${k}_start", atMs.coerceAtLeast(0L)).putLong("${k}_end", -1L).apply()
        CloudSync.kick(context)
    }
    fun finish(context: Context, itemId: String, season: Int, episode: Int, atMs: Long, durationMs: Long, kind: String = "intro"): Boolean {
        if (season <= 0 || episode <= 0) return false
        val p = prefs(context)
        val k = key(itemId, season, episode, kind)
        val start = p.getLong("${k}_start", -1L)
        if (!valid(start, atMs, durationMs)) return false
        p.edit().putLong("${k}_end", atMs).apply()
        CloudSync.kick(context)
        return true
    }
    fun hasStart(context: Context, itemId: String, season: Int, episode: Int, kind: String = "intro"): Boolean =
        season > 0 && episode > 0 &&
            prefs(context).getLong(key(itemId, season, episode, kind) + "_start", -1L) >= 0L
    fun clear(context: Context, itemId: String, season: Int, episode: Int, kind: String = "intro") {
        val k = key(itemId, season, episode, kind)
        // Tombstones sync correctly even when another device still has the old markers.
        prefs(context).edit().putLong("${k}_start", -1L).putLong("${k}_end", -1L).apply()
        CloudSync.kick(context)
    }
}
