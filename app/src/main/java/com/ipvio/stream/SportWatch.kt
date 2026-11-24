package com.ipvio.stream

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * Watch Live: connects an ESPN event with the channels of your playlist. ESPN delivers no streams; the channel comes only from
 * your own channel list, in this order:
 *  1. chosen by hand ("Assign channel", [SportFix]),
 *  2. TV guide: a show on one of your channels around the start time whose title names both teams (or the race / tournament),
 *  3. channel name: the channel itself names both teams (event channels of some providers).
 * No URL is ever built; playing uses the normal player.
 */
object SportWatch {
    /** [why]: 0 = chosen by hand, 1 = TV guide, 2 = channel name, 3 = network, 4 = team channel, 5 = learned. [show] = detail text. */
    class Hit(val ch: Item, val why: Int, val show: String) {
        /** Only event-specific evidence or a manual assignment may trigger automatic playback. */
        val autoEligible: Boolean get() = why == 0 || why == 1 || why == 2 || why == 5
    }

    /**
     * Play order: a hand pick, then the network ESPN lists for the game (the actual broadcaster – the card shows it),
     * then event channels naming both teams, then TV guide matches (a guide entry can be wrong: a channel listed the
     * game but showed another match), learned picks, team channels, guesses.
     */
    private fun prio(why: Int) = when (why) { 0 -> 0; 2 -> 1; 1 -> 2; 5 -> 3; 3 -> 4; 4 -> 5; else -> 6 }

    /** Sports shows from the TV guide (now and the next 48 h), computed by the hub once a minute. */
    @Volatile var slots: List<SportLogic.Slot> = emptyList()
        private set
    var version by mutableIntStateOf(0)
        private set

    fun setSlots(s: List<SportLogic.Slot>) { slots = s.sortedBy { it.prog.start }; version++ }

    /** Guide slots that start between [lo] and [hi] (slots are sorted by start, so this stays fast with a 30-hour guide). */
    private fun slotsBetween(lo: Long, hi: Long): List<SportLogic.Slot> {
        val all = slots
        var a = 0; var b = all.size
        while (a < b) { val m = (a + b) ushr 1; if (all[m].prog.start < lo) a = m + 1 else b = m }
        var e = a
        while (e < all.size && all[e].prog.start <= hi) e++
        return all.subList(a, e)
    }

    private val RACE = Regex("""formel\s?1|formula\s?1|\bf1\b|grand\s?prix""", RegexOption.IGNORE_CASE)
    private val GOLF = Regex("""\bgolf\b|\bpga\b""", RegexOption.IGNORE_CASE)
    private val UFC = Regex("""\bufc\b""", RegexOption.IGNORE_CASE)

    /** Does a show text belong to this event? */
    fun showMatches(g: SportData.Game, text: String): Boolean = when (SportData.kindOf(g)) {
        SportData.KIND_RACE -> RACE.containsMatchIn(text)
        SportData.KIND_GOLF -> GOLF.containsMatchIn(text)
        SportData.KIND_FIGHT -> UFC.containsMatchIn(text) || SportData.matches(g, text)
        else -> SportData.matches(g, text)
    }

    private val cache = HashMap<String, Pair<Int, List<Hit>>>()

    // Event channels ("DAZN 3: Arsenal – Spurs") are sports channels: matching names only there keeps big playlists fast
    private var sportSig = 0
    private var sportList: List<Item> = emptyList()
    private fun sportChannels(live: List<Item>): List<Item> {
        val sig = System.identityHashCode(live) * 31 + live.size
        synchronized(this) {
            if (sig != sportSig) { sportList = live.filter { SportLogic.isSportChannel(it) }; sportSig = sig }
            return sportList
        }
    }

