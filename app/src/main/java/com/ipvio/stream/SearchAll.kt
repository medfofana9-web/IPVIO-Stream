package com.ipvio.stream

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** Pure matching rules of the search over everything (no Android, testable). */
object SearchAllLogic {
    /** Words that only join a matchup ("lakers vs celtics") and must not have to match. */
    private val JOIN = setOf("vs", "v", "at", "and", "game", "the")

    fun tokens(q: String): List<String> = ChannelSearch.norm(q).split(' ').filter { it.isNotEmpty() && it !in JOIN }

    /** Every word of the query occurs as the start of a word in [hay] ("lak" finds "Lakers", "celt" finds "Celtics"). */
    fun matches(hay: String, tokens: List<String>): Boolean {
        if (tokens.isEmpty()) return false
        val words = ChannelSearch.norm(hay).split(' ')
        return tokens.all { t -> words.any { it.startsWith(t) } || (t.length >= 4 && words.any { t in it }) }
    }

    /** Live first, then upcoming (soonest first), then finished (latest first). */
    fun rank(state: Int, start: Long): Long = when (state) { 1 -> -1L; 0 -> start; else -> Long.MAX_VALUE / 2 - start }
}

/** Search over the sports data: games of the next days, teams and leagues (ESPN, cached). */
object SearchSports {
    private const val DAYS = 3
    private const val MAX_AGE = 5 * 60_000L

    /** Games of all switched-on leagues: today and the next days, plus the current events of event sports. */
    suspend fun games(): List<SportData.Game> = withContext(Dispatchers.IO) {
        val tz = General.zone()
        val leagues = SportData.LEAGUES.filter { SportLeagues.on(it) }
        val fetched = runCatching {
            coroutineScope {
                leagues.flatMap { l ->
                    if (l.kind == SportData.KIND_TEAM) (0 until DAYS).map { d ->
                        async { runCatching { SportData.fetch(l, SportData.ymd(d, tz), MAX_AGE) }.getOrDefault(emptyList()) }
                    } else listOf(async { runCatching { SportData.fetchCurrent(l, MAX_AGE) }.getOrDefault(emptyList()) })
                }.awaitAll().flatten()
            }
        }.getOrDefault(emptyList())
        // The shared score map is UI state: written and read on the main thread
        withContext(Dispatchers.Main) {
            SportFeed.put(fetched)
            (fetched + SportFeed.latest.values.toList()).distinctBy { it.key }.map { SportFeed.latest[it.key] ?: it }
        }
    }

    private fun hay(t: SportData.Team) = t.name + " " + t.short + " " + t.abbr

    fun findGames(all: List<SportData.Game>, q: String): List<SportData.Game> {
        val tk = SearchAllLogic.tokens(q)
        return all.filter { g -> SearchAllLogic.matches(hay(g.home) + " " + hay(g.away) + " " + g.title + " " + g.league + " " + g.note, tk) }
            .sortedBy { SearchAllLogic.rank(it.state, it.start) }.take(24)
    }

    fun findTeams(all: List<SportData.Game>, q: String): List<Pair<SportData.League, SportData.Team>> {
        val tk = SearchAllLogic.tokens(q)
        val out = LinkedHashMap<String, Pair<SportData.League, SportData.Team>>()
        for (g in all) {
            if (g.solo) continue
            val l = SportData.leagueOf(g) ?: continue
            for (t in listOf(g.home, g.away)) {
                if (t.name.isBlank() || !SearchAllLogic.matches(hay(t), tk)) continue
                out.getOrPut(l.name + "|" + t.id.ifBlank { t.name }) { l to t }
            }
        }
        return out.values.take(20)
    }

    fun findLeagues(q: String): List<SportData.League> {
        val tk = SearchAllLogic.tokens(q)
        return SportData.LEAGUES.filter { SearchAllLogic.matches(it.name + " " + it.catName + " " + it.sport, tk) }
    }
}

/** Search over the TV guide: shows on now or later today on the playlist's channels. */
object SearchGuide {
    fun shows(live: List<Item>, q: String): List<GuideSearchLogic.Hit> {
        val now = System.currentTimeMillis() / 1000
        return GuideSearchLogic.search(live, q, now, limit = 40, maxChannels = 0)
            .filter { it.entry != null && it.entry.end > now && it.entry.start < now + 12 * 3600 }.take(24)
    }
}

/** Row title of the search results (same look as the other result rows). */
/** The first card of a row takes the requester the keyboard jumps to. */
private fun Modifier.first(i: Int, r: FocusRequester?): Modifier = if (i == 0 && r != null) focusRequester(r) else this

