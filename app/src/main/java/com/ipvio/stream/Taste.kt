package com.ipvio.stream

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Taste profile (pure part): the sports a person follows and what that means for the leagues.
 * Sports groups: nba, wnba, nfl, cfb (college football), cbb (college basketball), soccer.
 */
object TasteLogic {
    val SPORTS = listOf("nfl" to "NFL", "nba" to "NBA", "wnba" to "WNBA", "cfb" to "College Football", "cbb" to "College Basketball", "soccer" to "Soccer")

    fun groupOf(leagueName: String, sport: String): String? = when (leagueName) {
        "NFL" -> "nfl"; "NBA" -> "nba"; "WNBA" -> "wnba"; "College Football" -> "cfb"
        "College Basketball", "Women's College Basketball" -> "cbb"
        else -> if (sport == "soccer") "soccer" else null
    }

    /** Leagues switched off for the chosen sports (everything of the other sports). Soccer keeps its usual extras off. */
    fun offFor(chosen: Set<String>, leagues: List<Pair<String, String>>, keptOff: Collection<String>): List<String> =
        leagues.filter { (name, sport) -> val g = groupOf(name, sport); g != null && g !in chosen }.map { it.first } +
            keptOff.filter { k -> leagues.any { it.first == k && groupOf(it.first, it.second) in chosen } }

    /** Leagues whose teams are offered in the setup (a few per sport, the ones people follow). */
    fun teamLeagues(chosen: Set<String>): List<String> = buildList {
        if ("nfl" in chosen) add("NFL")
        if ("nba" in chosen) add("NBA")
        if ("wnba" in chosen) add("WNBA")
        if ("soccer" in chosen) { add("Premier League"); add("MLS"); add("LaLiga"); add("Liga MX") }
    }
}

/**
 * Per-profile taste ("fof_taste"): done, sports, teams, liked titles, genres. Set up once per profile in [TasteSetup],
 * redone from the profile options; travels with the account (CloudSync).
 */
object Taste {
    private fun prefs(c: Context) = Profiles.sp(c, "fof_taste")

    /** Test builds: shown only when the test asks for it. */
    @Volatile var testForce = false

    fun needed(c: Context): Boolean =
        if (BuildConfig.VERSION_NAME.endsWith("-test")) testForce else !prefs(c).getBoolean("done", false)

    fun sports(c: Context): Set<String> = prefs(c).getStringSet("sports", emptySet()).orEmpty()
    fun genres(c: Context): List<String> = prefs(c).getString("genres", "").orEmpty().split('|').filter { it.isNotBlank() }
    fun titles(c: Context): List<String> = prefs(c).getString("titles", "").orEmpty().split('|').filter { it.isNotBlank() }

    /** Ask the questions again the next time profile [id] is picked. */
    fun reset(c: Context, id: Int) {
        c.applicationContext.getSharedPreferences(Profiles.scopedName("fof_taste", id), Context.MODE_PRIVATE).edit().putBoolean("done", false).commit()
        if (BuildConfig.VERSION_NAME.endsWith("-test")) testForce = true
    }

    fun skip(c: Context) { prefs(c).edit().putBoolean("done", true).apply(); CloudSync.kick(c) }

    /** Saves the answers and applies them: leagues, favorite teams, liked titles (for the "Because you like" rows), genres. */
    fun apply(c: Context, sports: Set<String>, teams: List<SportData.Team>, liked: List<Item>, genres: List<String>) {
        if (sports.isNotEmpty()) {
            SportLeagues.init(c)
            val all = SportData.LEAGUES.map { it.name to it.sport }
            SportLeagues.setOff(c, TasteLogic.offFor(sports, all, listOf("2. Bundesliga", "Cricket")))
        }
        SportFavs.init(c)
        teams.forEach { t -> if (!SportFavs.has(t)) SportFavs.toggle(c, t) }
        liked.forEach { CatalogItems.put(c, it) }
        prefs(c).edit().putBoolean("done", true).putStringSet("sports", sports)
            .putString("titles", liked.joinToString("|") { it.id }).putString("genres", genres.joinToString("|")).apply()
        testForce = false
        CloudSync.kick(c)
    }
}

