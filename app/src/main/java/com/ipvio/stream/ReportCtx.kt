package com.ipvio.stream

import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/**
 * Context for the automatic problem reports: what the TV was doing when a problem happened. Only screen names, step
 * names, numbers and settings switches – never titles, channel names, addresses or logins.
 *
 * - Last steps: screen changes (seen every 500 ms by [FreezeWatch]), places opened inside a screen ([ReportPlace]) and
 *   player steps (OK, first picture, error), the last [N] of them with their age.
 * - Where: screen plus the place inside it ("tab1/detail").
 * - Memory and device: heap, native heap, picture cache, uptime, playlist size, display mode, a few settings.
 * - A memory snapshot every 30 s, so a close by Android for low memory (which leaves no stack) still shows what was
 *   going on just before ([CrashLog]).
 */
object ReportCtx {
    private const val N = 12
    private const val PREFS = "fof_reportctx"
    private val crumbs = ArrayDeque<Pair<Long, String>>()
    private val places = ArrayList<String>()
    @Volatile var place: String = ""
        private set
    /** Display mode and UI size, set once by [MainActivity] ("3840x2160@60 ui 1920x1080"). */
    @Volatile var display: String = ""
    private var lastScreen = ""
    // First snapshot 30 s after start, so the previous run's snapshot is still there when [CrashLog] reads why it ended
    private var lastSnap = System.currentTimeMillis()

    fun crumb(s: String) {
        synchronized(crumbs) {
            crumbs.addLast(System.currentTimeMillis() to s.take(32))
            while (crumbs.size > N) crumbs.removeFirst()
        }
    }

    fun steps(max: Int = N): String = synchronized(crumbs) { ReportLogic.steps(crumbs.toList(), System.currentTimeMillis(), max) }

    fun enter(name: String) { synchronized(places) { places.add(name); place = name }; crumb("open $name") }
    fun leave(name: String) {
        synchronized(places) { val i = places.lastIndexOf(name); if (i >= 0) places.removeAt(i); place = places.lastOrNull().orEmpty() }
        crumb("close $name")
    }

    fun where(): String = FrameStats.currentScreen() + (place.takeIf { it.isNotEmpty() }?.let { "/$it" } ?: "")

    fun settings(): String = "lite " + (if (Lite.on) "on" else "off") + " motion " + Look.motion + " clean " + (if (FofPrefs.cleanLook) "on" else "off")

    fun mem(c: Context): String = runCatching {
        val rt = Runtime.getRuntime()
        val mb = 1024 * 1024
        val heap = (rt.totalMemory() - rt.freeMemory()) / mb
        val native = android.os.Debug.getNativeHeapAllocatedSize() / mb
        val img = coil.Coil.imageLoader(c).memoryCache?.let { " img " + it.size / mb + "/" + it.maxSize / mb + "MB" } ?: ""
        val up = if (Build.VERSION.SDK_INT >= 24) (SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()) / 60_000 else -1
        "heap $heap/${rt.maxMemory() / mb}MB native ${native}MB$img · up ${up}m · ram ${Mem.totalMb(c)}MB tier ${Mem.tier(c)} · channels ${PlaylistStore.channels.size}"
    }.getOrDefault("")

    /** Everything at once, for a details part. */
    fun all(c: Context): String = listOf("on " + where(), mem(c), settings(), display, "steps: " + steps())
        .filter { it.isNotBlank() }.joinToString(" · ")

    fun initDisplay(a: android.app.Activity) = runCatching {
        val dm = a.resources.displayMetrics
        @Suppress("DEPRECATION") val d = a.windowManager.defaultDisplay
        val mode = if (Build.VERSION.SDK_INT >= 23) d.mode.let { "${it.physicalWidth}x${it.physicalHeight}@${Math.round(it.refreshRate)}" } else "@" + Math.round(d.refreshRate)
        display = "$mode ui ${dm.widthPixels}x${dm.heightPixels}"
    }

    /** Every 500 ms from the freeze watch thread: screen changes become steps; a memory snapshot every 30 s. */
    fun tick(c: Context) {
        val s = runCatching { FrameStats.currentScreen() }.getOrDefault("")
        if (s.isNotEmpty() && s != lastScreen) { lastScreen = s; crumb(s) }
        val now = System.currentTimeMillis()
        if (now - lastSnap >= 30_000) {
            lastSnap = now
            runCatching {
                c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("snap", "on " + where() + " · " + mem(c) + " · steps: " + steps(6)).putLong("snap_at", now).apply()
            }
        }
    }

    /** The last snapshot, if it was taken within [maxAgeMs] before [at] (a close by Android). */
    fun snapshotBefore(c: Context, at: Long, maxAgeMs: Long = 120_000): String? {
        val sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val t = sp.getLong("snap_at", 0L)
        return sp.getString("snap", null)?.takeIf { at - t in 0..maxAgeMs }
    }
}

/** Marks a place inside a screen (title page, settings, search) for the reports while it is shown. */
@Composable
fun ReportPlace(name: String) {
    DisposableEffect(name) { ReportCtx.enter(name); onDispose { ReportCtx.leave(name) } }
}
