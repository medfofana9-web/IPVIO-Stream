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
        val autoEligible: Boolean get() = why in 0..2
    }

    /** Sports shows from the TV guide (now and the next 48 h), computed by the hub once a minute. */
    @Volatile var slots: List<SportLogic.Slot> = emptyList()
        private set
    var version by mutableIntStateOf(0)
        private set

    fun setSlots(s: List<SportLogic.Slot>) { slots = s; version++ }

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
    fun prewarm(live: List<Item>) {
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
        for (c in live) if (c.kind == Kind.LIVE) {
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
        val hk = SportData.keysCached(g.home).filter { it.length >= 3 }.map { " $it " }
        val ak = SportData.keysCached(g.away).filter { it.length >= 3 }.map { " $it " }
        val out = ArrayList<Pair<Item, Int>>()
        for ((c, title, group) in pr.team) {
            if (tags.none { group.contains(it) || title.contains(it) }) continue
            val h = hk.any { title.contains(it) }
            val a = ak.any { title.contains(it) }
            if (h != a) out.add(c to (if (h) 1 else 2))
        }
        // Favorite team first, otherwise the home team (the home broadcast is usually the main one)
        val favAway = SportFavs.has(g.away) && !SportFavs.has(g.home)
        return out.sortedBy { if (favAway) (if (it.second == 2) 0 else 1) else (if (it.second == 1) 0 else 1) }
    }

    fun resolve(g: SportData.Game, live: List<Item>): List<Hit> {
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
        learned.forEach { id -> live.firstOrNull { it.id == id }?.let { out.add(Hit(it, 5, "")) } }
        val sport = sportChannels(live)
        // Event channels that name both teams ("NBA 01: Pacers vs Celtics") are the most exact
        SportData.channelsFor(g, sport).forEach { out.add(Hit(it, 2, "")) }
        if (out.none { it.why == 2 }) SportData.channelsFor(g, eventChannels(live)).forEach { out.add(Hit(it, 2, "")) }
        val multiDay = g.end > g.start
        for (s in slots) {
            val nearStart = kotlin.math.abs(s.prog.start - g.start) <= 90 * 60 ||
                (multiDay && s.prog.start in g.start..g.end && g.state != 2)
            if (!nearStart || (g.state == 1 && !s.live)) continue
            val exact = SportData.matches(g, s.prog.title)
            if (exact) out.add(Hit(s.ch, 1, s.prog.title))
            else if (showMatches(g, s.prog.title)) out.add(Hit(s.ch, 6, s.prog.title))
        }
        // Streams of the game from the addons (Pengu …): exact, so before the network guesses
        SportAddons.cached(g).forEach { out.add(Hit(it, 2, "Addon")) }
        if (pr != null) {
            teamChannels(g, pr).forEach { (c, t) -> out.add(Hit(c, 4, if (t == 1) g.home.name else g.away.name)) }
            // ESPN often lists no TV for games it does not carry itself (e.g. USA vs Haiti on TBS/truTV): then the usual US
            // networks of that competition are tried, so the game is never "no channel found" while it is on TV
            for (n in (g.nets + SportRights.usual(g)).distinct()) (pr.net[SportNet.key(n)] ?: pr.net["~" + SportNet.looseKey(n)])?.forEach { out.add(Hit(it, 3, n)) }
            // Still nothing: the networks that carry this sport in the US (tried in order by the player)
            if (out.isEmpty()) for (n in SportRights.fallback(g)) (pr.net[SportNet.key(n)] ?: pr.net["~" + SportNet.looseKey(n)])?.forEach { out.add(Hit(it, 6, "Usually on $n")) }
        }
        // Working channels before ones marked broken; the rest of the list is the backup order for the player
        val r = out.sortedWith(compareBy<Hit>({ if (it.autoEligible) 0 else 1 }, { it.why }))
            .distinctBy { it.ch.id }.sortedBy { if (DeadLinks.isDead(it.ch.url)) 1 else 0 }
        synchronized(cache) { if (cache.size > 800) cache.clear(); cache[g.key] = fullSig to r }
        return r
    }

    /**
     * Automatic playback and recovery use event-specific evidence or a channel assigned for this game.
     * Network, team and learned suggestions remain available on the manual Watch Live page.
     */
    fun channels(g: SportData.Game, live: List<Item>): List<Item> {
        val hits = resolve(g, live).filter { it.autoEligible }
        val c = SportSession.appCtx()
        return hits.sortedWith(compareBy<Hit>(
            { if (DeadLinks.isDead(it.ch.url)) 1 else 0 },
            { it.why },
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
    val hitsLoading by produceState<List<SportWatch.Hit>?>(null, g.key, g.nets, hub.live, prepVersion, addonVersion, epgVersion) {
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
                            3 -> "Network: " + h.show + " · matchup unconfirmed"
                            4 -> "Team channel: " + h.show + " · matchup unconfirmed"
                            5 -> "Your usual channel · matchup unconfirmed"
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
