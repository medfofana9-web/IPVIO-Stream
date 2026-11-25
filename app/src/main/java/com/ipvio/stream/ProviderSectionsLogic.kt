package com.ipvio.stream

/**
 * Provider sections in Sports (layout B, pure, tested in ProviderSectionsTest): the provider's own sports folders
 * ("USA | NBA Teams", "Live | UEFA Champions League") shown as they are, one tile per channel with its state taken from
 * the provider's guide ("Live in 3 hours: DET @ ORL", "No Game Today"). OK plays the channel – no matching involved.
 */
object ProviderSectionsLogic {
    /** Folders for basketball, American football and soccer (the sports FOF TV covers). */
    private val WANTED = Regex(
        """\b(nba|wnba|nfl|ncaa\s*f|ncaaf|college\s+football|epl|premier\s+league|efl|fa\s+cup|champions\s+league|""" +
            """europa|conference\s+league|uefa|la\s*liga|laliga|bundesliga|serie\s+a|ligue\s*1|mls|soccer|""" +
            """liga\s+mx|coupang|ncaa\s+men'?s\s+basketball)\b""",
        RegexOption.IGNORE_CASE
    )
    /** Not games: replays, radio, 24/7 loops, betting, VOD folders that share a league word. */
    private val SKIP = Regex("""\b(replay|replays|radio|24/7|betting|vod|series|movies|highlights|classics?)\b""", RegexOption.IGNORE_CASE)

    fun wanted(group: String): Boolean = WANTED.containsMatchIn(group) && !SKIP.containsMatchIn(group)

    /** Section order: NBA teams first, then NBA, WNBA, NFL teams, NFL, college, soccer; the rest by name. */
    fun rank(group: String): Int {
        val g = group.lowercase()
        val teams = g.contains("team")
        return when {
            Regex("""\bnba\b""").containsMatchIn(g) -> if (teams) 0 else if (g.contains("backup")) 2 else 1
            Regex("""\bwnba\b""").containsMatchIn(g) -> 3
            Regex("""\bnfl\b""").containsMatchIn(g) -> if (teams) 4 else if (g.contains("backup")) 6 else 5
            g.contains("ncaa") || g.contains("college") -> 7
            Regex("""\b(epl|premier league)\b""").containsMatchIn(g) -> 10
            g.contains("champions league") -> 11
            g.contains("europa") -> 12
            g.contains("la liga") || g.contains("laliga") -> 13
            g.contains("serie a") -> 14
            g.contains("bundesliga") -> 15
            g.contains("ligue") -> 16
            Regex("""\bmls\b""").containsMatchIn(g) -> 17
            else -> 30
        }
    }

    /** Short section name for the chip: "USA | NBA Teams" → "NBA Teams", "Live | UEFA Champions League" → "UEFA Champions League". */
    fun sectionName(group: String): String =
        group.substringAfterLast('|').replace(Regex("""[\p{So}\p{Cn}]"""), "").trim().ifBlank { group.trim() }

    private val PREFIX = Regex("""^\s*((usa|us|uk|ca|live)\s*[|:]\s*)?(nba|wnba|nfl|nhl|mlb)\s*[:|-]?\s+""", RegexOption.IGNORE_CASE)
    private val TAGS = Regex("""\s*[(\[]?\b(fhd|uhd|hd|sd|4k|hevc|h265|backup|raw)\b[)\]]?""", RegexOption.IGNORE_CASE)

    /** Tile name: "NBA DETROIT PISTONS (FHD)" → "Detroit Pistons"; names in mixed case stay as they are. */
    fun tileName(title: String): String {
        var t = title.replace(TAGS, "").trim()
        val stripped = t.replace(PREFIX, "").trim()
        if (stripped.isNotEmpty()) t = stripped
        if (t.any { it.isLetter() } && t == t.uppercase()) {
            t = t.lowercase().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
        }
        return t
    }

    enum class Kind { LIVE, SOON, UNKNOWN, LATER, NONE }
    data class Status(val kind: Kind, val text: String, val minutes: Int = 0)

    private val LIVE_IN = Regex("""live\s+in\s+(\d+)\s*(min(?:ute)?s?|h(?:ours?|rs?)?|days?)\b\s*:?\s*(.*)""", RegexOption.IGNORE_CASE)

    /**
     * State of a channel from its guide entry on now: "Live in 3 hours: DET @ ORL" → soon, "Live in 1 day: …" → later,
     * "No Game Today" / "No EVENT Today" / "Data not available" → no game, a real programme → live, no guide → unknown.
     */
    fun status(now: EpgEntry?): Status {
        val t = now?.title?.trim().orEmpty()
        if (t.isEmpty()) return Status(Kind.UNKNOWN, "")
        LIVE_IN.find(t)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: 0
            val unit = m.groupValues[2].lowercase()
            val what = m.groupValues[3].trim()
            val mins = when { unit.startsWith("d") -> n * 1440; unit.startsWith("h") -> n * 60; else -> n }
            val whenText = when {
                unit.startsWith("d") -> if (n == 1) "Tomorrow" else "In $n days"
                unit.startsWith("h") -> "In $n h"
                else -> "In $n min"
            }
            val kind = if (mins <= 12 * 60) Kind.SOON else Kind.LATER
            return Status(kind, if (what.isNotEmpty()) "$whenText · $what" else whenText, mins)
        }
        if (SportGuard.inactive(t)) return Status(Kind.NONE, "No game today")
        return Status(Kind.LIVE, t)
    }

    /** Live first, then starting soonest, then unknown, later, no game; equal states keep the provider's order. */
    fun order(states: List<Status>): List<Int> =
        states.indices.sortedWith(compareBy({ states[it].kind.ordinal }, { if (states[it].kind == Kind.SOON || states[it].kind == Kind.LATER) states[it].minutes else 0 }))
}