    // Channels whose name looks like an event ("Fubo 03: Spain vs England", "ViX 12 | España - Inglaterra")
    private var evSig = 0
    private var evList: List<Item> = emptyList()
    private val EVENT_NAME = Regex("""\b(vs\.?|v|at|@|-|–)\b|\|.*\S""", RegexOption.IGNORE_CASE)
    private fun eventChannels(live: List<Item>): List<Item> {
        val sig = System.identityHashCode(live) * 31 + live.size
        synchronized(this) {
            if (sig != evSig) { evList = live.filter { it.kind == Kind.LIVE && it.title.length > 12 && EVENT_NAME.containsMatchIn(it.title) }; evSig = sig }
            return evList
        }
    }

    /** Prepares the list of sports channels in the background (otherwise the first opening of the hub filters all channels on the main thread). */
    fun prewarm(live0: List<Item>) {
        val live = SportFresh.apply(live0)
        sportChannels(live)
        val sig = sigOf(live)
        if (prep?.sig != sig) build(live, sig)?.let { prep = it; prepVersion++ }
    }

    /**
     * Prepared lookups per channel list: channels by cleaned network name ("US| ESPN HD" → "espn", plus a looser form)
     * and the sports channels with their cleaned title and group (for team channels). Built once per list, off the main thread.
     */
    private class Prep(val sig: Int, val net: Map<String, List<Item>>, val team: List<Triple<Item, String, String>>)
    @Volatile private var prep: Prep? = null
    @Volatile private var building = 0
    var prepVersion by mutableIntStateOf(0)
        private set
    private val worker by lazy { java.util.concurrent.Executors.newSingleThreadExecutor() }
    private fun sigOf(live: List<Item>) = System.identityHashCode(live) * 31 + live.size

    private fun build(live: List<Item>, sig: Int): Prep? = runCatching {
        val m = HashMap<String, MutableList<Item>>()
        for (c in live) if (c.kind == Kind.LIVE && !SportNet.foreign(c.title, c.group)) {
            val t = SportNet.stripPrefix(c.title)
            m.getOrPut(SportNet.key(t)) { ArrayList(2) }.add(c)
            m.getOrPut("~" + SportNet.looseKey(t)) { ArrayList(2) }.add(c)
        }
        val team = sportChannels(live).map { c -> Triple(c, " " + SportNet.norm(SportNet.stripPrefix(c.title)) + " ", " " + SportNet.norm(c.group) + " ") }
        Prep(sig, m, team)
    }.getOrNull()

    /** Ready lookups for this list, or null while they are built in the background (then team and network matches wait). */
    private fun prepFor(live: List<Item>): Prep? {
        val sig = sigOf(live)
        prep?.let { if (it.sig == sig) return it }
        synchronized(this) {
            if (building != sig) {
                building = sig
                worker.execute { build(live, sig)?.let { prep = it; prepVersion++ } }
            }
        }
        return null
    }

    /** Team channels: sports channels whose name or group carries the league tag and name exactly one of the two teams. */
    private fun teamChannels(g: SportData.Game, pr: Prep): List<Pair<Item, Int>> {
        if (g.solo) return emptyList()
        val tags = SportNet.leagueTags(g.league).map { " $it " }
        if (tags.isEmpty()) return emptyList()
        val soccer = SportData.isSoccer(g)
        // Soccer (like the Mac app): only a club's full or short name, never a city word ("Manchester"), and only a channel
        // that is just the team ("Napoli TV"), never an event channel of another match ("Porto - Napoli", "AEK - Real Madrid")
        val hk = (if (soccer) SportData.soccerTeamKeys(g.home) else SportData.keysCached(g.home)).filter { it.length >= 3 }.map { " $it " }
        val ak = (if (soccer) SportData.soccerTeamKeys(g.away) else SportData.keysCached(g.away)).filter { it.length >= 3 }.map { " $it " }
        val out = ArrayList<Pair<Item, Int>>()
        for ((c, title, group) in pr.team) {
            if (soccer && (EVENT_NAME.containsMatchIn(c.title) || c.title.contains(':') || title.trim().length > maxOf(g.home.name.length, g.away.name.length) + 12)) continue
            if (tags.none { group.contains(it) || title.contains(it) }) continue
            val h = hk.any { title.contains(it) }
            val a = ak.any { title.contains(it) }
            if (h != a) out.add(c to (if (h) 1 else 2))
        }
        // Favorite team first, otherwise the home team (the home broadcast is usually the main one)
        val favAway = SportFavs.has(g.away) && !SportFavs.has(g.home)
        return out.sortedBy { if (favAway) (if (it.second == 2) 0 else 1) else (if (it.second == 1) 0 else 1) }
    }

