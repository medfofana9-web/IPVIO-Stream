package com.ipvio.stream

import android.app.ActivityManager
import android.content.Context
import androidx.media3.exoplayer.DefaultLoadControl
import coil.Coil

/**
 * Adaptation to the device's working memory. Many TV boxes and sticks have only 1 GB RAM:
 * there the image cache and player buffer are kept smaller so the app is not ended by the system.
 * Tiers: 0 = low (< 1.5 GB), 1 = medium (< 3 GB), 2 = high.
 */
object Mem {
    private var cachedTier = -1

    fun totalMb(ctx: Context): Long {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0L
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.totalMem / (1024 * 1024)
    }

    fun tier(ctx: Context): Int {
        if (cachedTier >= 0) return cachedTier
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mb = totalMb(ctx)
        cachedTier = tierFor(mb, am?.isLowRamDevice == true)
        return cachedTier
    }

    fun tierFor(totalMb: Long, lowRamFlag: Boolean): Int = when {
        lowRamFlag || (totalMb in 1..1536) -> 0
        totalMb in 1..3072 -> 1
        else -> 2
    }

    /** Share of the working memory for the image cache (Coil). */
    fun imageCachePercent(tier: Int): Double = when (tier) { 0 -> 0.08; 1 -> 0.13; else -> 0.18 }

    /** Buffer sizes for the player: (min ms, max ms, start ms, rebuffer ms, byte limit). */
    fun bufferPlan(tier: Int): IntArray = when (tier) {
        // After a stall playback resumes only with a few seconds in the buffer (rebuffer ms); too little means stall, play 1 s, stall again
        0 -> intArrayOf(10_000, 25_000, 1_500, 5_000, 14 * 1024 * 1024)
        1 -> intArrayOf(20_000, 40_000, 2_000, 6_000, 28 * 1024 * 1024)
        else -> intArrayOf(30_000, 60_000, 2_500, 8_000, -1)
    }

    fun loadControl(ctx: Context): DefaultLoadControl {
        val p = BufferLogic.scale(bufferPlan(tier(ctx)), BufferLogic.level(AppSettings.bufferMode, AppSettings.bufferLearned))
        val b = DefaultLoadControl.Builder().setBufferDurationsMs(p[0], p[1], p[2], p[3])
        val bytes = BufferLogic.byteLimit(p[4], Runtime.getRuntime().maxMemory())
        if (bytes > 0) b.setTargetBufferBytes(bytes).setPrioritizeTimeOverSizeThresholds(false)
        return b.build()
    }

    /** True if the app's Java memory is used by more than [limit] (background work then stops instead of crashing). */
    fun heapTight(limit: Double = 0.75): Boolean {
        val r = Runtime.getRuntime()
        return r.totalMemory() - r.freeMemory() > r.maxMemory() * limit
    }

    /** On memory shortage: empty everything that can be reloaded. */
    fun trim(ctx: Context) {
        runCatching { Coil.imageLoader(ctx).memoryCache?.clear() }
        runCatching { MetaRepo.trim() }
        runCatching { Epg.trim(); EpgFull.trim(); CreditsRepo.trim() }
    }
}

/**
 * Remembers the last crash (only the kind and the top stack lines, no message texts with addresses),
 * so it can be shown in the diagnostics area.
 */
