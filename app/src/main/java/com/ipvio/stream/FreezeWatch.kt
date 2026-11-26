package com.ipvio.stream

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Main-thread freeze watch (release builds too): a background thread checks every 500 ms that the main thread still
 * runs its queue. When it has been stuck for [FreezeLogic.FREEZE_MS], the main thread's stack is read every 500 ms until
 * it runs again, and a report goes to the problem list: the most common location as the name ("freeze: tab0 ·
 * HomeScreen.kt:812 …"), plus how long it lasted, how often each location was seen, the raw frames, memory and the
 * last steps ([ReportCtx]); at most one per location a day (see [AutoReport]). Only class, method and line are sent –
 * never data, titles or addresses. R8 renames library classes in release builds; tools/auto_reports.py turns the raw
 * frames back into source lines with the build's mapping file (kept by release.yml).
 * The same thread drives [ReportCtx.tick] and [IdleDraw.check].
 *
 * This is what turns "Home froze for 3.4 s" (frame metrics) into the code that blocked it.
 */
object FreezeWatch {
    @Volatile private var lastBeat = SystemClock.uptimeMillis()
    @Volatile private var running = false

    fun install(c: Context) {
        if (running) return
        running = true
        val app = c.applicationContext
        val main = Handler(Looper.getMainLooper())
        val beat = Runnable { lastBeat = SystemClock.uptimeMillis() }
        Thread({
            var reportedFor = 0L
            while (running) {
                main.post(beat)
                try { Thread.sleep(500) } catch (_: InterruptedException) { return@Thread }
                runCatching { ReportCtx.tick(app) }
                runCatching { IdleDraw.check(app) }
                val beatAt = lastBeat
                val stuck = SystemClock.uptimeMillis() - beatAt
                // One report per freeze (the same freeze is seen on every check while it lasts)
                if (stuck >= FreezeLogic.FREEZE_MS && reportedFor != beatAt) {
                    reportedFor = beatAt
                    val screen = if (AppNav.playing) "player" else "tab" + NavState.tab
                    val where = ReportCtx.where()
                    val steps = ReportCtx.steps()
                    // Sample the stuck main thread every 500 ms until it runs again (at most 12 s): one look at 2 s can
                    // catch the end of the freeze instead of its cause
                    val samples = ArrayList<List<StackTraceElement>>()
                    while (true) {
                        val t = runCatching { Looper.getMainLooper().thread.stackTrace.toList() }.getOrDefault(emptyList())
                        if (t.isNotEmpty() && !FreezeLogic.idle(t)) samples.add(t)
                        if (lastBeat != beatAt || SystemClock.uptimeMillis() - beatAt > 12_000) break
                        try { Thread.sleep(500) } catch (_: InterruptedException) { return@Thread }
                    }
                    val total = SystemClock.uptimeMillis() - beatAt
                    val ended = lastBeat != beatAt
                    // Only idle samples: the main thread was already free again (no real freeze, e.g. "ActivityThread.main")
                    if (samples.isEmpty()) continue
                    val sums = samples.map { FreezeLogic.summarize(it) }.filter { it.isNotEmpty() }
                    val top = sums.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: continue
                    val topTrace = samples[sums.indexOf(top).coerceAtLeast(0)]
                    val secs = (total / 100) / 10.0
                    val line = "Freeze " + secs + " s on " + screen + ": " + top
                    // Diagnostics screen state is written on the main thread, once it runs again
                    main.post { Diag.report(line) }
                    val details = listOf(
                        secs.toString() + " s" + (if (ended) "" else " (still stuck)") + " on " + where,
                        samples.size.toString() + " samples: " + ReportLogic.hot(sums),
                        "raw: " + FreezeLogic.raw(topTrace),
                        ReportCtx.mem(app), "steps: " + steps
                    ).joinToString(" · ")
                    AutoReport.issue(app, "freeze", "$screen · $top", details)
                }
            }
        }, "fof-freeze-watch").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }
}

/** Pure part (tested in FreezeLogicTest). */
object FreezeLogic {
    const val FREEZE_MS = 2_000L

    private val SKIP = listOf("java.", "javax.", "sun.", "dalvik.", "libcore.", "kotlin.", "kotlinx.coroutines.",
        "android.os.", "com.android.internal.", "java.lang.reflect.")

    /**
     * Where the main thread is stuck: the first app frames (com.ipvio.*), else the first frames that are not the
     * looper/JDK plumbing, as "File.kt:line method" joined by " < " (innermost first). Empty when nothing useful.
     */
    /** The main thread is waiting for work (the freeze already ended). */
    fun idle(trace: List<StackTraceElement>): Boolean = trace.firstOrNull()?.methodName == "nativePollOnce"

    /**
     * Raw frames for retracing (R8 renames library classes and moves inlined lines): "class.method:line" innermost
     * first, without the looper/JDK plumbing, at most [max].
     */
    fun raw(trace: List<StackTraceElement>, max: Int = 9): String =
        trace.filter { e -> RAW_SKIP.none { e.className.startsWith(it) } }.take(max)
            .joinToString(" < ") { it.className + "." + it.methodName + ":" + it.lineNumber }

    private val RAW_SKIP = listOf("java.lang.Thread", "dalvik.", "android.os.Looper", "android.os.Handler", "com.android.internal.os.",
        "java.lang.reflect.", "android.app.ActivityThread")

    fun summarize(trace: List<StackTraceElement>, max: Int = 5): String {
        if (trace.isEmpty()) return ""
        val app = trace.filter { it.className.startsWith("com.ipvio.") }
        val useful = app.ifEmpty { trace.filter { e -> SKIP.none { e.className.startsWith(it) } } }
        // The innermost non-app frame tells what kind of work blocked (disk, lock, JSON, layout)
        val top = trace.firstOrNull()?.let { e -> e.className.substringAfterLast('.') + "." + e.methodName }
        val parts = useful.take(max).map { e -> (e.fileName ?: e.className.substringAfterLast('.')) + ":" + e.lineNumber + " " + e.methodName }
        if (parts.isEmpty()) return top.orEmpty()
        return (if (top != null && app.isNotEmpty() && trace.first() !== app.first()) "[$top] " else "") + parts.joinToString(" < ")
    }
}
