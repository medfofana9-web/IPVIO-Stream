package com.ipvio.stream

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Main-thread freeze watch (release builds too): a background thread checks every 500 ms that the main thread still
 * runs its queue. When it has been stuck for [FreezeLogic.FREEZE_MS], the main thread's stack is read once and a short
 * report goes to the problem list ("freeze: 2.4s · HomeScreen.kt:812 < LazyListState… "), at most one per kind a day
 * (see [AutoReport]). Only class, method and line are sent – never data, titles or addresses. R8 renames classes in
 * release builds; the build's mapping file (kept by release.yml) turns the lines back into source lines.
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
                val stuck = SystemClock.uptimeMillis() - lastBeat
                // One report per freeze (the same freeze is seen on every check while it lasts)
                if (stuck >= FreezeLogic.FREEZE_MS && reportedFor != lastBeat) {
                    reportedFor = lastBeat
                    val trace = runCatching { Looper.getMainLooper().thread.stackTrace.toList() }.getOrDefault(emptyList())
                    val where = FreezeLogic.summarize(trace)
                    if (where.isNotEmpty()) {
                        val screen = if (AppNav.playing) "player" else "tab" + NavState.tab
                        val line = "Freeze " + (stuck / 100) / 10.0 + " s on " + screen + ": " + where
                        // Diagnostics screen state is written on the main thread, once it runs again
                        main.post { Diag.report(line) }
                        AutoReport.issue(app, "freeze", screen + " · " + where)
                    }
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
