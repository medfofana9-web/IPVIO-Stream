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

    fun add(c: Context, ch: Item) { val l = list(c); if (l.none { it == "id:" + ch.id }) save(c, l + ("id:" + ch.id)) }
    fun remove(c: Context, pin: String) = save(c, list(c).filter { it != pin })

    /** Base name of a channel: no country prefix, no quality tags ("US: ESPN FHD" -> "espn"). */
    internal fun base(title: String): String {
        var t = title.lowercase().replace(Regex("^\\s*[a-z]{2,3}\\s*[:|\\-]\\s*"), "")
        t = t.replace(Regex("[\\[(].*?[\\])]"), " ")
        t = t.replace(Regex("\\b(fhd|uhd|hd|sd|4k|hevc|h265|1080p?|720p?|50fps|60fps)\\b"), " ")
        return t.replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
    }

    private fun score(title: String): Double {
        val t = title.lowercase()
        var s = 0.0
        if (Regex("^\\s*(us|usa)\\s*[:|\\-]").containsMatchIn(t)) s += 2
        if (Regex("\\b(fhd|1080)").containsMatchIn(t)) s += 1 else if (Regex("\\bhd\\b").containsMatchIn(t)) s += 0.5
        if (Regex("\\b(backup|raw|plus|alt)\\b").containsMatchIn(t)) s -= 1
        if (Regex("\\b(4k|uhd)\\b").containsMatchIn(t)) s -= 0.3
        return s
    }

    /** The playlist channel for one pin, or null when the playlist has none. */
    fun resolve(pin: String, live: List<Item>): Item? = when {
        pin.startsWith("id:") -> live.firstOrNull { it.id == pin.removePrefix("id:") }
        pin.startsWith("name:") -> {
            val want = base(pin.removePrefix("name:"))
            live.filter { base(it.title) == want }.maxByOrNull { score(it.title) }
        }
        else -> null
    }

    fun label(pin: String, ch: Item?): String =
        if (pin.startsWith("name:")) pin.removePrefix("name:") else ch?.title ?: "Channel"
}
