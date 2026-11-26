package com.ipvio.stream

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Automatic problem reports: things that go wrong on a TV (a game with no matching channel, a stream that never starts,
 * a picture that never loads, a slow screen, a crash) are sent to the account's problem list (`fof_reports`, kind
 * "<type>: <detail>"). A GitHub workflow turns new reports into issues, and the scheduled fix task works on them.
 *
 * Sends only short descriptions (title, screen, error text) – never links, passwords or playlist addresses.
 * The same problem is sent at most once a day, and at most 30 problems a day per TV.
 */
object AutoReport {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val PREFS = "fof_autoreport"
    private const val DAY = 86_400_000L

    /** [details] = what changes every time (durations, memory, last steps); the problem's name is [type] + [detail]. */
    fun issue(c: Context, type: String, detail: String, details: String = "") {
        val app = c.applicationContext
        if (!FofAccount.signedIn) return
        val stable = ReportLogic.clean(type + ": " + detail).take(380)
        val full = ReportLogic.clean(ReportLogic.join(stable, details))
        scope.launch {
            val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val key = "k" + stable.hashCode()
            if (now - p.getLong(key, 0L) < DAY) return@launch
            val day = now / DAY
            val count = if (p.getLong("day", 0L) == day) p.getInt("count", 0) else 0
            if (count >= 30) return@launch
            val t = FofAccount.tokenFor(app) ?: return@launch
            val code = post(app, t, Profiles.activeName(), full, now)
            if (code in 200..299) p.edit().putLong(key, now).putLong("day", day).putInt("count", count + 1).apply()
        }
    }

    /**
     * Sends one report (split into rows of at most 400 characters, see [ReportLogic.split]); blocking, call off the
     * main thread. Returns the HTTP code of the head row.
     */
    fun post(c: Context, token: String, profile: String, kind: String, atMs: Long): Int {
        val id = java.lang.Long.toString(atMs % 1_000_000_000L xor (kind.hashCode().toLong() and 0xFFFF), 36)
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(atMs))
        var first = 0
        for ((i, row) in ReportLogic.split(ReportLogic.clean(kind), id).withIndex()) {
            val body = JSONObject().put("profile", profile.take(60)).put("kind", row)
                .put("version", AppUpdate.currentName(c).take(30))
                .put("device", (Build.MANUFACTURER + " " + Build.MODEL).trim().take(80))
                .put("happened_at", stamp)
            val code = runCatching { FofAccount.call("POST", "/rest/v1/fof_reports", body.toString(), token).first }.getOrDefault(0)
            if (i == 0) { first = code; if (code !in 200..299) break }
        }
        return first
    }
}