@Composable
private fun ResultTitle(text: String) {
    Row(Modifier.padding(start = DS.PageX, top = 22.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(22.dp).clip(RoundedCornerShape(2.dp)).background(Accent1))
        Spacer(Modifier.width(10.dp))
        Text(text, color = Color.White, fontSize = spx(22), fontWeight = FontWeight.Bold, raw = true)
    }
}

/** Catalog titles (movies or shows) as poster cards that open the title page. */
@Composable
internal fun SearchTitleRow(title: String, list: List<Item>, cardW: Int, first: FocusRequester?, onFocus: (Item) -> Unit, onOpen: (Item) -> Unit) {
    if (list.isEmpty()) return
    Column {
        ResultTitle(title)
        LazyRow(contentPadding = PaddingValues(horizontal = DS.PageX, vertical = 18.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            itemsIndexed(list, key = { _, it -> title + it.id }) { i, x ->
                RowPosterCard(x, cardW.dp, Modifier.first(i, first).restoreFocus(x.id).onFocusChanged { if (it.hasFocus) onFocus(x) }, expand = false) { onOpen(x) }
            }
        }
    }
}

/** Games: the same matchup tiles as the sports area. */
@Composable
internal fun SearchGameRow(games: List<SportData.Game>, first: FocusRequester?, onGame: (SportData.Game) -> Unit) {
    if (games.isEmpty()) return
    Column {
        ResultTitle("Games")
        LazyRow(contentPadding = PaddingValues(horizontal = DS.PageX, vertical = 18.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            itemsIndexed(games, key = { _, it -> "g" + it.key }) { i, g -> GameTileCard(g, Modifier.first(i, first)) { onGame(g) } }
        }
    }
}

/** Teams and leagues as round-logo pills; OK opens their page in the sports area. */
@Composable
internal fun SearchTeamRow(
    teams: List<Pair<SportData.League, SportData.Team>>, leagues: List<SportData.League>, first: FocusRequester?,
    onTeam: (SportData.League, SportData.Team) -> Unit, onLeague: (SportData.League) -> Unit
) {
    if (teams.isEmpty() && leagues.isEmpty()) return
    Column {
        ResultTitle(if (teams.isEmpty()) "Leagues" else if (leagues.isEmpty()) "Teams" else "Teams & leagues")
        LazyRow(contentPadding = PaddingValues(horizontal = DS.PageX, vertical = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            itemsIndexed(teams, key = { _, it -> "t" + it.first.name + it.second.id + it.second.name }) { i, (l, t) ->
                SearchChip(t.name, l.name, t.logo, t.abbr, Modifier.first(i, first)) { onTeam(l, t) }
            }
            itemsIndexed(leagues, key = { _, it -> "l" + it.name }) { i, l ->
                SearchChip(l.name, l.catName, null, l.name, Modifier.first(if (teams.isEmpty()) i else 1, first)) { onLeague(l) }
            }
        }
    }
}

/** TV guide hits: show title, channel and time; OK plays the channel. */
@Composable
internal fun SearchShowRow(hits: List<GuideSearchLogic.Hit>, first: FocusRequester?, onPlay: (Item) -> Unit) {
    if (hits.isEmpty()) return
    val now = System.currentTimeMillis() / 1000
    Column {
        ResultTitle("On TV")
        LazyRow(contentPadding = PaddingValues(horizontal = DS.PageX, vertical = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            itemsIndexed(hits, key = { _, it -> "e" + it.channel.id + it.entry!!.start }) { i, h ->
                val e = h.entry!!
                val sub = (if (e.start <= now) "Now" else hhmm(e.start)) + " · " + h.channel.title
                SearchChip(e.title, sub, h.channel.logo, h.channel.title, Modifier.first(i, first), wide = true) { onPlay(h.channel) }
            }
        }
    }
}

@Composable
private fun SearchChip(title: String, sub: String, logo: String?, fallback: String, modifier: Modifier = Modifier, wide: Boolean = false, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(if (f) focusScale(1.05f) else 1f, label = "searchchip")
    val shape = RoundedCornerShape(dpx(14))
    Row(
        modifier.width(dpx(if (wide) 340 else 280)).height(dpx(84)).graphicsLayer { scaleX = sc; scaleY = sc }
            .clip(shape).background(Surf)
            .border(if (f) 3.dp else 1.dp, if (f) Color.White else DS.Hairline, shape)
            .onFocusChanged { f = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = dpx(14)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dpx(12))
    ) {
        Box(Modifier.size(dpx(52)).clip(RoundedCornerShape(50)).background(DS.Overlay2), contentAlignment = Alignment.Center) {
            TeamLogo(logo, fallback, dpx(40))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = spx(17), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, raw = true)
            Text(sub, color = Muted, fontSize = spx(14), maxLines = 1, overflow = TextOverflow.Ellipsis, raw = true)
        }
    }
}
