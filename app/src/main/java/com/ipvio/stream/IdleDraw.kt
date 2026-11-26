package com.ipvio.stream

import android.content.Context

/**
 * A still screen should draw (almost) no frames. When nobody has pressed a key for 20 s and the screen keeps drawing
 * at [MIN_FPS] or more for 10 s (an endless animation, a ticker, a glow that never settles), the main thread is
 * sampled for 5 s once per screen and run, and the busiest app code is reported as "idle-draw: <screen> · <code>".
 * This is what explains a screensaver or a Home screen that is "98 % slow" while nobody uses it. Checked every
 * 500 ms by [FreezeWatch]'s thread; nothing but class, method and line names is sent.
 */
object IdleDraw {
    private const val MIN_FPS = 15
    private val done = HashSet<String>()
    @Volatile private var sampling = false
    private var at = 0L
    private var frames = 0L

    fun check(c: Context) {
        val now = System.currentTimeMillis()
        if (now - at < 10_000) return
        val count = FrameStats.idleFrames
        val quietSince = now - Ambient.lastKey
        // The whole last window must have been without keys
        val fps = if (at > 0 && quietSince > 20_000 && quietSince - (now - at) > 3_000) ((count - frames) * 1000 / (now - at)).toInt() else -1
        at = now; frames = count
        if (fps < MIN_FPS || sampling || !FofAccount.signedIn) return
        val screen = ReportCtx.where()
        if (!done.add(screen)) return
        sampling = true
        MainSampler.sample(5_000) { busy, appTop, appAll, topFrame ->
            sampling = false
            val name = (appTop.firstOrNull() ?: topFrame.firstOrNull())?.substringAfter("x ") ?: "?"
            AutoReport.issue(c, "idle-draw", "$screen · $name", listOf(
                "$fps fps with no key for ${quietSince / 1000}s", "main busy $busy%",
                "app: " + appTop.take(4).joinToString(" ; "), "anywhere: " + appAll.take(5).joinToString(" ; "),
                "top: " + topFrame.take(3).joinToString(" ; "), ReportCtx.all(c)
            ).joinToString(" · "))
        }
    }
}