    /** Words a provider uses for a soccer competition's own section ("Live | UEFA Champions League", "Live | EPL", "Live | La Liga"). */
    private val SECTION = mapOf(
        "Premier League" to listOf("premier league", "epl", "english premier"),
        "Champions League" to listOf("champions league", "ucl"),
        "Europa League" to listOf("europa league", "uel"),
        "Conference League" to listOf("conference league", "europa conf", "uecl"),
        "LaLiga" to listOf("la liga", "laliga"),
        "Serie A" to listOf("serie a"),
        "Bundesliga" to listOf("bundesliga"),
        "2. Bundesliga" to listOf("2 bundesliga", "bundesliga 2"),
        "Ligue 1" to listOf("ligue 1", "ligue1"),
        "MLS" to listOf("mls"),
        "Liga MX" to listOf("liga mx"),
        "FA Cup" to listOf("fa cup"),
        "Nations League" to listOf("nations league"),
        "International Friendlies" to listOf("friendly", "friendlies"),
        "Club World Cup" to listOf("club world cup")
    )

    /**
     * Soccer: 0 = a channel of the provider's section for this competition that shows the game (sure match by name or guide),
     * so "Live | UEFA Champions League" feeds come before every other source; 1 = anything else.
     */
    private fun sectionRank(g: SportData.Game, h: Hit): Int {
        if (h.why != 2 && h.why != 1 && h.why != 0) return 1
        if (!SportData.isSoccer(g)) return 1
        val words = SECTION[g.league] ?: listOf(SportNet.norm(g.league)).filter { it.length >= 3 }
        val grp = " " + SportNet.norm(h.ch.group) + " "
        val ttl = " " + SportNet.norm(h.ch.title) + " "
        // "Bundesliga" must not take "2. Bundesliga" feeds and the other way round
        val other2 = g.league == "Bundesliga" && (grp.contains(" 2 bundesliga ") || ttl.contains(" 2 bundesliga "))
        return if (!other2 && words.any { w -> grp.contains(" $w ") || ttl.contains(" $w ") }) 0 else 1
    }

    /** 0 = a channel of a network listed for the game that is sure to show it (event feed naming both teams, or the network itself). */
    private fun netRank(g: SportData.Game, h: Hit): Int {
        if (g.nets.isEmpty()) return 1
        if (h.why == 3) return 0
        if (h.why != 2 && h.why != 1) return 1
        val ck = SportNet.key(SportNet.stripPrefix(h.ch.title))
        return if (SportNet.keys(g.nets).any { k -> k.length >= 3 && ck.contains(k) }) 0 else 1
    }

    private val FEED_NO = Regex("""^\d{1,3}$""")
    private val feedCache = HashMap<String, Pair<Int, List<Item>>>()
    /** "Paramount+ 01" … "Paramount+ 40": live channels that are the network plus only a feed number. */
    private fun netFeeds(live: List<Item>, net: String): List<Item> {
        val k = SportNet.key(net)
        if (k.length < 3) return emptyList()
        val sig = sigOf(live)
        synchronized(feedCache) { feedCache[k]?.let { if (it.first == sig) return it.second } }
        val r = live.filter { c ->
            if (c.kind != Kind.LIVE || SportNet.foreign(c.title, c.group)) return@filter false
            val ck = SportNet.key(SportNet.stripPrefix(c.title))
            ck.startsWith(k) && FEED_NO.matches(ck.removePrefix(k))
        }.take(40)
        synchronized(feedCache) { if (feedCache.size > 40) feedCache.clear(); feedCache[k] = sig to r }
        return r
    }

