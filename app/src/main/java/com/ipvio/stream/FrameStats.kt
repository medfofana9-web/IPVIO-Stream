package com.ipvio.stream

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Measures real frames on this TV (Android frame metrics, no extra library): per screen how many frames were slow and
 * which phase cost the time (input, animation, layout, draw, sync/GPU). Every few minutes of use a short summary goes
 * to the account's problem list (fof_reports, kind "perf: …"), so smoothness is judged from the TV itself, not the
 * emulator. No titles, names or addresses are recorded.
 */
object FrameStats {
    /** Current screen: the player, or the side menu tab (0 Home, …). */
    // The idle screensaver is its own screen: its frames (minutes of slow artwork) were counted against the tab
    // underneath and made e.g. Sports look 97 % slow and GPU-bound while nobody was navigating
    private fun screen(): String = if (AppNav.playing) "player" else if (Ambient.on) "ambient" else "tab" + NavState.tab

    private class Agg {
        var frames = 0; var slow = 0; var frozen = 0; var worst = 0L; val ph = LongArray(5); val hist = IntArray(6)
        // Frames drawn with no key pressed for 3 s (a still screen should draw almost none), when it started, and the
        // place inside the screen of each slow frame (title page, settings …)
        var idle = 0; var idleSlow = 0; val since = System.currentTimeMillis(); val where = HashMap<String, Int>()
    }
    private val agg = HashMap<String, Agg>()
    private var started = 0L
    private var thread: HandlerThread? = null
    private var listener: Window.OnFrameMetricsAvailableListener? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun install(a: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || listener != null) return
        val t = HandlerThread("frames").also { it.start() }
        thread = t
        val l = Window.OnFrameMetricsAvailableListener { _, m, _ -> add(m) }
        listener = l
        runCatching { a.window.addOnFrameMetricsAvailableListener(l, Handler(t.looper)) }
        started = System.currentTimeMillis()
    }

    fun uninstall(a: Activity) {
        listener?.let { l -> runCatching { a.window.removeOnFrameMetricsAvailableListener(l) } }
        listener = null; thread?.quitSafely(); thread = null
    }

    private fun add(m: FrameMetrics) {
        if (m.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) return
        val total = m.getMetric(FrameMetrics.TOTAL_DURATION)
        val ms = total / 1_000_000L
        synchronized(agg) {
            val a = agg.getOrPut(screen()) { Agg() }
            a.frames++
            a.hist[when { ms < 17 -> 0; ms < 25 -> 1; ms < 34 -> 2; ms < 50 -> 3; ms < 100 -> 4; else -> 5 }]++
            if (ms > a.worst) a.worst = ms
            val idle = System.currentTimeMillis() - Ambient.lastKey > IDLE_MS
            if (idle) { a.idle++; idleFrames++; if (ms >= 25) a.idleSlow++ }
            if (ms >= 25) {
                a.slow++
                a.where.merge(ReportCtx.place, 1, Int::plus)
                if (ms >= 700) a.frozen++
                a.ph[0] += m.getMetric(FrameMetrics.INPUT_HANDLING_DURATION) + m.getMetric(FrameMetrics.UNKNOWN_DELAY_DURATION)
                a.ph[1] += m.getMetric(FrameMetrics.ANIMATION_DURATION)
                a.ph[2] += m.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION)
                a.ph[3] += m.getMetric(FrameMetrics.DRAW_DURATION)
                a.ph[4] += m.getMetric(FrameMetrics.SYNC_DURATION) + m.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION) +
                    (if (Build.VERSION.SDK_INT >= 31) m.getMetric(FrameMetrics.GPU_DURATION) else m.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION))
            }
        }
    }

    /** Pure: one summary line per screen ("tab0 1200f slow 14% (<17:…) worst 180ms | input 5% anim 10% layout 30% draw 35% gpu 20%"). */
    internal fun line(name: String, frames: Int, slow: Int, frozen: Int, worst: Long, ph: LongArray, hist: IntArray): String {
        val sum = ph.sum().coerceAtLeast(1)
        fun pc(i: Int) = (ph[i] * 100 / sum).toInt()
        return "$name ${frames}f slow ${slow * 100 / frames.coerceAtLeast(1)}%" + (if (frozen > 0) " frozen $frozen" else "") +
            " worst ${worst}ms h=" + hist.joinToString("/") + " | in ${pc(0)} an ${pc(1)} lay ${pc(2)} dr ${pc(3)} gpu ${pc(4)}"
    }

    private const val IDLE_MS = 3_000L

    /** Details of one screen's summary: idle drawing, where the slow frames were, settings, display, memory. */
    private fun details(c: Context, a: Agg): String {
        val secs = ((System.currentTimeMillis() - a.since) / 1000).coerceAtLeast(1)
        val idle = "idle ${a.idle}f" + (if (a.idle > 0) " slow ${a.idleSlow * 100 / a.idle}%" else "") + " · over ${secs}s"
        return listOf(idle, "where " + ReportLogic.places(a.where), ReportCtx.settings(), ReportCtx.display, ReportCtx.mem(c))
            .filter { it.isNotBlank() }.joinToString(" · ")
    }

    /** Sends the summary (when there is enough to judge) and starts a new measurement. */
    fun flush(c: Context) {
        val lines = synchronized(agg) {
            val l = agg.filter { it.value.frames >= 120 }.map { (k, a) ->
                ReportLogic.join(line(k, a.frames, a.slow, a.frozen, a.worst, a.ph, a.hist), details(c, a))
            }
            if (l.isNotEmpty()) agg.clear()
            l
        }
        if (lines.isEmpty() || !FofAccount.signedIn) return
        send(c, lines.map { "perf: " + it })
    }

    /** All frames drawn with no key pressed for 3 s, since start (read by [IdleDraw]; written by the frames thread only). */
    @Volatile var idleFrames = 0L
        private set

    /** "Report lag" (double Menu): what was measured since the last summary, every screen, without clearing it. */
    fun snapshot(): List<String> = synchronized(agg) {
        agg.filter { it.value.frames >= 10 }.map { (k, a) -> line(k, a.frames, a.slow, a.frozen, a.worst, a.ph, a.hist) }
    }
    fun currentScreen(): String = screen()

    /** Sends ready-made report lines (used by [LagReport]). */
    fun sendLines(c: Context, lines: List<String>) = send(c, lines)

    enum class Incident(val label: String) {
        SLOW_START("slow playback start"),
        PLAYBACK_ERROR("playback error"),
        REBUFFERING("repeated buffering"),
        PROFILE_SYNC_ERROR("profile sync failed"),
        SPORTS_NO_MATCH("sports: no confirmed channel"),
        MARATHON_EXHAUSTED("24/7: no playable episode")
    }

    private val reportedAt = HashMap<Incident, Long>()

    /**
     * Only fixed labels name the incident; [details] adds error codes, stream kind and format (never addresses or
     * titles). Repeated incidents are limited to one per category per minute.
     */
    fun incident(c: Context, incident: Incident, details: String = "") {
        if (!FofAccount.signedIn) return
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(reportedAt) {
            if (reportedAt[incident]?.let { now - it < 60_000 } == true) return
            reportedAt[incident] = now
        }
        send(c, listOf(ReportLogic.join("reliability: " + incident.label, details)))
    }

    private fun send(c: Context, summaries: List<String>) {
        val app = c.applicationContext
        scope.launch {
            val t = FofAccount.tokenFor(app) ?: return@launch
            for (summary in summaries) AutoReport.post(app, t, "-", summary, System.currentTimeMillis())
        }
    }
}
