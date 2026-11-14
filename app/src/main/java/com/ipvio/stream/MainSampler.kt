package com.ipvio.stream

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Looper
import android.util.Log

/**
 * Test builds only (version name ends with "-test"): samples the main thread's stack every few milliseconds for a given
 * time and writes the hottest code to the log (tag "FofProfile"). The performance test triggers it with
 * `am broadcast -a com.fof.tv.PROFILE --ei ms 14000 --es tag home`. Release builds never register it.
 */
object MainSampler {
    private const val ACTION = "com.fof.tv.PROFILE"

    fun register(c: Context) {
        if (!BuildConfig.VERSION_NAME.endsWith("-test")) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: Intent) { run(i.getIntExtra("ms", 10_000), i.getStringExtra("tag") ?: "run") }
        }
        // Journey test: open a tab directly (com.fof.tv.TAB --ei tab N)
        val t = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: Intent) { AppNav.testTab = i.getIntExtra("tab", -1) }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            c.registerReceiver(r, IntentFilter(ACTION), Context.RECEIVER_EXPORTED)
            c.registerReceiver(t, IntentFilter("com.fof.tv.TAB"), Context.RECEIVER_EXPORTED)
        } else { c.registerReceiver(r, IntentFilter(ACTION)); c.registerReceiver(t, IntentFilter("com.fof.tv.TAB")) }
    }

    private fun key(e: StackTraceElement) = e.className.substringAfterLast('.') + "." + e.methodName + (if (e.lineNumber > 0) ":" + e.lineNumber else "")

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
            Thread.sleep(3)
        }
        fun dump(name: String, m: Map<String, Int>, n: Int) {
            Log.i("FofProfile", "[$tag] -- $name (of $busy busy samples)")
            m.entries.sortedByDescending { it.value }.take(n).forEach { Log.i("FofProfile", "[$tag] ${it.value}  ${it.key}") }
        }
        Log.i("FofProfile", "[$tag] busy=$busy idle=$idle")
        dump("top frame", self, 30)
        dump("innermost app frame", appTop, 40)
        dump("app frames anywhere on the stack", appAll, 40)
    }.apply { isDaemon = true; priority = Thread.MAX_PRIORITY }.start()
}