    /**
     * "Other streams" in the player: the sure matches (like the Mac app), then the numbered feeds of the game's network.
     * Never channels of other matches.
     */
    fun pickerChannels(g: SportData.Game, live: List<Item>): List<Item> =
        resolve(g, live).filter { it.autoEligible || it.why == 3 || it.why == 4 || it.show.endsWith(" feed") }.map { it.ch }

    fun resolve(g: SportData.Game, live0: List<Item>): List<Hit> {
        // Today's channel names from the provider (event channels are renamed every day)
        val live = SportFresh.apply(live0)
        val fixed = SportFix.map[SportFix.key(g)]
        val learned = SportLearn.lookup(g)
        val pr = prepFor(live)
        // Recalculate when addon results, TV networks or game state change.
        // Otherwise an early empty result can remain cached after a match airs.
        val fullSig = listOf(
            System.identityHashCode(live), live.size, version,
            SportAddons.version, SportAddons.cached(g).size,
            fixed?.hashCode() ?: 0, learned.hashCode(), SportLearn.version,
            if (pr != null) 1 else 0,
            g.nets.hashCode(), g.tv.hashCode(), g.state,
            g.start.hashCode(), g.end.hashCode()
        ).fold(17) { acc, n -> acc * 31 + n }
        synchronized(cache) { cache[g.key]?.let { if (it.first == fullSig) return it.second } }
        val out = ArrayList<Hit>()
        if (fixed != null) live.firstOrNull { it.id == fixed }?.let { out.add(Hit(it, 0, "")) }
        val soccer = SportData.isSoccer(g)
        learned.forEach { id -> live.firstOrNull { it.id == id }?.let { ch ->
            // Soccer: a channel picked for another match (one network, many games at once) is no proof for this one
            val sure = !soccer || SportData.matches(g, ch.title) ||
                SportNet.teamOf(ch.title, SportData.soccerTeamKeys(g.home), SportData.soccerTeamKeys(g.away)) != 0
            out.add(if (sure) Hit(ch, 5, "") else Hit(ch, 6, "Picked before for another game"))
        } }
        val sport = sportChannels(live)
        // Event channels that name both teams ("NBA 01: Pacers vs Celtics") are the most exact
        SportData.channelsFor(g, sport).forEach { out.add(Hit(it, 2, "")) }
        SportData.channelsFor(g, eventChannels(live)).forEach { out.add(Hit(it, 2, "")) }
        val multiDay = g.end > g.start
        for (s in slotsBetween(g.start - 90 * 60, if (multiDay) maxOf(g.end, g.start + 90 * 60) else g.start + 90 * 60)) {
            val nearStart = kotlin.math.abs(s.prog.start - g.start) <= 90 * 60 ||
                (multiDay && s.prog.start in g.start..g.end && g.state != 2)
            if (!nearStart) continue
            val exact = SportData.matches(g, s.prog.title)
            // A running game: an entry naming both teams counts even when the guide starts it later than the real kickoff
            // (the provider listed Napoli @ Manchester City at 4:00 for a 3:00 game); other entries must be on now
            if (g.state == 1 && !s.live && !(exact && s.prog.start - g.start in 0..90 * 60)) continue
            if (exact) out.add(Hit(s.ch, 1, s.prog.title))
            else if (showMatches(g, s.prog.title)) out.add(Hit(s.ch, 6, s.prog.title))
        }
        // Streams of the game from the addons (Pengu …): exact, so before the network guesses
        SportAddons.cached(g).forEach { out.add(Hit(it, 2, "Addon")) }
        if (pr != null) {
            teamChannels(g, pr).forEach { (c, t) -> out.add(Hit(c, 4, if (t == 1) g.home.name else g.away.name)) }
            // ESPN often lists no TV for games it does not carry itself (e.g. USA vs Haiti on TBS/truTV): then the usual US
            // networks of that competition are tried, so the game is never "no channel found" while it is on TV
            // Networks ESPN lists for this game count as a match; the usual rights holders are only a guess (manual)
            for (n in g.nets.distinct()) (pr.net[SportNet.key(n)] ?: pr.net["~" + SportNet.looseKey(n)])?.forEach { out.add(Hit(it, 3, n)) }
            // Numbered feeds of a listed network ("US| Paramount+ 03") without a match name: may show any of that night's games,
            // so they are offered (right after the sure matches), not auto-played
            for (n in g.nets.distinct()) netFeeds(live, n).forEach { out.add(Hit(it, 6, "$n feed")) }
            for (n in SportRights.usual(g).filter { it !in g.nets }.distinct()) (pr.net[SportNet.key(n)] ?: pr.net["~" + SportNet.looseKey(n)])?.forEach { out.add(Hit(it, 6, "Usually on $n")) }
            // Still nothing: the networks that carry this sport in the US (tried in order by the player)
            if (out.none { it.autoEligible }) for (n in SportRights.fallback(g)) (pr.net[SportNet.key(n)] ?: pr.net["~" + SportNet.looseKey(n)])?.forEach { out.add(Hit(it, 6, "Usually on $n")) }
        }
        // Working channels before ones marked broken; the rest of the list is the backup order for the player
        // The network on the game card (Paramount+) is tried first: its event feed for this game, then the network channel
        // Soccer: the provider's section for the competition first, then the listed network (Paramount+), then the rest
        val r = out.sortedWith(compareBy<Hit>({ if (it.autoEligible) 0 else 1 }, { prio(it.why) }, { sectionRank(g, it) }, { netRank(g, it) }))
            .distinctBy { it.ch.id }.sortedBy { if (DeadLinks.isDead(it.ch.url)) 1 else 0 }
        synchronized(cache) { if (cache.size > 800) cache.clear(); cache[g.key] = fullSig to r }
        return r
    }

