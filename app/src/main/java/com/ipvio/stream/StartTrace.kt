package com.ipvio.stream

import android.content.Context
import android.os.SystemClock

/**
 * Time from OK to the first picture on this TV, in steps: OK → source chosen → stream allowed (stream count) →
 * player asked to open the feed → first video frame, plus every feed that failed on the way. Shown in diagnostics
 * and, when it took longer than [StartTraceLogic.SLOW_MS], sent as "start: …" to the problem list. Only step names,
 * times and counts – never channel names, links or logins.
 */
object StartTrace {
    private var origin = ""
    private var tOk = 0L; private var tPick = 0L; private var tAllowed = 0L; private var tOpen = 0L; private var tLastOpen = 0L
    private var feeds = 0; private var failovers = 0
    @Volatile var last: String? = null
        private set
    /** Test builds only (logged with the result): the channel that showed the picture. */
    @Volatile var playingTitle = ""

    private fun now() = SystemClock.elapsedRealtime()

    /** OK pressed on a game card / Home live tile ([from] = "sports", "home") or a channel ("live"). */
    @Synchronized fun failoverCount(): Int = failovers

    @Synchronized fun ok(from: String) { ReportCtx.crumb("ok $from"); origin = from; tOk = now(); tPick = 0; tAllowed = 0; tOpen = 0; tLastOpen = 0; feeds = 0; failovers = 0 }

    /** Sources for the game are known ([n] candidates). */
    @Synchronized fun picked(n: Int) { if (tOk > 0 && tPick == 0L) { tPick = now(); feeds = n } }

    /** The stream count check passed; the player is about to open. A start without OK (zapping) begins a "live" trace. */
    @Synchronized fun allowed() {
        val t = now()
        if (tOk == 0L || t - tOk > 60_000L) { ok("live"); tPick = tOk }
        if (tAllowed == 0L) tAllowed = t
    }

    /** The player opens a feed. Without an OK before it (Up/Down or number zapping) this starts a "zap" trace. */
    @Synchronized fun opening() {
        val t = now()
        if (tOk == 0L || t - tOk > 60_000L) { ok("zap"); tPick = tOk; tAllowed = tOk }
        if (tOpen == 0L) tOpen = t
        tLastOpen = t
    }

    @Synchronized fun failover() {
        if (tOk > 0) { failovers++; tLastOpen = now() }
        android.util.Log.i("FofStart", "failover " + failovers + " after " + (now() - tOk) + " ms")
    }

    /** First rendered video frame. */
    fun firstFrame(c: Context) {
        val line = synchronized(this) {
            if (tOk == 0L) return
            val r = StartTraceLogic.line(origin, tOk, tPick, tAllowed, tOpen, now(), feeds, failovers, tLastOpen)
            tOk = 0L
            r
        } ?: return
        last = line.text
        ReportCtx.crumb("picture " + line.text.substringAfter(' ').substringBefore(' '))
        android.util.Log.i("FofStart", "first frame: " + line.text + (if (BuildConfig.VERSION_NAME.endsWith("-test")) " · on " + playingTitle else ""))
        Diag.report("Start " + line.text)
        if (line.slow) AutoReport.issue(c, "start", line.text, ReportCtx.all(c))
    }

    /** Player closed before a picture: the trace ends without a result. */
    @Synchronized fun abandon() { tOk = 0L }
}

/** Pure formatting of a start trace (tested in StartTraceTest). */
object StartTraceLogic {
    const val SLOW_MS = 3_000L

    class Line(val text: String, val slow: Boolean)

    private fun s(ms: Long) = String.format(java.util.Locale.US, "%.1f", ms.coerceAtLeast(0) / 1000.0)

    /** [lastOpen]: when the feed that showed the picture was opened (later than [open] after failovers). */
    fun line(origin: String, ok: Long, pick: Long, allowed: Long, open: Long, frame: Long, feeds: Int, failovers: Int, lastOpen: Long = 0L): Line? {
        if (ok <= 0 || frame < ok) return null
        val p = if (pick > 0) pick else ok
        val a = if (allowed > 0) allowed else p
        val o = if (open > 0) open else a
        val lo = if (lastOpen >= o) lastOpen else o
        val total = frame - ok
        val t = "$origin ${s(total)}s · pick ${s(p - ok)} · guard ${s(a - p)} · ui ${s(o - a)}" +
            (if (failovers > 0) " · failed feeds ${s(lo - o)}" else "") + " · net+decode ${s(frame - lo)}" +
            (if (feeds > 0) " · feeds $feeds" else "") + (if (failovers > 0) " · failover $failovers" else "")
        return Line(t, total >= SLOW_MS)
    }
}
