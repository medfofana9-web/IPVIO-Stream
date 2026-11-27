package com.ipvio.stream

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * Channels pinned at the top of the Sports page (ESPN and NBA TV to start). A pin is either a name ("name:ESPN",
 * matched against the playlist, best feed first) or a channel picked by hand ("id:<channel id>").
 */
object SportPins {
    private const val KEY = "pins"
    private val DEFAULT = listOf("name:ESPN", "name:NBA TV")
    var version by mutableIntStateOf(0)
        private set

    private fun prefs(c: Context) = Profiles.sp(c, "fof_sport")

    fun list(c: Context): List<String> {
        val raw = prefs(c).getString(KEY, null) ?: return DEFAULT
        return raw.split('\n').filter { it.isNotBlank() }
    }

    private fun save(c: Context, l: List<String>) {
        prefs(c).edit().putString(KEY, l.joinToString("\n")).apply()
        version++
    }

    /** Saved by name, so the pin finds the same channel on the Mac, the phone and other TVs (their channel ids differ). */
    fun add(c: Context, ch: Item) { val pin = "name:" + base(ch.title); val l = list(c); if (l.none { it.equals(pin, true) }) save(c, l + pin) }
    fun remove(c: Context, pin: String) = save(c, list(c).filter { it != pin })

    // Compiled once: resolve() runs these for every playlist channel (thousands), and compiling them per call froze the
    // Sports page (auto-reports #71, #73, #75: PatternNative.compileImpl in base()).
    private val RX_PREFIX = Regex("^\\s*[a-z]{2,3}\\s*[:|\\-]\\s*")
    private val RX_BRACKETS = Regex("[\\[(].*?[\\])]")
    private val RX_QUALITY = Regex("\\b(fhd|uhd|hd|sd|4k|hevc|h265|1080p?|720p?|50fps|60fps)\\b")
    private val RX_NON_ALNUM = Regex("[^a-z0-9 ]")
    private val RX_SPACES = Regex("\\s+")
    private val RX_US = Regex("^\\s*(us|usa)\\s*[:|\\-]")
    private val RX_FHD = Regex("\\b(fhd|1080)")
    private val RX_HD = Regex("\\bhd\\b")
    private val RX_ALT = Regex("\\b(backup|raw|plus|alt)\\b")
    private val RX_UHD = Regex("\\b(4k|uhd)\\b")

    /** Base name of a channel: no country prefix, no quality tags ("US: ESPN FHD" -> "espn"). */
    internal fun base(title: String): String {
        var t = title.lowercase().replace(RX_PREFIX, "")
        t = t.replace(RX_BRACKETS, " ")
        t = t.replace(RX_QUALITY, " ")
        return t.replace(RX_NON_ALNUM, " ").replace(RX_SPACES, " ").trim()
    }

    private fun score(title: String): Double {
        val t = title.lowercase()
        var s = 0.0
        if (RX_US.containsMatchIn(t)) s += 2
        if (RX_FHD.containsMatchIn(t)) s += 1 else if (RX_HD.containsMatchIn(t)) s += 0.5
        if (RX_ALT.containsMatchIn(t)) s -= 1
        if (RX_UHD.containsMatchIn(t)) s -= 0.3
        return s
    }

    private class Resolved(val live: List<Item>, val ver: Int, val pins: List<Pair<String, Item?>>)
    @Volatile private var lastResolved: Resolved? = null

    /** The last [resolveAll] result for this channel list (same object) and pin version, or null. Cheap on the main thread. */
    fun cached(live: List<Item>, ver: Int): List<Pair<String, Item?>>? =
        lastResolved?.takeIf { it.live === live && it.ver == ver }?.pins

    /** Every pin with its playlist channel. Scans the whole playlist, so call it off the main thread. */
    fun resolveAll(c: Context, live: List<Item>, ver: Int): List<Pair<String, Item?>> =
        list(c).map { it to resolve(it, live) }.also { lastResolved = Resolved(live, ver, it) }

    /** The playlist channel for one pin, or null when the playlist has none. */
    fun resolve(pin: String, live: List<Item>): Item? = when {
        pin.startsWith("id:") -> live.firstOrNull { it.id == pin.removePrefix("id:") }
        pin.startsWith("url:") -> live.firstOrNull { it.url == pin.removePrefix("url:") }
        pin.startsWith("name:") -> {
            val want = base(pin.removePrefix("name:"))
            live.filter { base(it.title) == want }.maxByOrNull { score(it.title) }
        }
        else -> null
    }

    fun label(pin: String, ch: Item?): String =
        if (pin.startsWith("name:")) pin.removePrefix("name:").let { n -> if (n == n.lowercase()) n.uppercase() else n } else ch?.title ?: "Channel"
}