    /**
     * Automatic playback and recovery use event-specific evidence or a channel assigned for this game.
     * Network, team and learned suggestions remain available on the manual Watch Live page.
     */
    fun channels(g: SportData.Game, live0: List<Item>): List<Item> {
        val live = SportFresh.apply(live0)
        val hits = resolve(g, live).filter { it.autoEligible }
        val c = SportSession.appCtx()
        return hits.sortedWith(compareBy<Hit>(
            { if (DeadLinks.isDead(it.ch.url)) 1 else 0 },
            { prio(it.why) },
            { sectionRank(g, it) },
            { netRank(g, it) },
            { if (c != null) -FeedScore.score(c, it.ch.id) else 0 }
        )).map { it.ch }
    }
}

/** Watch Live page: the event, all matching channels with reason and availability, play, assign by hand. */
@Composable
internal fun WatchPage(g0: SportData.Game, hub: HubCtx) {
    val ctx = LocalContext.current
    SportFix.init(ctx)
    val g = SportFeed.latest[g0.key] ?: g0
    val prepVersion = SportWatch.prepVersion
    val addonVersion = SportAddons.version
    val epgVersion = SportWatch.version
    var addonPending by remember(g.key) { mutableStateOf(false) }
    var retry by remember(g.key) { mutableIntStateOf(0) }
    // Matching a game against thousands of channels must never happen in
    // composition. Until the first lookup is complete show an actual
    // searching state, not the misleading "no matching channel" message.
    val freshVersion = SportFresh.version
    val hitsLoading by produceState<List<SportWatch.Hit>?>(null, g.key, g.nets, hub.live, prepVersion, addonVersion, epgVersion, freshVersion) {
        if (SportFresh.stale()) withTimeoutOrNull(4_000L) { SportFresh.refresh() }
        value = withContext(Dispatchers.Default) {
            SportWatch.prewarm(hub.live)
            SportWatch.resolve(g, hub.live)
        }
    }
    val hits = hitsLoading.orEmpty()
    val automatic = hits.filter { it.autoEligible }
    LaunchedEffect(g.key, g.state, retry, automatic.isNotEmpty()) {
        if (g.state == 2 || (automatic.isNotEmpty() && retry == 0)) {
            addonPending = false
            return@LaunchedEffect
        }
        var refresh = retry > 0
        do {
            addonPending = true
            try {
                withTimeoutOrNull(5_000L) { SportAddons.find(ctx, g, refresh) }
            } finally { addonPending = false }
            if (automatic.isNotEmpty() || g.state != 1) break
            delay(12_000L)
            refresh = true
        } while (true)
    }
    val searching = hitsLoading == null || (automatic.isEmpty() && addonPending)
    LaunchedEffect(g.key, searching, automatic.isEmpty()) {
        if (g.state == 1 && !searching && automatic.isEmpty() && SportAddons.report(g) != null) {
            FrameStats.incident(ctx, FrameStats.Incident.SPORTS_NO_MATCH)
        }
    }
    var pick by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    HubPageFrame("Live ansehen", g.groupName, { hub.nav.pop() }, first, rawTitle = false) {
        HubPanel(Modifier.fillMaxWidth()) {
            MatchHeader(g, 90)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(12))) {
                if (automatic.isNotEmpty() && g.state != 2) HeroButton("Live ansehen", AIcon.Play, true, Modifier, 46) { hub.watch(g) }
                // Finished game: play it from the provider's archive (catch-up) on the matched channel, when the provider offers it
                val replay = remember(g.key, g.state, hits) {
                    if (g.state != 2) null else {
                        val now = System.currentTimeMillis() / 1000
                        val e = EpgEntry(if (g.solo) g.title else g.away.short + " at " + g.home.short, "", g.start - 300, minOf(g.start + 4 * 3600, now - 60))
                        automatic.firstNotNullOfOrNull { h -> Catchup.urlFor(h.ch, e)?.let { u -> h.ch.copy(url = u, title = "Replay: " + e.title) } }
                    }
                }
                if (replay != null) HeroButton("Watch replay", AIcon.Catchup, true, Modifier, 46) { hub.play(replay, listOf(replay)) }
                // Joined late: start the running game from the beginning (provider archive)
                val fromStart = remember(g.key, g.state, hits) {
                    if (g.state != 1) null else {
                        val now = System.currentTimeMillis() / 1000
                        val e = EpgEntry(if (g.solo) g.title else g.away.short + " at " + g.home.short, "", g.start - 300, now - 60)
                        automatic.firstNotNullOfOrNull { h -> Catchup.urlFor(h.ch, e)?.let { u -> h.ch.copy(url = u, title = "From the start: " + e.title) } }
                    }
                }
                if (fromStart != null) FilterPill("Watch from the start", false, Modifier.trackFocus(), aicon = AIcon.Rewind) { hub.play(fromStart, listOf(fromStart)) }
                // Upcoming: reminder switches the TV to the game when it starts
                if (g.state == 0) {
                    GameDay.init(ctx)
                    val on = GameDay.has(g)
                    FilterPill(if (on) "Auto-tune on" else "Auto-tune at start", on, Modifier.trackFocus(), aicon = AIcon.Bell) { GameDay.toggle(ctx, g) }
                }
                FilterPill("Choose an IPTV channel", false, Modifier.focusRequester(first), aicon = AIcon.Edit) { pick = true }
                if (g.state != 2) FilterPill("Retry sources", false, Modifier.trackFocus(), aicon = AIcon.Refresh) { retry++ }
                if (SportFix.map.containsKey(SportFix.key(g))) FilterPill("Automatisch zuordnen", false, Modifier.trackFocus(), aicon = AIcon.Refresh) { SportFix.set(ctx, g, null) }
                FilterPill("Mehr Infos", false, Modifier.trackFocus(), aicon = AIcon.Info) { hub.nav.open(HubPage.Match(g)) }
            }
        }
        val reports = SportAddons.report(g)
        Text(
            "IPTV: " + when {
                hub.live.isEmpty() -> "no channels loaded"
                hitsLoading == null -> "checking guide and channel names"
                automatic.any { it.show != "Addon" } -> "matched"
                else -> "no confirmed feed"
            } + (reports?.let { "\\n" + it } ?: ""),
            color = Muted, fontSize = spx(14), raw = true, modifier = Modifier.padding(vertical = dpx(8))
        )
        if (searching) HubLoading("Still checking sources…")
        else if (hits.isEmpty()) HubEmpty(
            AIcon.Live, "Choose a source",
            "The guide and add-ons haven’t confirmed a feed for this event. Retry sources or choose an IPTV channel."
        ) else LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = dpx(30)), verticalArrangement = Arrangement.spacedBy(dpx(4))) {
            itemsIndexed(hits, key = { _, h -> h.ch.id }) { _, h ->
                FRow({ hub.watchOn(g, h.ch, automatic.map { it.ch }) }) {
                    TeamLogo(h.ch.logo, h.ch.title.take(2), dpx(44))
                    Column(Modifier.weight(1f)) {
                        Text(raw = true, text = h.ch.title, color = Tok.TextHi, fontSize = spx(18), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val why = when (h.why) {
                            0 -> Lang.tr("Von dir zugeordnet")
                            1 -> Lang.tr("Laut TV-Guide") + ": " + h.show
                            3 -> "On " + h.show
                            4 -> "Team channel: " + h.show
                            5 -> "Your usual channel"
                            6 -> h.show + " · matchup unconfirmed"
                            else -> Lang.tr("Sendername passt")
                        }
                        Text(raw = true, text = why, color = Muted, fontSize = spx(14), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val dead = DeadLinks.isDead(h.ch.url)
                    val verified = !dead && SportSession.isVerified(ctx, h.ch.id)
                    Box(Modifier.size(dpx(10)).clip(RoundedCornerShape(50)).background(if (dead) DS.Gold else Accent2))
                    if (verified) Text(raw = true, text = "Played before", color = Accent2, fontSize = spx(15), fontWeight = FontWeight.Bold)
                    else Text(if (dead) "Gestört" else "Verfügbar", color = if (dead) DS.Gold else Accent2, fontSize = spx(15), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
    if (pick) ChannelPicker(g, hub.live, { pick = false }) { id ->
        SportFix.set(ctx, g, id)
        hub.live.firstOrNull { it.id == id }?.let { SportLearn.learn(ctx, g, it) }
        pick = false
    }
}

/** Choose a channel by hand (sports channels first, all channels if the playlist has none). */
@Composable
internal fun ChannelPicker(g: SportData.Game, live: List<Item>, onClose: () -> Unit, onPick: (String) -> Unit) {
    val pool = remember(live) { live.filter { SportLogic.isSportChannel(it) }.ifEmpty { live } }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.clip(RoundedCornerShape(DS.RadiusM)).background(Bg).border(1.dp, Tok.Line, RoundedCornerShape(DS.RadiusM)).padding(dpx(16)),
            verticalArrangement = Arrangement.spacedBy(dpx(8))
        ) {
            Text("Sender zuordnen", color = Tok.TextHi, fontSize = spx(20), fontWeight = FontWeight.Bold)
            Text(raw = true, text = if (g.solo) g.title else g.home.name + " – " + g.away.name, color = Muted, fontSize = spx(14), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (pool.isEmpty()) Text("Keine Sender in der Playlist.", color = Muted, fontSize = spx(15), modifier = Modifier.focusRequester(first))
            LazyColumn(Modifier.height(dpx(420)).width(dpx(520)), verticalArrangement = Arrangement.spacedBy(dpx(4))) {
                itemsIndexed(pool, key = { i, c -> c.id + "#" + i }) { i, c ->
                    FRow({ onPick(c.id) }, req = if (i == 0) first else null) {
                        TeamLogo(c.logo, c.title.take(2), dpx(30))
                        Text(raw = true, text = c.title, color = Color.White, fontSize = spx(16), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
