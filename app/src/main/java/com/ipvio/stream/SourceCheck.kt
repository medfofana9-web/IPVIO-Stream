package com.ipvio.stream

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Background checks, so Play and Resume start a source that works (run while the app is open and nothing plays):
 *
 * - Continue watching (every [EVERY_MS]): for the first [TITLES] addon titles, the addons are asked for fresh links (this
 *   also fills the 10-minute stream cache, so Resume starts at once) and the top direct links are opened briefly with a
 *   range request into the file. A link that does not answer is marked failed, one that cannot jump into the file is
 *   marked "no seek" ([SourceHealth]); Resume then starts another source first.
 * - Addons (once a day): every addon with streams is asked for two well-known movies and its top links are opened the
 *   same way. An addon that finds nothing, whose links do not open, or that cannot seek is reported ("addon: …").
 *
 * Only addon links are opened (never the provider, so its connection limit is not touched), never Real-Debrid torrents
 * (resolving one adds it to the account), and only [READ_BYTES] per link. Nothing but addon names and results is sent.
 */
object SourceCheck {
    private const val EVERY_MS = 15 * 60_000L
    private const val ADDON_EVERY_MS = 20 * 3600_000L
    private const val TITLES = 3
    private const val READ_BYTES = 64 * 1024
    private const val OFFSET = 4L * 1024 * 1024
    private const val PREFS = "fof_sourcecheck"
    @Volatile private var running = false
    private var lastRun = 0L
    private val checked = HashMap<String, Long>()
    /** Well-known movies every addon should find (The Dark Knight, The Shawshank Redemption). */
    private val PROBE_TITLES = listOf(Triple("tt0468569", "The Dark Knight", 2008), Triple("tt0111161", "The Shawshank Redemption", 1994))

    /** Called every few minutes by [MainActivity]; does nothing while playing or when the last run is recent. */
    suspend fun maybeRun(c: Context) {
        val now = System.currentTimeMillis()
        if (running || AppNav.playing || now - lastRun < EVERY_MS) return
        running = true; lastRun = now
        try {
            withContext(Dispatchers.IO) {
                Stremio.ensure(c); SourceMemory.init(c); SourceHealth.load(c)
                if (Stremio.addons.none { it.streams }) return@withContext
                runCatching { resumeCheck(c) }
                val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                if (System.currentTimeMillis() - p.getLong("addons_at", 0L) > ADDON_EVERY_MS && !AppNav.playing) {
                    p.edit().putLong("addons_at", System.currentTimeMillis()).apply()
                    runCatching { addonCheck(c) }
                }
            }
        } finally { running = false }
    }

    private suspend fun resumeCheck(c: Context) {
        val now = System.currentTimeMillis()
        val list = WatchStore.entries.toList().filter { it.itemId.startsWith("stremio:") && now - it.ts < 30 * 86_400_000L }.take(TITLES)
        for (e in list) {
            if (AppNav.playing) return
            val imdb = e.itemId.removePrefix("stremio:").substringBefore(':').takeIf { StremioLogic.isImdb(it) } ?: continue
            val series = e.season > 0 || e.ep > 0
            val ck = imdb + "|" + e.season + "|" + e.ep
            if (now - (checked[ck] ?: 0L) < 3600_000L) continue
            checked[ck] = now
            val title = e.title.replace(Regex("\\s+S\\d+\\s*E\\d+\\s*$"), "")
            val want = Stremio.Want(series, title, null, e.season.coerceAtLeast(1), e.ep.coerceAtLeast(1))
            val cid = SourceMemory.contentId(imdb, series, e.season, e.ep)
            val sources = runCatching { Stremio.streams(if (series) Kind.SERIES else Kind.MOVIE, imdb, e.season.coerceAtLeast(1), e.ep.coerceAtLeast(1)) }
                .getOrDefault(emptyList())
            val resuming = e.pos > 60_000L && !e.next
            val results = ArrayList<String>()
            for (s in Stremio.rank(sources, want, cid).filter { probeable(it) }.take(3)) {
                if (AppNav.playing) return
                val r = probe(s.url)
                val state = SourceCheckLogic.state(r)
                SourceHealth.mark(c, cid, s.key, state)
                results.add(s.addon + " " + SourceCheckLogic.describe(r))
                // A working link that can seek is enough; the rest are checked next time
                if (state == SourceHealthLogic.OK || (state == SourceHealthLogic.NO_SEEK && !resuming)) break
            }
            if (results.isNotEmpty()) ReportCtx.crumb("checked " + results.size + " src")
            android.util.Log.i("FofCheck", "resume " + cid + ": " + results.joinToString("; "))
        }
    }

