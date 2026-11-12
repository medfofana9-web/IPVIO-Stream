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
    class Hit(val ch: Item, val why: Int, val show: String)

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
        val sig = (((((System.identityHashCode(live) * 31 + live.size) * 31 + version) * 31 + (fixed?.hashCode() ?: 0)) * 31 +
            learned.hashCode()) * 31 + SportLearn.version) * 31 + (if (pr != null) 1 else 0)
        synchronized(cache) { cache[g.key]?.let { if (it.first == sig) return it.second } }
        val out = ArrayList<Hit>()
        if (fixed != null) live.firstOrNull { it.id == fixed }?.let { out.add(Hit(it, 0, "")) }
        learned.forEach { id -> live.firstOrNull { it.id == id }?.let { out.add(Hit(it, 5, "")) } }
        val sport = sportChannels(live)
        // Event channels that name both teams ("NBA 01: Pacers vs Celtics") are the most exact
        SportData.channelsFor(g, sport).forEach { out.add(Hit(it, 2, "")) }
        val multiDay = g.end > g.start
        for (s in slots) {
            val timeOk = (s.live && g.state == 1) || kotlin.math.abs(s.prog.start - g.start) <= 3 * 3600 ||
                (multiDay && s.prog.start in g.start..g.end && g.state != 2)
            if (timeOk && showMatches(g, s.prog.title + " " + s.prog.desc)) out.add(Hit(s.ch, 1, s.prog.title))
        }
        if (pr != null) {
            teamChannels(g, pr).forEach { (c, t) -> out.add(Hit(c, 4, if (t == 1) g.home.name else g.away.name)) }
            for (n in g.nets) (pr.net[SportNet.key(n)] ?: pr.net["~" + SportNet.looseKey(n)])?.forEach { out.add(Hit(it, 3, n)) }
        }
        // Working channels before ones marked broken; the rest of the list is the backup order for the player
        val r = out.distinctBy { it.ch.id }.sortedBy { if (DeadLinks.isDead(it.ch.url)) 1 else 0 }
        synchronized(cache) { if (cache.size > 800) cache.clear(); cache[g.key] = sig to r }
        return r
    }

    fun channels(g: SportData.Game, live: List<Item>): List<Item> = resolve(g, live).map { it.ch }
}

/** Watch Live page: the event, all matching channels with reason and availability, play, assign by hand. */
@Composable
internal fun WatchPage(g0: SportData.Game, hub: HubCtx) {
    val ctx = LocalContext.current
    SportFix.init(ctx)
    val g = SportFeed.latest[g0.key] ?: g0
    @Suppress("UNUSED_VARIABLE") val ready = SportWatch.prepVersion   // recompose once the channel lookups are built
    val hits = SportWatch.resolve(g, hub.live)
    var pick by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    HubPageFrame("Live ansehen", g.groupName, { hub.nav.pop() }, first, rawTitle = false) {
        HubPanel(Modifier.fillMaxWidth()) {
            MatchHeader(g, 90)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(12))) {
                if (hits.isNotEmpty() && g.state != 2) HeroButton("Live ansehen", AIcon.Play, true, Modifier, 46) { hub.watch(g) }
                // Finished game: play it from the provider's archive (catch-up) on the matched channel, when the provider offers it
                val replay = remember(g.key, g.state, hits) {
                    if (g.state != 2) null else {
                        val now = System.currentTimeMillis() / 1000
                        val e = EpgEntry(if (g.solo) g.title else g.away.short + " at " + g.home.short, "", g.start - 300, minOf(g.start + 4 * 3600, now - 60))
                        hits.firstNotNullOfOrNull { h -> Catchup.urlFor(h.ch, e)?.let { u -> h.ch.copy(url = u, title = "Replay: " + e.title) } }
                    }
                }
                if (replay != null) HeroButton("Watch replay", AIcon.Catchup, true, Modifier, 46) { hub.play(replay, listOf(replay)) }
                // Joined late: start the running game from the beginning (provider archive)
                val fromStart = remember(g.key, g.state, hits) {
                    if (g.state != 1) null else {
                        val now = System.currentTimeMillis() / 1000
                        val e = EpgEntry(if (g.solo) g.title else g.away.short + " at " + g.home.short, "", g.start - 300, now - 60)
                        hits.firstNotNullOfOrNull { h -> Catchup.urlFor(h.ch, e)?.let { u -> h.ch.copy(url = u, title = "From the start: " + e.title) } }
                    }
                }
                if (fromStart != null) FilterPill("Watch from the start", false, Modifier.trackFocus(), aicon = AIcon.Rewind) { hub.play(fromStart, listOf(fromStart)) }
                // Upcoming: reminder switches the TV to the game when it starts
                if (g.state == 0) {
                    GameDay.init(ctx)
                    val on = GameDay.has(g)
                    FilterPill(if (on) "Auto-tune on" else "Auto-tune at start", on, Modifier.trackFocus(), aicon = AIcon.Bell) { GameDay.toggle(ctx, g) }
                }
                FilterPill(if (hits.isEmpty()) "Sender zuordnen" else "Anderen Sender wählen", false, Modifier.focusRequester(first), aicon = AIcon.Edit) { pick = true }
                if (SportFix.map.containsKey(SportFix.key(g))) FilterPill("Automatisch zuordnen", false, Modifier.trackFocus(), aicon = AIcon.Refresh) { SportFix.set(ctx, g, null) }
                FilterPill("Mehr Infos", false, Modifier.trackFocus(), aicon = AIcon.Info) { hub.nav.open(HubPage.Match(g)) }
            }
        }
        if (hits.isEmpty()) HubEmpty(
            AIcon.Live, "Kein passender Sender gefunden",
            "ESPN liefert keine Streams. Gesucht wird in deinem TV-Guide und in deinen Sendernamen. Du kannst den Sender auch selbst zuordnen; die App merkt sich das für dieses Spiel."
        ) else LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = dpx(30)), verticalArrangement = Arrangement.spacedBy(dpx(4))) {
            itemsIndexed(hits, key = { _, h -> h.ch.id }) { _, h ->
                FRow({ hub.watchOn(g, h.ch, hits.map { it.ch }) }) {
                    TeamLogo(h.ch.logo, h.ch.title.take(2), dpx(44))
                    Column(Modifier.weight(1f)) {
                        Text(raw = true, text = h.ch.title, color = Tok.TextHi, fontSize = spx(18), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val why = when (h.why) {
                            0 -> Lang.tr("Von dir zugeordnet")
                            1 -> Lang.tr("Laut TV-Guide") + ": " + h.show
                            3 -> "Network: " + h.show
                            4 -> "Team channel: " + h.show
                            5 -> "Your usual channel"
                            else -> Lang.tr("Sendername passt")
                        }
                        Text(raw = true, text = why, color = Muted, fontSize = spx(14), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val dead = DeadLinks.isDead(h.ch.url)
                    val verified = !dead && SportSession.isVerified(ctx, h.ch.id)
                    Box(Modifier.size(dpx(10)).clip(RoundedCornerShape(50)).background(if (dead) DS.Gold else Accent2))
                    if (verified) Text(raw = true, text = "Verified", color = Accent2, fontSize = spx(15), fontWeight = FontWeight.Bold)
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