private class TeamPick(val team: SportData.Team, val league: String)

private suspend fun loadTeams(leagues: List<String>): List<TeamPick> = withContext(Dispatchers.IO) {
    coroutineScope {
        leagues.mapNotNull { SportData.league(it) }.map { l ->
            async {
                val o = runCatching { J.get("https://site.api.espn.com/apis/site/v2/sports/${l.path}/teams") }.getOrNull()
                val arr = o?.optJSONArray("sports")?.optJSONObject(0)?.optJSONArray("leagues")?.optJSONObject(0)?.optJSONArray("teams")
                J.objs(arr).mapNotNull { it.optJSONObject("team") }.mapNotNull { t ->
                    val name = t.optString("displayName").ifBlank { return@mapNotNull null }
                    TeamPick(SportData.Team(name, t.optString("shortDisplayName").ifBlank { name }, t.optString("abbreviation"), J.logo(t),
                        null, false, null, t.optString("color").takeIf { it.isNotBlank() }, t.optString("id")), l.name)
                }.sortedBy { it.team.name }
            }
        }.awaitAll().flatten()
    }
}

/**
 * "Make Fof TV yours": a few quick steps for each profile (sports, teams, shows, movies, genres). Everything can be
 * skipped; the answers shape Sports, the Home rows and the recommendations of this profile.
 */
