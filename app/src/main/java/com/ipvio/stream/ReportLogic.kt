package com.ipvio.stream

/**
 * Pure helpers for the automatic problem reports (tested in ReportLogicTest, no Android classes).
 *
 * A report is "<stable part> ‖ <details>". The stable part names the problem (it becomes the GitHub issue title and the
 * once-a-day key on the TV); the details change every time (durations, memory, last steps, raw stack frames for
 * retracing). The account table limits one row to [MAX] characters, so a long report is sent as a head row
 * "<stable> ‖#<id>" plus continuation rows "+#<id> <n> <text>", which tools/auto_reports.py joins again.
 */
object ReportLogic {
    const val MAX = 400
    const val SEP = " ‖ "
    private const val MAX_PARTS = 4

    fun join(stable: String, details: String): String = if (details.isBlank()) stable else stable + SEP + details.trim()

    /** The part before the details (the problem's name). */
    fun stable(kind: String): String = kind.substringBefore(SEP).substringBefore(" ‖#")

    fun clean(s: String): String = s.replace(Regex("https?://\\S+"), "<link>").replace(Regex("\\s+"), " ").trim()

    /** Rows to send for [full] (already cleaned); [id] ties continuation rows to the head (a few letters/digits). */
    fun split(full: String, id: String): List<String> {
        if (full.length <= MAX) return listOf(full)
        val stable = full.substringBefore(SEP)
        val details = full.substringAfter(SEP, "")
        val head = stable.take(MAX - id.length - 3) + " ‖#" + id
        if (details.isEmpty()) return listOf(full.take(MAX))
        val out = arrayListOf(head)
        var rest = details
        var n = 1
        while (rest.isNotEmpty() && n <= MAX_PARTS) {
            val prefix = "+#$id $n "
            val room = MAX - prefix.length
            out.add(prefix + rest.take(room))
            rest = rest.drop(room)
            n++
        }
        return out
    }

    /** Last steps before now, oldest first: "tab0 -95s > tab10 -40s > ok sports -12s". */
    fun steps(items: List<Pair<Long, String>>, nowMs: Long, max: Int = 12): String =
        items.takeLast(max).joinToString(" > ") { it.second + " -" + ((nowMs - it.first).coerceAtLeast(0) / 1000) + "s" }

    /** Kind of stream from its address shape only (the address itself is never sent). */
    fun sourceKind(url: String, live: Boolean): String {
        val u = url.lowercase().substringBefore('?')
        val path = u.substringAfter("://", u).substringAfter('/', "")
        val via = when {
            Regex("""^(live|movie|series|timeshift)/[^/]+/[^/]+/""").containsMatchIn(path) -> "xtream"
            Regex("""^[^/]+/[^/]+/\d+(\.\w+)?$""").matches(path) -> "xtream"
            "play_token" in url || "/ch/" in u -> "stalker"
            u.startsWith("magnet:") || "/stremio/" in u || "torrent" in u -> "addon"
            else -> "m3u"
        }
        val ext = u.substringAfterLast('/').substringAfterLast('.', "")
        val fmt = when (ext) {
            "m3u8" -> "hls"; "mpd" -> "dash"; "ts" -> "ts"; "mp4", "m4v" -> "mp4"; "mkv" -> "mkv"; "avi" -> "avi"
            else -> if ("m3u8" in u) "hls" else "?"
        }
        return (if (live) "live " else "vod ") + via + " " + fmt
    }

    /** Most common freeze locations: "4/7 A ; 2/7 B". */
    fun hot(samples: List<String>, max: Int = 3): String {
        val n = samples.size
        return samples.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(max)
            .joinToString(" ; ") { "${it.value}/$n ${it.key}" }
    }

    /** Share of slow frames per place: "detail 60% home 40%". */
    fun places(m: Map<String, Int>, max: Int = 3): String {
        val sum = m.values.sum().coerceAtLeast(1)
        return m.entries.sortedByDescending { it.value }.take(max).joinToString(" ") { "${it.key.ifEmpty { "main" }} ${it.value * 100 / sum}%" }
    }
}
