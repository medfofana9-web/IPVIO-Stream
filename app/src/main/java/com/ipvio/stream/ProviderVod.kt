package com.ipvio.stream

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Your IPTV as a source for addon movies and shows: the provider's movie and series names (Xtream login) are kept as a
 * small index on disk (refreshed daily). Opening an addon title looks up the same title there (cleaned name, year
 * within one) and offers the provider's copy in the source list as "Your IPTV": the movie, or exactly that episode.
 * Nothing is matched by a similar name: the cleaned names must be equal, and only one copy per language is taken.
 */
object ProviderVod {
    private class Entry(val series: Boolean, val key: String, val year: Int?, val id: String, val ext: String, val name: String)

    private val lock = Mutex()
    @Volatile private var index: Map<String, List<Entry>>? = null
    @Volatile private var indexFor: String? = null
    private const val MAX_AGE = 24 * 3600_000L

    private fun file(c: Context) = java.io.File(c.cacheDir, "provider_vod.tsv")

    /** The provider's copy of this movie or episode as a source, or null (not there, no Xtream login, no connection). */
    suspend fun source(c: Context, series: Boolean, title: String, year: Int?, season: Int, ep: Int): Stremio.Source? = withContext(Dispatchers.IO) {
        val acct = PlaylistStore.savedUrl(c)?.let { XtreamLogic.fromUrl(it) } ?: return@withContext null
        val idx = withTimeoutOrNull(25_000) { ensure(c, acct) } ?: return@withContext null
        val hit = ProviderVodLogic.pick(idx[ProviderVodLogic.key(title, series)].orEmpty().map { ProviderVodLogic.Cand(it.name, it.year) }, year)
            ?.let { i -> idx[ProviderVodLogic.key(title, series)]!![i] } ?: return@withContext null
        val info = if (series) title + " S" + season.toString().padStart(2, '0') + "E" + ep.toString().padStart(2, '0') else (title + " " + (year ?: "")).trim()
        if (!series) return@withContext Stremio.Source(ProviderVodLogic.ADDON, "Your IPTV · " + hit.name, XtreamLogic.movieUrl(acct, hit.id, hit.ext), 1080, info = info)
        val eps = runCatching { PlaylistStore.xtreamApi().episodeList(acct, hit.id) }.getOrNull() ?: return@withContext null
        val e = eps.firstOrNull { it.season == season && it.num == ep } ?: return@withContext null
        Stremio.Source(ProviderVodLogic.ADDON, "Your IPTV · " + hit.name + " S${season}E$ep", e.url, 1080, info = info)
    }

    private suspend fun ensure(c: Context, a: XtreamLogic.Acct): Map<String, List<Entry>>? = lock.withLock {
        val who = a.base + "|" + a.user
        index?.takeIf { indexFor == who }?.let { return it }
        val f = file(c)
        val fresh = f.isFile && System.currentTimeMillis() - f.lastModified() < MAX_AGE && f.useLines { it.firstOrNull() } == who
        if (!fresh) runCatching { build(a, f, who) }.onFailure { if (!f.isFile) return null }
        val m = HashMap<String, MutableList<Entry>>()
        runCatching {
            f.useLines { lines ->
                lines.drop(1).forEach { l ->
                    val p = l.split('\t')
                    if (p.size >= 6) m.getOrPut(p[1]) { ArrayList(1) }.add(Entry(p[0] == "s", p[1], p[2].toIntOrNull(), p[3], p[4], p[5]))
                }
            }
        }
        index = m; indexFor = who
        m
    }

    private fun build(a: XtreamLogic.Acct, f: java.io.File, who: String) {
        val tmp = java.io.File(f.path + ".tmp")
        val api = PlaylistStore.xtreamApi()
        tmp.bufferedWriter().use { w ->
            w.write(who); w.write("\n")
            fun line(series: Boolean, name: String, year: Int?, id: String, ext: String) {
                val clean = name.replace('\t', ' ').replace('\n', ' ')
                w.write(if (series) "s" else "m"); w.write("\t"); w.write(ProviderVodLogic.key(clean, series)); w.write("\t")
                w.write(year?.toString() ?: ""); w.write("\t"); w.write(id); w.write("\t"); w.write(ext); w.write("\t"); w.write(clean); w.write("\n")
            }
            api.titles(a, "get_vod_streams") { e ->
                val id = e["stream_id"]?.trim()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: return@titles
                val n = e["name"]?.trim().orEmpty().ifEmpty { return@titles }
                line(false, n, ProviderVodLogic.year(n, e["year"] ?: e["releaseDate"] ?: e["release_date"]), id, e["container_extension"].orEmpty())
            }
            api.titles(a, "get_series") { e ->
                val id = e["series_id"]?.trim()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: return@titles
                val n = e["name"]?.trim().orEmpty().ifEmpty { return@titles }
                line(true, n, ProviderVodLogic.year(n, e["year"] ?: e["releaseDate"] ?: e["release_date"]), id, "")
            }
        }
        tmp.renameTo(f)
    }
}

/** Pure parts of [ProviderVod] (tested). */
object ProviderVodLogic {
    const val ADDON = "Your IPTV"
    class Cand(val name: String, val year: Int?)

    private val LANG_PREFIX = Regex("""^\s*(?:[\[|(]\s*)?([A-Za-z]{2,3})(?:\s*[\]|)])?\s*[-:|•]\s+""")
    private val TAGS = Regex("""(?i)\b(4k|uhd|fhd|hd|sd|hevc|h\.?265|x265|multi|vostfr|dual|1080p|720p|2160p)\b""")
    private val YEAR_PAREN = Regex("""[\(\[]\s*((?:19|20)\d{2})\s*[\)\]]""")
    private val YEAR = Regex("""\b(19|20)\d{2}\b""")

    /** Language tag in front of the name ("EN - Title", "|US| Title"), upper case, or null. */
    fun lang(name: String): String? = LANG_PREFIX.find(name)?.groupValues?.get(1)?.uppercase()

    /** Comparable name: no language tag, year, quality tags or punctuation. Kind is part of the key. */
    fun key(name: String, series: Boolean): String {
        var n = name.replace(LANG_PREFIX, "")
        n = n.replace(YEAR_PAREN, " ").replace(TAGS, " ")
        n = n.lowercase().replace("&", " and ").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        return (if (series) "s:" else "m:") + n
    }

    fun year(name: String, field: String?): Int? =
        YEAR_PAREN.find(name)?.groupValues?.get(1)?.toIntOrNull() ?: field?.let { YEAR.find(it)?.value?.toIntOrNull() }

    /**
     * Which of the provider's copies with the same cleaned name to use: the year must fit (within one) when both are
     * known; English copies (or ones without a tag) first, then the exact year. Null = none fits.
     */
    fun pick(c: List<Cand>, year: Int?): Int? {
        val ok = c.indices.filter { i -> val y = c[i].year; year == null || y == null || kotlin.math.abs(y - year) <= 1 }
        return ok.minWithOrNull(compareBy<Int>(
            { when (lang(c[it].name)) { null, "EN", "US", "UK", "ENG" -> 0; else -> 1 } },
            { if (year != null && c[it].year == year) 0 else 1 }
        ))
    }
}