@Composable
fun TasteSetup(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val name = remember { Profiles.activeName().let { n -> if (n == Profiles.defaultName(Profiles.active) || n == Profiles.defaultName(0)) "" else n } }
    var step by remember { mutableIntStateOf(0) }
    val sports = remember { mutableStateListOf<String>().apply { addAll(Taste.sports(ctx)) } }
    val teams = remember { mutableStateListOf<TeamPick>() }
    val liked = remember { mutableStateListOf<Item>() }
    val genres = remember { mutableStateListOf<String>().apply { addAll(Taste.genres(ctx)) } }
    val teamLeagues = TasteLogic.teamLeagues(sports.toSet())
    val teamList by produceState<List<TeamPick>?>(null, teamLeagues) { value = null; value = if (teamLeagues.isEmpty()) emptyList() else loadTeams(teamLeagues) }
    val shows by produceState<List<Item>?>(null) {
        value = withContext(Dispatchers.IO) { Catalog.init(ctx); runCatching { Catalog.items(Catalog.popular(Kind.SERIES), 2) }.getOrDefault(emptyList()) }.filter { !it.logo.isNullOrBlank() }.take(40)
    }
    val movies by produceState<List<Item>?>(null) {
        value = withContext(Dispatchers.IO) { Catalog.init(ctx); runCatching { Catalog.items(Catalog.popular(Kind.MOVIE), 2) }.getOrDefault(emptyList()) }.filter { !it.logo.isNullOrBlank() }.take(40)
    }
    val allGenres = remember { listOf("Action", "Comedy", "Drama", "Crime", "Thriller", "Sci-Fi", "Horror", "Romance", "Mystery", "Fantasy", "Animation", "Family", "Documentary", "Reality-TV", "Adventure") }
    val steps = 6
    fun finish() {
        Taste.apply(ctx, sports.toSet(), teams.map { it.team }, liked.toList(), genres.toList())
        onDone()
    }
    // First: does this person already use Fof TV? Then signing in brings their whole profile; else the questions
    var askAccount by remember { mutableStateOf(!FofAccount.signedInState) }
    var signingIn by remember { mutableStateOf(false) }
    var saveAtEnd by remember { mutableStateOf(false) }
    LaunchedEffect(FofAccount.signedInState, signingIn) {
        if (signingIn && FofAccount.signedInState) {
            CloudSync.run(ctx)
            signingIn = false
            if (saveAtEnd) finish()
            else if (!Taste.needed(ctx)) onDone()          // their taste came with the account
            else askAccount = false
        }
    }
    BackHandler {
        when {
            signingIn -> signingIn = false
            askAccount -> { Taste.skip(ctx); onDone() }
            step > 0 -> step--
            else -> askAccount = true
        }
    }
    if (signingIn) { SignInWithPhone { signingIn = false }; return }
    if (askAccount) {
        AccountQuestion(name, onSignIn = { signingIn = true }, onNew = { askAccount = false })
        return
    }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF111218), Color(0xFF050507))))) {
        Column(Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 40.dp)) {
            // Header: mark, step dots
            Row(verticalAlignment = Alignment.CenterVertically) {
                FofLogo(36.dp, 24.sp)
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(steps) { i -> Box(Modifier.size(if (i == step) 26.dp else 8.dp, 8.dp).clip(CircleShape).background(if (i <= step) Color.White else Color(0x33FFFFFF))) }
                }
            }
            Spacer(Modifier.height(28.dp))
            val (title, sub) = when (step) {
                0 -> (if (name.isBlank()) "Let's make Fof TV yours" else "Let's make Fof TV yours, $name") to "Which sports do you follow? Pick all you like – Sports, Home and alerts will focus on them."
                1 -> "Your teams" to "Pick the teams you root for. Their games come first, everywhere."
                2 -> "Shows you love" to "Pick a few. We'll recommend more like them."
                3 -> "Movies you love" to "Pick a few favorites."
                4 -> "What do you like to watch?" to "Pick your favorite genres."
                else -> "All set" + (if (name.isBlank()) "" else ", $name") to "Fof TV is now tuned to you. You can change this any time: Manage Profiles → your profile → Your taste."
            }
            Text(title, color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Bold, raw = true)
            Spacer(Modifier.height(6.dp))
            Text(sub, color = Color(0xA6FFFFFF), fontSize = 18.sp, raw = true)
            Spacer(Modifier.height(26.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (step) {
                    0 -> ChipGrid(TasteLogic.SPORTS.map { it.first to it.second }, sports)
                    1 -> {
                        val l = teamList
                        when {
                            sports.isEmpty() || (l != null && l.isEmpty()) -> Text("No teams to pick for these sports – go on.", color = Color(0x99FFFFFF), fontSize = 18.sp, raw = true)
                            l == null -> Text("Loading teams …", color = Color(0x99FFFFFF), fontSize = 18.sp, raw = true)
                            else -> TeamGrid(l, teams)
                        }
                    }
                    2 -> PosterGrid(shows, liked)
                    3 -> PosterGrid(movies, liked)
                    4 -> ChipGrid(allGenres.map { it to it }, genres)
                    else -> Summary(sports.mapNotNull { s -> TasteLogic.SPORTS.firstOrNull { it.first == s }?.second }, teams.map { it.team.short }, liked.map { it.title }, genres.toList())
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                val next = remember { FocusRequester() }
                if (step < steps - 1) {
                    PillButton(if (step == 0 && sports.isEmpty()) "Skip sports" else "Next", Modifier.focusRequester(next), light = true, raw = true) { step++ }
                    if (step > 0) PillButton("Back", raw = true) { step-- }
                    Spacer(Modifier.weight(1f))
                    PillButton("Skip setup", raw = true) { Taste.skip(ctx); onDone() }
                } else {
                    PillButton("Start watching", Modifier.focusRequester(next), light = true, icon = AIcon.Play, raw = true) { finish() }
                    if (!FofAccount.signedInState) PillButton("Keep it on every TV", icon = AIcon.Phone, raw = true) { saveAtEnd = true; signingIn = true }
                    PillButton("Back", raw = true) { step-- }
                }
                LaunchedEffect(step) { if (step == steps - 1) { delay(100); runCatching { next.requestFocus() } } }
            }
        }
    }
}

