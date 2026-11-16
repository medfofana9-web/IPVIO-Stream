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
    /** Titles rated "Not for me" in the setup (left out of recommendations). */
    fun nope(c: Context): Set<String> = prefs(c).getString("nope", "").orEmpty().split('|').filter { it.isNotBlank() }.toSet()
    /** Genres that the ratings point to, best first (feeds "Picked for you" and the other taste rows). */
    fun topGenres(c: Context): List<String> = prefs(c).getString("tg", "").orEmpty().split('|').filter { it.isNotBlank() }
    /** Viewing preference answers (key -> choice, see PREF_QUESTIONS). */
    fun pref(c: Context, k: String): String? = prefs(c).getString("p_$k", null)

    /** Unfinished setup of this profile (resumed the next time it is opened). */
    fun draft(c: Context): org.json.JSONObject? = prefs(c).getString("draft", null)?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
    fun saveDraft(c: Context, d: org.json.JSONObject) { prefs(c).edit().putString("draft", d.toString()).apply() }

    /** "Not interested" from the quick menu: left out of this profile's recommendations. */
    fun addNope(c: Context, id: String) {
        val n = nope(c) + id
        prefs(c).edit().putString("nope", n.joinToString("|")).apply(); CloudSync.kick(c)
    }

    /** Loved actors and creators (TMDB id -> name). */
    fun people(c: Context): List<Pair<Int, String>> = prefs(c).getString("people", "").orEmpty().split('|').mapNotNull { p ->
        val i = p.indexOf(':'); if (i <= 0) null else p.substring(0, i).toIntOrNull()?.let { it to p.substring(i + 1) }
    }
    /** "Show less like this" from the quick menu: the title's main genre counts as not interested for recommendations. */
    fun lessLike(c: Context, genre: String) {
        val n = genresNo(c) + genre
        prefs(c).edit().putString("gno", n.joinToString("|")).apply(); CloudSync.kick(c)
    }
    /** A recommendation to leave out: its genres include one this profile asked to see less of. */
    fun isLess(no: Set<String>, i: Item): Boolean = no.isNotEmpty() && Catalog.meta(i)?.genres?.any { g -> no.any { it.equals(g, true) } } == true
    /** Genres rated "Not interested". */
    fun genresNo(c: Context): Set<String> = prefs(c).getString("gno", "").orEmpty().split('|').filter { it.isNotBlank() }.toSet()
    fun loved(c: Context): Set<String> = prefs(c).getString("loved", "").orEmpty().split('|').filter { it.isNotBlank() }.toSet()
    fun genreLoved(c: Context): Set<String> = prefs(c).getString("glove", "").orEmpty().split('|').filter { it.isNotBlank() }.toSet()

    /**
     * The profile's saved answers as a starting draft ("Your taste" edits them instead of starting over), or null when
     * there is nothing saved yet.
     */
    fun savedAsDraft(c: Context): org.json.JSONObject? {
        val r = org.json.JSONObject()
        loved(c).forEach { r.put(it, Discovery.LOVED) }
        titles(c).forEach { if (!r.has(it)) r.put(it, Discovery.LIKED) }
        nope(c).forEach { r.put(it, Discovery.NOPE) }
        val gr = org.json.JSONObject()
        genres(c).forEach { gr.put(it, if (it in genreLoved(c)) Discovery.LOVED else Discovery.LIKED) }
        genresNo(c).forEach { gr.put(it, Discovery.NOPE) }
        val p = org.json.JSONObject(); prefs(c).all.forEach { (k, v) -> if (k.startsWith("p_") && v is String) p.put(k.removePrefix("p_"), v) }
        val pe = org.json.JSONObject(); people(c).forEach { (id, n) -> pe.put(id.toString(), n) }
        if (r.length() == 0 && gr.length() == 0 && p.length() == 0 && pe.length() == 0) return null
        return org.json.JSONObject().put("step", 0).put("r", r).put("gr", gr).put("p", p).put("pe", pe)
            .put("s", org.json.JSONArray(sports(c).toList()))
    }

    /** Clears everything the setup saved for profile [id] ("Reset taste"); the setup starts blank next time. */
    fun clearAll(c: Context, id: Int = Profiles.active) {
        c.applicationContext.getSharedPreferences(Profiles.scopedName("fof_taste", id), Context.MODE_PRIVATE).edit().clear().commit()
        if (BuildConfig.VERSION_NAME.endsWith("-test")) testForce = true
        CloudSync.kick(c)
    }

    /** Saves ratings, preferences and the genres they point to (one write, together with [apply]). */
    fun applyDiscovery(c: Context, ratings: Map<String, Int>, prefsMap: Map<String, String>, topGenres: List<String>,
                       genreRatings: Map<String, Int> = emptyMap(), people: Map<Int, String> = emptyMap()) {
        val e = prefs(c).edit()
        e.putString("gno", genreRatings.filterValues { it == Discovery.NOPE }.keys.joinToString("|"))
        e.putString("glove", genreRatings.filterValues { it == Discovery.LOVED }.keys.joinToString("|"))
        e.putString("people", people.entries.joinToString("|") { it.key.toString() + ":" + it.value.replace("|", " ") })
        e.putString("nope", ratings.filterValues { it == Discovery.NOPE }.keys.joinToString("|"))
        e.putString("loved", ratings.filterValues { it == Discovery.LOVED }.keys.joinToString("|"))
        e.putString("tg", topGenres.joinToString("|"))
        for (k in prefs(c).all.keys.filter { it.startsWith("p_") }) e.remove(k)
        for ((k, v) in prefsMap) e.putString("p_$k", v)
        e.remove("draft")
        e.apply()
    }

    /** Ask the questions again the next time profile [id] is picked. */
    fun reset(c: Context, id: Int) {
        c.applicationContext.getSharedPreferences(Profiles.scopedName("fof_taste", id), Context.MODE_PRIVATE).edit().putBoolean("done", false).commit()
        if (BuildConfig.VERSION_NAME.endsWith("-test")) testForce = true
    }

    fun skip(c: Context) { prefs(c).edit().putBoolean("done", true).remove("draft").apply(); CloudSync.kick(c) }

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
    // A blank slate: only this profile's own (draft) answers, never another profile's
    // Unfinished setup first; "Your taste" on a set-up profile starts from its saved answers
    val draft = remember { Taste.draft(ctx) ?: Taste.savedAsDraft(ctx) }
    var step by remember { mutableIntStateOf(draft?.optInt("step", 0) ?: 0) }
    val ratings = remember { mutableStateMapOf<String, Int>().apply {
        draft?.optJSONObject("r")?.let { o -> o.keys().forEach { k -> put(k, o.optInt(k)) } }
    } }
    val rated = remember { mutableStateMapOf<String, Item>().apply { ratings.keys.forEach { id -> CatalogItems.get(id)?.let { put(id, it) } } } }
    val genreRatings = remember { mutableStateMapOf<String, Int>().apply {
        draft?.optJSONObject("gr")?.let { o -> o.keys().forEach { k -> put(k, o.optInt(k)) } }
    } }
    val genres = genreRatings.filterValues { it == Discovery.LOVED || it == Discovery.LIKED }.keys.sortedBy { if (genreRatings[it] == Discovery.LOVED) 0 else 1 }
    val people = remember { mutableStateMapOf<Int, String>().apply {
        draft?.optJSONObject("pe")?.let { o -> o.keys().forEach { k -> k.toIntOrNull()?.let { put(it, o.optString(k)) } } }
    } }
    val prefsMap = remember { mutableStateMapOf<String, String>().apply {
        draft?.optJSONObject("p")?.let { o -> o.keys().forEach { k -> put(k, o.optString(k)) } }
    } }
    val sports = remember { mutableStateListOf<String>().apply {
        draft?.optJSONArray("s")?.let { a -> for (i in 0 until a.length()) add(a.optString(i)) }
    } }
    val teams = remember { mutableStateListOf<TeamPick>() }
    val teamLeagues = TasteLogic.teamLeagues(sports.toSet())
    val teamList by produceState<List<TeamPick>?>(null, teamLeagues) { value = null; value = if (teamLeagues.isEmpty()) emptyList() else loadTeams(teamLeagues) }
    val moviePool by produceState<Discovery.Pool?>(null) { value = runCatching { Discovery.pool(ctx, Kind.MOVIE) }.getOrNull() ?: Discovery.Pool(emptyList(), emptyMap(), emptyMap()) }
    val showPool by produceState<Discovery.Pool?>(null) { value = runCatching { Discovery.pool(ctx, Kind.SERIES) }.getOrNull() ?: Discovery.Pool(emptyList(), emptyMap(), emptyMap()) }
    val allGenres = remember { listOf("Action", "Comedy", "Drama", "Crime", "Thriller", "Sci-Fi", "Horror", "Romance", "Mystery", "Fantasy", "Animation", "Family", "Documentary", "Reality-TV", "Adventure") }
    // Steps: movies, shows, genres, actors and creators, viewing preferences, sports, teams, review
    val steps = 8
    fun saveDraft() {
        val d = org.json.JSONObject().put("step", step)
            .put("r", org.json.JSONObject().apply { ratings.forEach { (k, v) -> put(k, v) } })
            .put("gr", org.json.JSONObject().apply { genreRatings.forEach { (k, v) -> put(k, v) } })
            .put("pe", org.json.JSONObject().apply { people.forEach { (k, v) -> put(k.toString(), v) } })
            .put("s", org.json.JSONArray(sports.toList()))
            .put("p", org.json.JSONObject().apply { prefsMap.forEach { (k, v) -> put(k, v) } })
        Taste.saveDraft(ctx, d)
    }
    LaunchedEffect(step) { saveDraft() }
    fun topGenres(): List<String> {
        val m = HashMap<String, Set<String>>()
        moviePool?.genresOf?.let { m.putAll(it) }; showPool?.genresOf?.let { m.putAll(it) }
        val fromRatings = DiscoveryLogic.genreScores(ratings, m).filter { it.second > 0 }.map { it.first }
        val no = genreRatings.filterValues { it == Discovery.NOPE }.keys
        return (genres + fromRatings).distinct().filter { it !in no }.take(5)
    }
    fun finish() {
        val liked = ratings.filterValues { it == Discovery.LOVED || it == Discovery.LIKED }.keys.mapNotNull { rated[it] ?: CatalogItems.get(it) }
        Taste.apply(ctx, sports.toSet(), teams.map { it.team }, liked, genres)
        Taste.applyDiscovery(ctx, ratings.toMap(), prefsMap.toMap(), topGenres(), genreRatings.toMap(), people.toMap())
        onDone()
    }
    // First (TV not signed in): does this person already use Fof TV? Then signing in brings their whole profile
    // The welcome screen before "Who's watching?" already asked about the account: not asked twice
    var askAccount by remember { mutableStateOf(!FofAccount.signedInState && draft == null && !SignInGate.skipped(ctx)) }
    var signingIn by remember { mutableStateOf(false) }
    var saveAtEnd by remember { mutableStateOf(false) }
    LaunchedEffect(FofAccount.signedInState, signingIn) {
        if (signingIn && FofAccount.signedInState) {
            if (saveAtEnd) finish()
            AccountCloud.run(ctx)
            CloudSync.run(ctx)
            Profiles.restartToPicker(ctx)
        }
    }
    BackHandler {
        when {
            signingIn -> signingIn = false
            askAccount -> { Taste.skip(ctx); onDone() }
            step > 0 -> step--
            else -> if (!FofAccount.signedInState && !SignInGate.skipped(ctx)) askAccount = true else { Taste.skip(ctx); onDone() }
        }
    }
    if (signingIn) { SignInWithPhone { signingIn = false }; return }
    if (askAccount) {
        AccountQuestion(name, onSignIn = { signingIn = true }, onNew = { askAccount = false })
        return
    }
    val loved = ratings.values.count { it == Discovery.LOVED || it == Discovery.LIKED }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF111218), Color(0xFF050507))))) {
        Column(Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 36.dp)) {
            // Header: mark, progress
            Row(verticalAlignment = Alignment.CenterVertically) {
                FofLogo(36.dp, 24.sp)
                Spacer(Modifier.weight(1f))
                Text("Step ${step + 1} of $steps", color = Color(0x80FFFFFF), fontSize = 14.sp, raw = true)
                Spacer(Modifier.width(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(steps) { i -> Box(Modifier.size(if (i == step) 26.dp else 8.dp, 8.dp).clip(CircleShape).background(if (i <= step) Color.White else Color(0x33FFFFFF))) }
                }
            }
            Spacer(Modifier.height(22.dp))
            val hi = if (name.isBlank()) "" else ", $name"
            val (title, sub) = when (step) {
                0 -> "Movies you love$hi" to "We know nothing about you yet. Rate the movies you have seen – about 10 to 15 helps most. Search finds any movie."
                1 -> "Shows you love" to "Now series – rate what you have watched. Your movie picks do not decide this part."
                2 -> "Your genres" to "Rate the kinds of stories you enjoy – and the ones you don't."
                3 -> "Actors and creators" to "Pick the people whose work you always watch. Search finds anyone."
                4 -> "How you like to watch" to "A few quick choices so we can tell you apart from people with the same favorites. All optional."
                5 -> "Sports you follow" to "Sports, Home and alerts will focus on them."
                6 -> "Your teams" to "Their games come first, everywhere."
                else -> "Ready" + hi to "Here is what Fof TV knows about you now. You can change it any time: Manage Profiles → your profile → Your taste."
            }
            Text(title, color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold, raw = true)
            Spacer(Modifier.height(6.dp))
            Text(sub, color = Color(0xA6FFFFFF), fontSize = 17.sp, raw = true)
            Spacer(Modifier.height(20.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (step) {
                    0 -> DiscoverStep(Kind.MOVIE, moviePool, ratings, rated) { saveDraft() }
                    1 -> DiscoverStep(Kind.SERIES, showPool, ratings, rated) { saveDraft() }
                    2 -> GenreRateStep(allGenres, genreRatings) { saveDraft() }
                    3 -> PeopleStep(people) { saveDraft() }
                    4 -> PrefsStep(prefsMap) { saveDraft() }
                    5 -> ChipGrid(TasteLogic.SPORTS.map { it.first to it.second }, sports)
                    6 -> {
                        val l = teamList
                        when {
                            sports.isEmpty() || (l != null && l.isEmpty()) -> Text("No teams to pick for these sports – go on.", color = Color(0x99FFFFFF), fontSize = 18.sp, raw = true)
                            l == null -> Text("Loading teams …", color = Color(0x99FFFFFF), fontSize = 18.sp, raw = true)
                            else -> TeamGrid(l, teams)
                        }
                    }
                    else -> Summary(
                        sports.mapNotNull { s -> TasteLogic.SPORTS.firstOrNull { it.first == s }?.second } + teams.map { it.team.short },
                        prefsMap.values.toList() + people.values.map { "Loves $it" },
                        ratings.filterValues { it == Discovery.LOVED || it == Discovery.LIKED }.keys.mapNotNull { (rated[it] ?: CatalogItems.get(it))?.title },
                        topGenres()
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                val next = remember { FocusRequester() }
                if (step < steps - 1) {
                    val picked = when (step) { 0, 1 -> loved; 2 -> genreRatings.size; 3 -> people.size; 4 -> prefsMap.size; 5 -> sports.size; 6 -> teams.size; else -> 0 }
                    PillButton(if (picked > 0) "Next  ·  $picked picked" else "Skip this step", Modifier.focusRequester(next), light = true, raw = true) { step++ }
                    if (step > 0) PillButton("Back", raw = true) { step-- }
                    Spacer(Modifier.weight(1f))
                    // Leave and come back later: the answers so far stay with this profile only
                    PillButton("Save & exit", raw = true) { saveDraft(); Profiles.restartToPicker(ctx) }
                    PillButton("Skip setup", raw = true) { Taste.skip(ctx); onDone() }
                } else {
                    PillButton("Create my Fof TV", Modifier.focusRequester(next), light = true, icon = AIcon.Play, raw = true) { finish() }
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
    // 7 even columns; focus = lift with a light rim (FOF Glass), chosen = lavender frame, check badge and a soft tint,
    // so "where I am" and "what I picked" never look the same
    LazyVerticalGrid(GridCells.Fixed(7), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(22.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp, start = 6.dp, end = 6.dp)) {
        items(list, key = { it.id }) { i ->
            val on = chosen.any { it.id == i.id }
            var f by remember { mutableStateOf(false) }
            val s by animateFloatAsState(if (f) 1.06f else 1f, tween(140), label = "pg")
            val shape = RoundedCornerShape(12.dp)
            Column((if (i === list.first()) Modifier.focusRequester(first) else Modifier).graphicsLayer { scaleX = s; scaleY = s }
                .onFocusChanged { f = it.isFocused }
                .clickable { if (!chosen.removeAll { it.id == i.id }) chosen.add(i) }) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(2f / 3f).focusGlow(f, shape).clip(shape).background(Color(0xFF15161A))
                        .border(if (on) 3.dp else 1.dp, if (on) Color(0xFFB9B4F0) else Color(0x1FFFFFFF), shape)
                ) {
                    coil.compose.AsyncImage(i.logo, i.title, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                    if (on) Box(Modifier.fillMaxSize().background(Color(0x337B5CFF)))
                    if (on) Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(30.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                        AppIcon(AIcon.Check, Color.Black, size = 18.dp)
                    }
                }
                Text(i.title, color = if (f || on) Color.White else Color(0x99FFFFFF), fontSize = 14.sp, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, raw = true, modifier = Modifier.padding(top = 8.dp, start = 2.dp))
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
private fun Summary(sports: List<String>, howYouWatch: List<String>, titles: List<String>, genres: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        fun line(label: String, v: List<String>) = label to (if (v.isEmpty()) "—" else v.joinToString(", "))
        listOf(line("Loved and liked", titles), line("Genres", genres), line("How you watch", howYouWatch), line("Sports and teams", sports)).forEach { (l, v) ->
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
            Text("Scan the code and sign in with your email and password. Your addons, lists and Continue Watching come along.",
                color = Color(0xA6FFFFFF), fontSize = 17.sp, raw = true, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 760.dp))
        }
        RemoteOverlay(Modifier.align(Alignment.Center).padding(top = 90.dp), onClose = onClose, path = "/in")
    }
}

