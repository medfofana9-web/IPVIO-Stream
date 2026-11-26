package com.ipvio.stream

import android.content.Context
import android.os.Build
import android.widget.Toast

/**
 * "Report lag": press the remote's Menu (☰) button twice quickly on any screen when something feels slow. The TV
 * sends what it measured right then – the screen you are on, frame times of every screen since the last summary,
 * memory, Lite mode, playlist size – to the account's problem list, where it becomes a GitHub issue ("lag: …")
 * within the hour. No titles, names or addresses are sent.
 */
object LagReport {
    private var lastMenuUp = 0L
    private var lastSent = 0L

    /** Called for every Menu key release; true when this release triggered a report (the key is then used up). */
    fun onMenuUp(c: Context): Boolean {
        val now = System.currentTimeMillis()
        val double = now - lastMenuUp < 700
        lastMenuUp = if (double) 0L else now
        if (!double) return false
        if (now - lastSent < 15_000) { toast(c, "Already sent – thanks!"); return true }
        lastSent = now
        send(c)
        return true
    }

    fun send(c: Context) {
        val app = c.applicationContext
        val rt = Runtime.getRuntime()
        val heapPct = ((rt.totalMemory() - rt.freeMemory()) * 100 / rt.maxMemory().coerceAtLeast(1)).toInt()
        val head = "lag: report · on " + FrameStats.currentScreen() +
            " · ram " + Mem.totalMb(app) + "MB tier " + Mem.tier(app) + " · heap " + heapPct + "% · lite " + (if (Lite.on) "on" else "off") +
            " · channels " + PlaylistStore.channels.size + " · " + Build.MANUFACTURER + " " + Build.MODEL
        val lines = listOf(head) + FrameStats.snapshot().map { "lag: frames " + it }
        if (!FofAccount.signedIn) { toast(app, "Sign in to your Fof TV account to send reports"); return }
        FrameStats.sendLines(app, lines)
        toast(app, "Measuring for 20 seconds – keep scrolling and clicking where it feels slow")
        // Then where the time went on this TV: the busiest app code while you kept using it
        val screen = FrameStats.currentScreen()
        MainSampler.sample(20_000) { busy, appTop, appAll, topFrame ->
            val out = listOf("lag: busy " + busy + "% of the time on " + screen + " (main thread, 20 s)") +
                appTop.map { "lag: hot " + it } + appAll.map { "lag: stack " + it } + topFrame.map { "lag: top " + it }
            FrameStats.sendLines(app, out)
            android.os.Handler(android.os.Looper.getMainLooper()).post { toast(app, "Thanks – sent. It will be looked at and fixed.") }
        }
    }

    private fun toast(c: Context, t: String) = runCatching { Toast.makeText(c.applicationContext, t, Toast.LENGTH_LONG).show() }
}
