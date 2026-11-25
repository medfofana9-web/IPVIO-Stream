package com.ipvio.stream

/**
 * Event identity before playback reliability (pure, tested in SportGuardTest).
 *
 * Network, team and learned channels are only a *guess* that a channel carries a game. Before such a channel may start
 * automatically, its own name and its guide entry at kickoff are checked:
 *  - a placeholder ("No Game Today", "No Event", "Signing Off", "Off Air", "Data Not Available", "Live in 3 Hours") means
 *    the channel is not showing an event now;
 *  - a guide entry that names a different matchup ("Lakers vs Celtics" when the game is Pacers – Knicks) means the
 *    channel shows another game.
 * Either way the channel is still listed on Watch Live, but labeled unconfirmed and never auto-played. A channel that
 * plays fine but shows the wrong game counts as a failed match.
 */
object SportGuard {
    private val INACTIVE = Regex(
        """\b(no\s+(game|games|event|events|match|matches|live\s+event|broadcast|programming)(\s+(today|scheduled|now))?|""" +
            """signing\s+off|sign\s+off|off\s*air|offline|data\s+not\s+available|no\s+data|not\s+live|""" +
            """stand\s*by|coming\s+up\s+later|starts?\s+in\s+\d+|live\s+in\s+\d+\s*(h|hr|hrs|hour|hours|m|min|mins|minutes)\b|""" +
            """event\s+(ended|finished|over)|to\s+be\s+announced|\btba\b|\btbd\b)""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Matchup separators of a guide title naming two sides ("A vs B", "A v B", "A @ B"). Dashes and "at" are left out on
     * purpose: "NBA Basketball - Regular Season" or "SportsCenter at Night" are not another game.
     */
    private val VS = Regex("""\S.*\s(vs\.?|v\.?|@)\s.*\S""", RegexOption.IGNORE_CASE)

    /** Does this channel name or guide text say nothing is on (placeholder / off-air / later)? */
    fun inactive(text: String?): Boolean = !text.isNullOrBlank() && INACTIVE.containsMatchIn(text)

    /** Does a guide title name a matchup (two sides)? */
    fun namesMatchup(title: String?): Boolean = !title.isNullOrBlank() && VS.containsMatchIn(title.substringAfter(':').trim().ifBlank { title })

    /** Guide entry covering [atSec]; entries that ended before it (stale guide) are ignored. */
    fun entryAt(list: List<EpgEntry>, atSec: Long): EpgEntry? = list.firstOrNull { it.start <= atSec && it.end > atSec }

    /** Result of the identity check for a guessed channel. */
    enum class Verdict { OK, INACTIVE, OTHER_EVENT }

    /**
     * [channelTitle]: the channel's own name, [guide]: its guide entry at kickoff (null = no fresh guide data, nothing
     * to contradict), [isThisGame]: does a text name this game (both teams / the event).
     */
    fun check(channelTitle: String, guide: EpgEntry?, isThisGame: (String) -> Boolean): Verdict {
        if (inactive(channelTitle) && !isThisGame(channelTitle)) return Verdict.INACTIVE
        if (guide == null) return Verdict.OK
        val t = guide.title
        if (isThisGame(t) || isThisGame(guide.desc)) return Verdict.OK
        if (inactive(t)) return Verdict.INACTIVE
        if (namesMatchup(t)) return Verdict.OTHER_EVENT
        return Verdict.OK
    }
}
