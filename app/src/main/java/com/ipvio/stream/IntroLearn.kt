package com.ipvio.stream

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

/**
 * "Skip intro" for every episode (27 Nov 2026). Most shows have no intro times in the online databases, so the button
 * never appeared. Now, early in an episode with no known intro, the control bar opens on a "Skip intro" button: it jumps
 * forward by the length learned for the show (60 s the first time). Where the viewer lands after nudging with Left/Right
 * is remembered, so the next episode's jump is right. Known times (TheIntroDB, SkipDB, marked by hand) still come first.
 *
 * Pure rules (tested in IntroSkipLogicTest).
 */
object IntroSkipLogic {
    /** The button is offered in the first 12 minutes (at most the first half of a short episode). */
    const val WINDOW_MS = 12 * 60_000L
    const val DEFAULT_JUMP_MS = 60_000L
    const val MIN_LEN_MS = 10_000L
    const val MAX_LEN_MS = 180_000L
    /** Seeks this soon after the jump count as fixing where the intro ended. */
    const val ADJUST_MS = 25_000L

    fun window(durMs: Long): Long = if (durMs > 0) minOf(WINDOW_MS, durMs / 2) else WINDOW_MS

    fun offer(posMs: Long, durMs: Long, episode: Boolean, knownIntro: Boolean, done: Boolean): Boolean =
        episode && !knownIntro && !done && posMs in 0 until window(durMs)

    fun plausible(lenMs: Long?): Boolean = lenMs != null && lenMs in MIN_LEN_MS..MAX_LEN_MS

    fun jumpMs(learned: Long?): Long = if (plausible(learned)) learned!! else DEFAULT_JUMP_MS

    /** Where to jump from [atMs]: the jump, but never past 5 s before the end. */
    fun target(atMs: Long, jumpMs: Long, durMs: Long): Long {
        val t = atMs + jumpMs
        return if (durMs > 0) minOf(t, (durMs - 5_000L).coerceAtLeast(atMs)) else t
    }

    /** Length to remember after a press at [pressMs] that ended at [landedMs]; null when it makes no sense (seeked back). */
    fun learned(pressMs: Long, landedMs: Long): Long? = (landedMs - pressMs).takeIf { plausible(it) }

    /**
     * SkipDB answer (GET api.skipdb.tv/api/segments?imdb_id=…&season=…&episode=…&duration=<s>): segments.intro / recap /
     * outro with start_ms and end_ms (ms). Returns (kind, start, end) with kind from [SegLogic]; unknown shapes give nothing.
     */
    fun parseSkipDb(body: String): List<Triple<Int, Long?, Long?>> {
        val out = ArrayList<Triple<Int, Long?, Long?>>()
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return out
        val g = o.optJSONObject("segments") ?: o
        fun read(key: String, kind: Int) {
            val x = g.optJSONObject(key) ?: return
            fun ms(k: String): Long? = if (!x.has(k) || x.isNull(k)) null else x.optLong(k, -1L).takeIf { it >= 0 }
            out.add(Triple(kind, ms("start_ms"), ms("end_ms")))
        }
        read("intro", SegLogic.INTRO)
        read("recap", SegLogic.RECAP)
        read("outro", SegLogic.CREDITS)
        return out
    }
}

/**
 * Intro length per show, per profile, in the intro markers' store (fof_intro_marks_v1, already synced between TVs and
 * kept per profile): one new key per show, nothing renamed.
 */
internal object IntroLearn {
    var version by mutableIntStateOf(0)
        private set

    private fun prefs(c: Context) = Profiles.sp(c, "fof_intro_marks_v1")
    private fun key(showId: String) = "show_" + showId.hashCode().toUInt().toString(16) + "_intro_len"

    fun load(c: Context, showId: String): Long? = prefs(c).getLong(key(showId), -1L).takeIf { IntroSkipLogic.plausible(it) }

    fun save(c: Context, showId: String, lenMs: Long) {
        if (!IntroSkipLogic.plausible(lenMs)) return
        prefs(c).edit().putLong(key(showId), lenMs).apply()
        version++
        CloudSync.kick(c)
    }

    /** Known times from a database fill in only what the viewer has not taught yet. */
    fun saveIfNone(c: Context, showId: String, lenMs: Long) { if (load(c, showId) == null) save(c, showId, lenMs) }
}