@Composable
private fun ChipGrid(options: List<Pair<String, String>>, chosen: MutableList<String>) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { repeat(8) { delay(60); if (runCatching { first.requestFocus() }.getOrDefault(false)) return@LaunchedEffect } }
    LazyVerticalGrid(GridCells.Fixed(3), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        items(options, key = { it.first }) { (k, label) ->
            val on = k in chosen
            SelectTile(on, if (k == options.first().first) Modifier.focusRequester(first) else Modifier, Modifier.height(78.dp), { if (!chosen.remove(k)) chosen.add(k) }) {
                Text(label, color = if (on) Color.Black else Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, raw = true)
            }
        }
    }
}

@Composable
private fun TeamGrid(list: List<TeamPick>, chosen: MutableList<TeamPick>) {
    val first = remember { FocusRequester() }
    LaunchedEffect(list.size) { repeat(8) { delay(60); if (runCatching { first.requestFocus() }.getOrDefault(false)) return@LaunchedEffect } }
    val groups = list.groupBy { it.league }
    LazyVerticalGrid(GridCells.Fixed(7), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 20.dp)) {
        groups.forEach { (league, teams) ->
            item(key = "h$league", span = { GridItemSpan(maxLineSpan) }) {
                Text(league, color = Color(0x99FFFFFF), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, raw = true, modifier = Modifier.padding(top = 6.dp))
            }
            items(teams, key = { it.league + it.team.id }) { t ->
                val on = chosen.any { it.team.id == t.team.id && it.league == t.league }
                SelectTile(on, if (t === list.first()) Modifier.focusRequester(first) else Modifier, Modifier.height(112.dp),
                    { if (!chosen.removeAll { it.team.id == t.team.id && it.league == t.league }) chosen.add(t) }) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        coil.compose.AsyncImage(t.team.logo, t.team.name, Modifier.size(48.dp))
                        Spacer(Modifier.height(6.dp))
                        Text(t.team.short, color = if (on) Color.Black else Color.White, fontSize = 13.sp, maxLines = 1, textAlign = TextAlign.Center, raw = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun PosterGrid(list: List<Item>?, chosen: MutableList<Item>) {
    if (list == null) { Text("Loading …", color = Color(0x99FFFFFF), fontSize = 18.sp, raw = true); return }
    if (list.isEmpty()) { Text("Couldn't load titles right now – go on, you can do this later.", color = Color(0x99FFFFFF), fontSize = 18.sp, raw = true); return }
    val first = remember { FocusRequester() }
    LaunchedEffect(list.size) { repeat(8) { delay(60); if (runCatching { first.requestFocus() }.getOrDefault(false)) return@LaunchedEffect } }
    LazyVerticalGrid(GridCells.Fixed(8), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 20.dp)) {
        items(list, key = { it.id }) { i ->
            val on = chosen.any { it.id == i.id }
            var f by remember { mutableStateOf(false) }
            val s by animateFloatAsState(if (f) 1.07f else 1f, tween(140), label = "pg")
            val shape = RoundedCornerShape(10.dp)
            Box(
                (if (i === list.first()) Modifier.focusRequester(first) else Modifier).aspectRatio(2f / 3f).graphicsLayer { scaleX = s; scaleY = s }
                    .onFocusChanged { f = it.isFocused }.clip(shape).background(Color(0xFF15161A))
                    .border(if (f) 3.dp else if (on) 3.dp else 0.dp, if (f) Color.White else Color(0xFFF2F2F5), shape)
                    .clickable { if (!chosen.removeAll { it.id == i.id }) chosen.add(i) }
            ) {
                coil.compose.AsyncImage(i.logo, i.title, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                if (on) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(26.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                    AppIcon(AIcon.Check, Color.Black, size = 16.dp)
                }
            }
        }
    }
}

/** Focusable tile that is white when chosen. */
@Composable
private fun SelectTile(on: Boolean, focus: Modifier, size: Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (f) 1.05f else 1f, tween(140), label = "st")
    val shape = RoundedCornerShape(16.dp)
    Box(
        focus.then(size).fillMaxWidth().graphicsLayer { scaleX = s; scaleY = s }.onFocusChanged { f = it.isFocused }
            .clip(shape).background(if (on) Color(0xFFF2F2F5) else Color(0x14FFFFFF))
            .border(if (f) 3.dp else 0.dp, if (on) Color(0xFF7C8494) else Color.White, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
        if (on) Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(22.dp).clip(CircleShape).background(Color.Black), contentAlignment = Alignment.Center) {
            AppIcon(AIcon.Check, Color.White, size = 13.dp)
        }
    }
}

@Composable
private fun Summary(sports: List<String>, teams: List<String>, titles: List<String>, genres: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        fun line(label: String, v: List<String>) = label to (if (v.isEmpty()) "—" else v.joinToString(", "))
        listOf(line("Sports", sports), line("Teams", teams), line("Shows and movies", titles), line("Genres", genres)).forEach { (l, v) ->
            Row {
                Text(l, color = Color(0x99FFFFFF), fontSize = 18.sp, raw = true, modifier = Modifier.width(220.dp))
                Text(v, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 2, raw = true)
            }
        }
    }
}

/** "Already use Fof TV?": sign in with the phone (the whole profile comes back) or set up a new one. */
@Composable
private fun AccountQuestion(name: String, onSignIn: () -> Unit, onNew: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { repeat(8) { delay(60); if (runCatching { first.requestFocus() }.getOrDefault(false)) return@LaunchedEffect } }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF111218), Color(0xFF050507)))), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            FofLogo(44.dp, 30.sp)
            Spacer(Modifier.height(30.dp))
            Text(if (name.isBlank()) "Welcome" else "Welcome, $name", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold, raw = true)
            Spacer(Modifier.height(8.dp))
            Text("Do you already use Fof TV on another TV?", color = Color(0xA6FFFFFF), fontSize = 19.sp, raw = true)
            Spacer(Modifier.height(36.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                ChoiceCard("Yes, sign me in", "Scan a code with your phone. Your Continue Watching, avatar, teams and favorites come right over.",
                    AIcon.Phone, Modifier.focusRequester(first), onSignIn)
                ChoiceCard("No, I'm new", "Answer a few quick questions so Fof TV fits you.", AIcon.Play, Modifier, onNew)
            }
        }
    }
}

