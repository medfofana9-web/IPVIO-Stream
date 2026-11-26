package com.ipvio.stream

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.launch

/**
 * Test builds only (version name ends with "-test"): samples the main thread's stack every few milliseconds for a given
 * time and writes the hottest code to the log (tag "FofProfile"). The performance test triggers it with
 * `am broadcast -a com.fof.tv.PROFILE --ei ms 14000 --es tag home`. Release builds never register it.
 */
object MainSampler {
    private const val ACTION = "com.fof.tv.PROFILE"

    fun register(c: Context) {
        if (BuildConfig.VERSION_NAME.endsWith("-test")) {
            val german = Regex("[äöüßÄÖÜ]|\\b(der|die|das|und|nicht|ist|ein|eine|wird|kein|keine|Sender|Spiele|Lade|Filme|Serien|Suche|Fehler|anzeigen|öffnen|Sportarten|Einstellungen|Zurück|Weiter|Alle|Neu|Verlauf|Löschen)\\b")
            I18nCore.onMiss = { t -> if (german.containsMatchIn(t)) Log.w("FofUntranslated", t.take(160)) }
        }
        if (!BuildConfig.VERSION_NAME.endsWith("-test")) return
        // Test builds: Compose state written from a background thread (allowed, but the kind of code that crashed the
        // welcome screen, #63); logged once per place as "FofState" for the soak and playback tests
        val seenState = java.util.Collections.synchronizedSet(HashSet<String>())
        androidx.compose.runtime.snapshots.Snapshot.registerGlobalWriteObserver {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                val f = Thread.currentThread().stackTrace.firstOrNull { e -> e.className.startsWith("com.ipvio.stream") && !e.className.contains("MainSampler") }
                val at = f?.let { it.className.substringAfterLast('.') + "." + it.methodName + ":" + it.lineNumber } ?: "?"
                if (seenState.add(at)) Log.w("FofState", "state written off the main thread at " + at + " (" + Thread.currentThread().name + ")")
            }
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: Intent) { run(i.getIntExtra("ms", 10_000), i.getStringExtra("tag") ?: "run") }
        }
        // Journey test: open a tab directly (com.fof.tv.TAB --ei tab N)
        val t = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: Intent) {
                AppNav.testTab = i.getIntExtra("tab", -1)
                i.getStringExtra("sport")?.let { AppNav.testSport = it }
                i.getIntExtra("hubtab", -1).takeIf { it >= 0 }?.let { HubMemory.tab = it }
                // Search journey: type a query as if entered on the keyboard
                i.getStringExtra("q")?.let { SearchState.query = it }
                // Playback scenarios (playback-test.yml): add a fixture addon; remember a source as "played before";
                // resume a movie at a spot through the same path as Continue watching ("tt…|Title|year|ms")
                i.getStringExtra("addon")?.let { u ->
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                        Log.i("FofPlay", "addon " + u + ": " + (Stremio.add(ctx.applicationContext, u) ?: "added"))
                    }
                }
                i.getStringExtra("good")?.split('|', limit = 2)?.takeIf { it.size == 2 }?.let { (id, key) ->
                    val se = id.split(':')
                    val cid = if (se.size == 3) SourceMemory.contentId(se[0], true, se[1].toIntOrNull() ?: 0, se[2].toIntOrNull() ?: 0)
                        else SourceMemory.contentId(id, false, 0, 0)
                    SourceMemory.played(ctx.applicationContext, cid, key)
                }
                // "tt…|Title|year|ms" (movie) or "tt…|Title|year|ms|season|episode" (episode)
                i.getStringExtra("resume")?.split('|')?.takeIf { it.size == 4 || it.size == 6 }?.let { p ->
                    val ep = if (p.size == 6) (p[4].toIntOrNull() ?: 0) to (p[5].toIntOrNull() ?: 0) else 0 to 0
                    val item = Item("stremio:" + p[0], if (p.size == 6) Kind.SERIES else Kind.MOVIE, p[1], p[2].toIntOrNull(), "", null, "Stremio")
                    Log.i("FofPlay", "scenario " + p[0] + (if (p.size == 6) ":" + ep.first + ":" + ep.second else "") + " resume at " + p[3] + " ms")
                    AppNav.autoStream = item.id
                    AppNav.autoResume = Triple(ep.first, ep.second, p[3].toLongOrNull() ?: 0L)
                    AppNav.autoResumeId = item.id
                    OpenTitle.req = item
                }
                // Live channel by its name in the test playlist (same path as a reminder notification)
                i.getStringExtra("live")?.let { name ->
                    val ch = PlaylistStore.channels.firstOrNull { it.kind == Kind.LIVE && it.title == name }
                    Log.i("FofPlay", "scenario live " + name + (if (ch == null) " (not in the playlist)" else ""))
                    if (ch != null) AppNav.openChannel = ch.id
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            c.registerReceiver(r, IntentFilter(ACTION), Context.RECEIVER_EXPORTED)
            c.registerReceiver(t, IntentFilter("com.fof.tv.TAB"), Context.RECEIVER_EXPORTED)
        } else { c.registerReceiver(r, IntentFilter(ACTION)); c.registerReceiver(t, IntentFilter("com.fof.tv.TAB")) }
    }

    @Volatile private var cardMarked = false

    /** Test builds: logs a moment in the startup ("profile picked") with the time since process start. */
    /** Speed of this run, seconds after process start, shown in Settings → diagnostics ("Speed"). */
    val timings = java.util.concurrent.CopyOnWriteArrayList<String>()

    private fun keep(what: String) {
        if (timings.size >= 20 || timings.any { it.endsWith(what) }) return
        val ms = android.os.SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()
        timings.add(String.format(java.util.Locale.US, "%.1f s  ", ms / 1000.0) + what)
    }

    fun mark(what: String) {
        keep(what)
        if (!BuildConfig.VERSION_NAME.endsWith("-test")) return
        Log.i("FofProfile", "[start] $what after " + (android.os.SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()) + " ms")
    }

    /** Test builds: logs once how long after process start the first poster card was composed (home usable). */
    fun firstCard() {
        if (cardMarked) return
        cardMarked = true
        keep("home: first poster")
        if (!BuildConfig.VERSION_NAME.endsWith("-test")) return
        val ms = android.os.SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()
        Log.i("FofProfile", "[start] first poster card composed after $ms ms")
    }

    private fun key(e: StackTraceElement) = e.className.substringAfterLast('.') + "." + e.methodName + (if (e.lineNumber > 0) ":" + e.lineNumber else "")

    /**
     * Report lag (release builds too): samples the main thread every 20 ms for [ms] while you keep using the TV and
     * hands back the busiest app code (where the time goes on this very TV). Observational only, low priority.
     */
    fun sample(ms: Int, done: (busyPct: Int, appTop: List<String>, appAll: List<String>, topFrame: List<String>) -> Unit) = Thread {
        val main = Looper.getMainLooper().thread
        val self = HashMap<String, Int>(); val appTop = HashMap<String, Int>(); val appAll = HashMap<String, Int>()
        var busy = 0; var idle = 0
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            val st = main.stackTrace
            if (st.isNotEmpty()) {
                if (st[0].methodName == "nativePollOnce") idle++ else {
                    busy++
                    self.merge(key(st[0]), 1, Int::plus)
                    st.firstOrNull { it.className.startsWith("com.ipvio.stream") }?.let { appTop.merge(key(it), 1, Int::plus) }
                    st.filter { it.className.startsWith("com.ipvio.stream") }.map { key(it) }.distinct().forEach { appAll.merge(it, 1, Int::plus) }
                }
            }
            Thread.sleep(20)
        }
        fun top(m: Map<String, Int>, n: Int) = m.entries.sortedByDescending { it.value }.take(n).map { "${it.value}x ${it.key}" }
        done(busy * 100 / (busy + idle).coerceAtLeast(1), top(appTop, 15), top(appAll, 15), top(self, 8))
    }.apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()

    fun run(ms: Int, tag: String) = Thread {
        val main = Looper.getMainLooper().thread
        val self = HashMap<String, Int>()
        val appTop = HashMap<String, Int>()
        val appAll = HashMap<String, Int>()
        var busy = 0; var idle = 0
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            val st = main.stackTrace
            if (st.isNotEmpty()) {
                if (st[0].methodName == "nativePollOnce") idle++ else {
                    busy++
                    self.merge(key(st[0]), 1, Int::plus)
                    st.firstOrNull { it.className.startsWith("com.ipvio.stream") }?.let { appTop.merge(key(it), 1, Int::plus) }
                    st.filter { it.className.startsWith("com.ipvio.stream") }.map { key(it) }.distinct().forEach { appAll.merge(it, 1, Int::plus) }
                }
            }
            // Profiling is observational: high-frequency, maximum-priority
            // sampling otherwise competes with frame rendering on emulators.
            Thread.sleep(10)
        }
        fun dump(name: String, m: Map<String, Int>, n: Int) {
            Log.i("FofProfile", "[$tag] -- $name (of $busy busy samples)")
            m.entries.sortedByDescending { it.value }.take(n).forEach { Log.i("FofProfile", "[$tag] ${it.value}  ${it.key}") }
        }
        Log.i("FofProfile", "[$tag] busy=$busy idle=$idle")
        dump("top frame", self, 30)
        dump("innermost app frame", appTop, 40)
        dump("app frames anywhere on the stack", appAll, 40)
    }.apply { isDaemon = true; priority = Thread.NORM_PRIORITY }.start()
}
