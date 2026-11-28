package com.ipvio.stream

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.tv.TvContract
import android.os.Build

/**
 * "Continue watching" on the TV's own home screen (Google TV / Android TV "Watch Next" row, 27 Nov 2026): the titles of
 * the app's Continue watching row, so a show can be resumed from the home screen without opening the app first.
 * Uses the platform TV provider (no extra library); the provider only shows and changes this app's own entries. Where
 * there is none (Fire TV, phones) nothing happens. A title the viewer removed from the home row is not put back until
 * its episode changes. Choosing it opens the app on that title's Resume (same path as the app's own row).
 *
 * Pure part: [HomeRowLogic] (tested in HomeRowLogicTest).
 */
internal object HomeRow {
    const val EXTRA = "fof_resume"
    private const val ACTION = "com.fof.tv.RESUME"
    private val exec = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var lastSig = ""
    @Volatile private var lastAt = 0L

    // Column names and values of TvContract.WatchNextPrograms (API 26), spelled out: the constants live on Java interfaces
    private const val C_TYPE = "type"
    private const val C_WN_TYPE = "watch_next_type"
    private const val C_TITLE = "title"
    private const val C_SEASON = "season_display_number"
    private const val C_EPISODE = "episode_display_number"
    private const val C_POS = "last_playback_position_millis"
    private const val C_DUR = "duration_millis"
    private const val C_ENGAGED = "last_engagement_time_utc_millis"
    private const val C_POSTER = "poster_art_uri"
    private const val C_POSTER_RATIO = "poster_art_aspect_ratio"
    private const val C_INTENT = "intent_uri"
    private const val C_KEY = "internal_provider_id"
    private const val C_BROWSABLE = "browsable"
    private const val TYPE_MOVIE = 0
    private const val TYPE_TV_EPISODE = 3
    private const val WN_CONTINUE = 0
    private const val WN_NEXT = 1
    private const val RATIO_2_3 = 4

    private class Row(val e: Entry, val poster: String?)

    /**
     * After Continue watching changed (main thread): brings the home row up to date in the background, at most every
     * 2 minutes unless the titles or their order changed.
     */
    fun kick(c: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val app = c.applicationContext
        if (!runCatching { app.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) }.getOrDefault(false)) return
        val picked = HomeRowLogic.pick(WatchStore.entries.toList())
        val sig = picked.joinToString(",") { HomeRowLogic.key(it) }
        val now = System.currentTimeMillis()
        if (sig == lastSig && now - lastAt < 120_000L) return
        lastSig = sig; lastAt = now
        val rows = picked.map { e -> Row(e, CatalogItems.get(e.itemId)?.logo) }
        exec.execute { runCatching { sync(app, rows) }.onFailure { log("home row: " + it.javaClass.simpleName) } }
    }

    private fun log(s: String) { if (BuildConfig.VERSION_NAME.endsWith("-test")) android.util.Log.i("FofPlay", s) }

    private fun sync(c: Context, rows: List<Row>) {
        val cr = c.contentResolver
        val uri = TvContract.WatchNextPrograms.CONTENT_URI
        val have = HashMap<String, Long>()
        val removed = HashSet<String>()
        cr.query(uri, arrayOf(android.provider.BaseColumns._ID, C_KEY, C_BROWSABLE), null, null, null)?.use { q ->
            while (q.moveToNext()) {
                val key = q.getString(1) ?: continue
                have[key] = q.getLong(0)
                if (q.getInt(2) == 0) removed.add(key)
            }
        }
        val want = rows.associateBy { HomeRowLogic.key(it.e) }
        // Gone from Continue watching (or an older episode of it): off the home row
        for ((key, id) in have) if (key !in want) cr.delete(ContentUris.withAppendedId(uri, id), null, null)
        for ((key, r) in want) {
            if (key in removed) continue
            val v = values(c, r, key)
            val id = have[key]
            if (id != null) cr.update(ContentUris.withAppendedId(uri, id), v, null, null) else cr.insert(uri, v)
        }
        log("home row " + want.size + " titles")
    }

    private fun values(c: Context, r: Row, key: String) = ContentValues().apply {
        val e = r.e
        val episode = e.season > 0 && e.ep > 0
        put(C_TYPE, if (episode) TYPE_TV_EPISODE else TYPE_MOVIE)
        put(C_WN_TYPE, if (e.next) WN_NEXT else WN_CONTINUE)
        put(C_TITLE, HomeRowLogic.title(e))
        if (episode) { put(C_SEASON, e.season.toString()); put(C_EPISODE, e.ep.toString()) }
        if (!e.next && e.dur > 0) { put(C_POS, e.pos.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()); put(C_DUR, e.dur.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) }
        put(C_ENGAGED, e.ts)
        r.poster?.takeIf { it.startsWith("http") }?.let { put(C_POSTER, it); put(C_POSTER_RATIO, RATIO_2_3) }
        put(C_INTENT, Intent(c, MainActivity::class.java).setAction(ACTION).putExtra(EXTRA, e.itemId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).toUri(Intent.URI_INTENT_SCHEME))
        put(C_KEY, key)
    }
}

/** Pure rules of the home row (tested in HomeRowLogicTest). */
object HomeRowLogic {
    const val MAX = 10

    /** What goes on the home row: titles with a resume spot or a next episode, newest first, at most [MAX]. */
    fun pick(entries: List<Entry>): List<Entry> = entries.filter { it.next || it.pos > 0 }.sortedByDescending { it.ts }.take(MAX)

    /** One home-row entry per title and episode: a new episode is a new entry (shown again even if the last was removed). */
    fun key(e: Entry): String = e.itemId + "|" + e.season + "|" + e.ep

    /** The show's name without the " S1E2" the app adds to episode titles. */
    fun title(e: Entry): String =
        if (e.season > 0 && e.ep > 0) e.title.substringBeforeLast(" S" + e.season + "E" + e.ep).trim().ifEmpty { e.title } else e.title
}