@Composable
private fun ChoiceCard(title: String, sub: String, icon: AIcon, modifier: Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (f) 1.04f else 1f, tween(140), label = "cc")
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier.width(360.dp).height(210.dp).graphicsLayer { scaleX = s; scaleY = s }.onFocusChanged { f = it.isFocused }
            .clip(shape).background(if (f) Color(0xFFF2F2F5) else Color(0x14FFFFFF)).border(if (f) 0.dp else 1.dp, Color(0x22FFFFFF), shape)
            .clickable(onClick = onClick).padding(28.dp)
    ) {
        AppIcon(icon, if (f) Color.Black else Color.White, size = 30.dp)
        Spacer(Modifier.height(18.dp))
        Text(title, color = if (f) Color.Black else Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, raw = true)
        Spacer(Modifier.height(8.dp))
        Text(sub, color = if (f) Color(0xB3000000) else Color(0x99FFFFFF), fontSize = 15.sp, raw = true)
    }
}

/** Sign in with the phone: QR code to the phone page; closes by itself once the phone has signed this profile in. */
@Composable
fun SignInWithPhone(onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF111218), Color(0xFF050507))))) {
        Column(Modifier.align(Alignment.TopCenter).padding(top = 44.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Sign in with your phone", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold, raw = true)
            Spacer(Modifier.height(6.dp))
            Text("Scan the code, then enter your email and password under “Family account”. New here? The same form creates your account.",
                color = Color(0xA6FFFFFF), fontSize = 17.sp, raw = true, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 760.dp))
        }
        RemoteOverlay(Modifier.align(Alignment.Center).padding(top = 90.dp), onClose = onClose)
    }
}