    private suspend fun addonCheck(c: Context) {
        val byAddon = HashMap<String, MutableList<Stremio.Source>>()
        for ((imdb, title, year) in PROBE_TITLES) {
            val l = runCatching { Stremio.lookupStreams(Kind.MOVIE, imdb, 1, 1, refresh = true).sources }.getOrDefault(emptyList())
            val want = Stremio.Want(false, title, year, 1, 1)
            for (s in Stremio.rank(l, want, SourceMemory.contentId(imdb, false, 0, 0))) byAddon.getOrPut(s.addon) { ArrayList() }.add(s)
        }
        for (a in Stremio.addons.filter { it.streams }) {
            if (AppNav.playing) return
            val mine = byAddon[a.name].orEmpty()
            val direct = mine.filter { probeable(it) }.take(2)
            val probes = direct.map { probe(it.url) }
            val verdict = SourceCheckLogic.addonVerdict(mine.size, probes)
            android.util.Log.i("FofCheck", "addon " + a.name + ": " + mine.size + " sources · " + probes.joinToString { SourceCheckLogic.describe(it) } + " → " + (verdict ?: "ok"))
            if (verdict != null) AutoReport.issue(c, "addon", a.name.take(40) + " · " + verdict,
                mine.size.toString() + " sources for 2 well-known movies · " + probes.joinToString(" ; ") { SourceCheckLogic.describe(it) } + " · " + ReportCtx.all(c))
        }
    }

    private fun probeable(s: Stremio.Source) = s.hash == null && s.url.startsWith("http") && !s.addon.equals("Your IPTV", true)

    /** Opens [url] with a range request [OFFSET] bytes into the file and reads at most [READ_BYTES]. */
    fun probe(url: String): SourceCheckLogic.Probe {
        val t0 = System.currentTimeMillis()
        return runCatching {
            val b = Request.Builder().url(url).header("Range", "bytes=$OFFSET-" + (OFFSET + READ_BYTES - 1))
            StreamHeaders.headersFor(url).forEach { (k, v) -> b.header(k, v) }
            if (StreamHeaders.headersFor(url).keys.none { it.equals("User-Agent", true) }) b.header("User-Agent", "Mozilla/5.0 (Linux; Android) FofTV")
            Net.withTimeouts(5, 6).newBuilder().callTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build().newCall(b.build()).execute().use { r ->
                val first = System.currentTimeMillis() - t0
                var n = 0
                r.body?.byteStream()?.use { ins ->
                    val buf = ByteArray(16 * 1024)
                    while (n < READ_BYTES) { val k = ins.read(buf, 0, minOf(buf.size, READ_BYTES - n)); if (k <= 0) break; n += k }
                }
                SourceCheckLogic.Probe(r.code, n, first, null)
            }
        }.getOrElse { SourceCheckLogic.Probe(0, 0, System.currentTimeMillis() - t0, it.javaClass.simpleName) }
    }
}

/** Pure part (tested in SourceCheckLogicTest). */
object SourceCheckLogic {
    /** [code] 0 = no answer ([error] says why); [bytes] read; [ms] until the answer. */
    data class Probe(val code: Int, val bytes: Int, val ms: Long, val error: String?)

    fun state(p: Probe): Int = when {
        p.code == 206 && p.bytes > 0 -> SourceHealthLogic.OK
        // Answered with the whole file: plays from the start, but a resume has to read up to the spot first
        p.code == 200 && p.bytes > 0 -> SourceHealthLogic.NO_SEEK
        // Range past the end of a short file: the link works
        p.code == 416 -> SourceHealthLogic.OK
        else -> SourceHealthLogic.FAILED
    }

    fun describe(p: Probe): String = when {
        p.code == 0 -> "no answer (" + (p.error ?: "?") + ", " + p.ms / 1000 + "s)"
        p.code == 206 -> "seekable " + p.ms + "ms"
        p.code == 200 -> "no seeking " + p.ms + "ms"
        else -> "HTTP " + p.code
    }

    /** Problem of an addon from its answers for well-known movies, or null when it is fine (or cannot be checked). */
    fun addonVerdict(sources: Int, probes: List<Probe>): String? = when {
        sources == 0 -> "finds no streams for well-known movies"
        probes.isEmpty() -> null   // only Real-Debrid torrents: not opened (that would add them to the account)
        probes.all { state(it) == SourceHealthLogic.FAILED } ->
            "links do not open (" + probes.joinToString(", ") { describe(it) } + ")"
        probes.none { state(it) == SourceHealthLogic.OK } -> "cannot jump into files (Resume has to read up to the spot)"
        else -> null
    }
}