object CrashLog {
    private const val PREFS = "ipvio_crash"

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                // Deepest cause, own frames first (else the first library frames), plus the message
                var root: Throwable = e
                while (root.cause != null && root.cause !== root) root = root.cause!!
                val own = root.stackTrace.filter { it.className.startsWith("com.ipvio") }.take(4)
                val frames = (own.ifEmpty { root.stackTrace.take(3).toList() })
                    .joinToString(" < ") { (it.fileName ?: it.className.substringAfterLast('.')) + ":" + it.lineNumber }
                val kind = root.javaClass.simpleName + (if (root is OutOfMemoryError) " (Speicher voll)" else "") +
                    (root.message?.take(140)?.let { ": $it" } ?: "")
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("last", kind + (if (frames.isNotEmpty()) " · " + frames else ""))
                    .putLong("at", System.currentTimeMillis()).putBoolean("shown", false).commit()
            }
            prev?.uncaughtException(t, e)
        }
        watchFreezes(app)
        lastExit(app)
    }

    /**
     * Android 11+: why the app's previous run ended. A kill for low memory, a freeze (ANR) or a native crash leaves
     * no Java stack trace, so it is saved here and shown like a crash on this start.
     */
    private fun lastExit(app: Context) {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        runCatching {
            val am = app.getSystemService(android.app.ActivityManager::class.java) ?: return
            val e = am.getHistoricalProcessExitReasons(app.packageName, 0, 1).firstOrNull() ?: return
            val sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (sp.getLong("exitSeen", 0L) == e.timestamp) return
            sp.edit().putLong("exitSeen", e.timestamp).apply()
            val why = when (e.reason) {
                android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "Closed by Android: low memory"
                android.app.ApplicationExitInfo.REASON_ANR -> "Closed by Android: app not responding"
                android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
                android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Closed by Android: too much resource use"
                else -> return
            }
            val mb = e.pss / 1024
            // Keep a freeze location saved by the watcher a moment before (it says where the screen was stuck)
            val prev = sp.getString("last", null)?.takeIf { it.startsWith("Freeze") && e.timestamp - sp.getLong("at", 0L) in 0..120_000 }
            sp.edit().putString("last", "$why (memory $mb MB" + (e.description?.take(80)?.let { ", $it" } ?: "") + ")" + (prev?.let { " · $it" } ?: ""))
                .putLong("at", e.timestamp).putBoolean("shown", false).commit()
        }
    }

    /**
     * A frozen screen is not a crash, so Android closes the app without a stack trace. This watcher pings the main
     * thread every 2 s; when it has not answered for 6 s it saves where the main thread is stuck (own frames only), so
     * the next start can show what froze. Saved once per freeze.
     */
    private fun watchFreezes(app: Context) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Thread {
            try { freezeLoop(app, main) } catch (_: InterruptedException) { }
        }.apply { name = "freeze-watch"; isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    private fun freezeLoop(app: Context, main: android.os.Handler) {
        run {
            var seen = 0L
            val tick = java.util.concurrent.atomic.AtomicLong(0)
            while (true) {
                val sent = tick.incrementAndGet()
                val at = android.os.SystemClock.uptimeMillis()
                main.post { tick.compareAndSet(sent, -sent) }
                Thread.sleep(2_000)
                var waited = android.os.SystemClock.uptimeMillis() - at
                while (tick.get() == sent && waited < 6_000) { Thread.sleep(500); waited = android.os.SystemClock.uptimeMillis() - at }
                if (tick.get() == sent && seen != sent) {
                    seen = sent
                    runCatching {
                        val st = android.os.Looper.getMainLooper().thread.stackTrace
                        val own = st.filter { it.className.startsWith("com.ipvio") }.take(5).ifEmpty { st.take(4) }
                        val where = own.joinToString(" < ") { (it.fileName ?: it.className.substringAfterLast('.')) + ":" + it.lineNumber }
                        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                            .putString("last", "Freeze (screen stuck 6 s) · $where")
                            .putLong("at", System.currentTimeMillis()).putBoolean("shown", false).commit()
                    }
                    while (tick.get() == sent) Thread.sleep(500)
                }
            }
        }
    }

    fun last(ctx: Context): Pair<String, Long>? {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val s = sp.getString("last", null) ?: return null
        return s to sp.getLong("at", 0L)
    }

    /** Last crash not shown yet and less than 30 minutes old; marks it as shown. */
    fun takeRecent(ctx: Context): String? {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (sp.getBoolean("shown", true)) return null
        val at = sp.getLong("at", 0L)
        sp.edit().putBoolean("shown", true).apply()
        if (System.currentTimeMillis() - at > 30 * 60_000L) return null
        return sp.getString("last", null)
    }
}
