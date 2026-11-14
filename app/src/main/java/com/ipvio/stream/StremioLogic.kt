package com.ipvio.stream

/**
 * Stremio addon protocol, pure logic (no Android, testable).
 * An addon lives at a base address; the manifest is at base/manifest.json and streams at
 * base/stream/{type}/{id}.json, where id is an IMDb id ("tt0111161") or, for an episode, "tt0903747:1:3".
 */
object StremioLogic {
    /**
     * Turns whatever the user pasted into the addon's base address (no trailing slash):
     * "stremio://host/abc/manifest.json" → "https://host/abc". Returns null if it cannot be an addon address.
     */
    fun base(input: String): String? {
        var s = input.trim()
        if (s.isEmpty()) return null
        if (s.startsWith("stremio://", ignoreCase = true)) s = "https://" + s.substring("stremio://".length)
        if (!s.startsWith("http://", ignoreCase = true) && !s.startsWith("https://", ignoreCase = true)) s = "https://$s"
        s = s.substringBefore('#')
        s = s.trimEnd('/')
        if (s.endsWith("/manifest.json", ignoreCase = true)) s = s.dropLast("/manifest.json".length)
        s = s.trimEnd('/')
        val host = s.substringAfter("://").substringBefore('/')
        if (host.isBlank() || !host.contains('.') && !host.startsWith("localhost") && !host.contains(':')) return null
        return s
    }

    fun manifestUrl(base: String) = "$base/manifest.json"

    /** Stremio's type for the kind of item: "movie" or "series". */
    fun type(kind: Kind): String = if (kind == Kind.SERIES) "series" else "movie"

    /** Video id: the IMDb id for a movie, "tt…:season:episode" for an episode. */
    fun videoId(imdb: String, kind: Kind, season: Int, ep: Int): String =
        if (kind == Kind.SERIES) "$imdb:$season:$ep" else imdb

    fun streamUrl(base: String, kind: Kind, imdb: String, season: Int, ep: Int): String =
        "$base/stream/${type(kind)}/${videoId(imdb, kind, season, ep)}.json"

    /** Cinemeta (Stremio's free catalog) search, used to find the IMDb id when no TMDB key is set. */
    fun searchUrl(kind: Kind, title: String): String =
        "https://v3-cinemeta.strem.io/catalog/${type(kind)}/top/search=" +
            java.net.URLEncoder.encode(title, "UTF-8").replace("+", "%20") + ".json"

    fun isImdb(id: String?): Boolean = id != null && Regex("tt\\d{5,}").matches(id)

    /**
     * Title cleanup for the catalog search: provider titles often carry tags such as "EN - ", "[4K]", "(2019)", "|DE|".
     */
    fun cleanTitle(t: String): String {
        var s = t
        s = s.replace(Regex("\\[[^]]*]|\\|[^|]*\\||\\([^)]*\\)"), " ")
        s = s.replace(Regex("^\\s*[A-Z]{2,3}\\s*[-:|]\\s*"), "")
        s = s.replace(Regex("\\b(4K|UHD|FHD|HD|SD|HEVC|H265|MULTI|SUB|DUB)\\b", RegexOption.IGNORE_CASE), " ")
        return s.replace(Regex("\\s+"), " ").trim()
    }

    /** First release year in a Cinemeta "releaseInfo" ("2008–2013", "2019"). */
    fun year(releaseInfo: String?): Int? = releaseInfo?.let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() }

    /** Short, single-line label for a stream: addon name plus the first line of its description. */
    fun label(name: String?, title: String?): String {
        val n = name.orEmpty().replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        val t = title.orEmpty().lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        return listOf(n, t).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "Stream" }
    }

    // ---------------------------------------------------------------- Does a stream belong to the requested title?
    const val MISMATCH = 0
    const val UNVERIFIED = 1
    const val VERIFIED = 2

    private val EP_SXE = Regex("(?i)(?<![a-z0-9])s(\\d{1,2})\\s*[._ -]?\\s*e(\\d{1,3})(?![0-9])")
    private val EP_X = Regex("(?i)(?<![0-9x])(\\d{1,2})x(\\d{2,3})(?![0-9])")
    private val EP_WORDS = Regex("(?i)season\\s*(\\d{1,2})\\D{0,6}episode\\s*(\\d{1,3})")
    private val SEASON_ONLY = Regex("(?i)(?<![a-z0-9])(?:s|season\\s*)(\\d{1,2})(?![0-9])(?!\\s*[._ -]?\\s*e\\d)")
    private val YEAR = Regex("(?<![0-9])(19[3-9]\\d|20[0-4]\\d)(?![0-9])")
    private val STOP = setOf("the", "a", "an", "and", "of", "de", "la", "le", "el")

    private fun words(s: String): List<String> =
        java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .replace("&", " and ").split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }

    /** Season/episode pairs named in a stream's text ("S04E01", "4x01", "Season 4 Episode 1"). */
    fun episodesIn(text: String): Set<Pair<Int, Int>> {
        val out = HashSet<Pair<Int, Int>>()
        for (r in listOf(EP_SXE, EP_X, EP_WORDS)) r.findAll(text).forEach { m ->
            val s = m.groupValues[1].toIntOrNull(); val e = m.groupValues[2].toIntOrNull()
            if (s != null && e != null) out.add(s to e)
        }
        return out
    }

    /**
     * Compares what a stream says about itself (name, title, file name) with the requested title.
     * VERIFIED: the right episode (S/E) or, for a movie, its title words and a fitting year are named.
     * MISMATCH: it names another episode or season, an episode for a movie, or (movie) only other years.
     * UNVERIFIED: nothing to compare (no file name, other language) – never auto-played as if it were certain.
     * A file name can still lie; this only keeps clearly wrong streams out and does not prove the content.
     */
    fun matchCheck(text: String, series: Boolean, title: String, year: Int?, season: Int, ep: Int): Int {
        val eps = episodesIn(text)
        val tw = words(title).filter { it !in STOP }
        val txt = words(text).toHashSet()
        val titleOk = tw.isNotEmpty() && tw.all { it in txt }
        val years = YEAR.findAll(text).mapNotNull { it.value.toIntOrNull() }.toList()
        val yearClash = year != null && years.isNotEmpty() && years.none { kotlin.math.abs(it - year) <= 1 }
        if (series) {
            if (eps.isNotEmpty()) {
                if ((season to ep) !in eps) return MISMATCH
                // Right episode number, but only verified when the show's name is there too (and no other year)
                return if (yearClash || !titleOk) UNVERIFIED else VERIFIED
            }
            val packs = SEASON_ONLY.findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() }.toSet()
            if (packs.isNotEmpty() && season !in packs) return MISMATCH
            return UNVERIFIED
        }
        if (eps.isNotEmpty()) return MISMATCH
        if (yearClash) return MISMATCH
        return if (titleOk && (year == null || years.isNotEmpty())) VERIFIED else UNVERIFIED
    }

    /** Extra facts a stream names about itself (HDR, audio, size), shown as given – nothing is guessed. */
    fun facts(text: String): List<String> {
        val u = text.uppercase()
        val out = ArrayList<String>()
        if (Regex("\\bDV\\b|DOLBY ?VISION|DOVI").containsMatchIn(u)) out.add("Dolby Vision")
        else if ("HDR10+" in u) out.add("HDR10+") else if (Regex("\\bHDR").containsMatchIn(u)) out.add("HDR")
        when {
            "ATMOS" in u -> out.add("Atmos")
            Regex("DDP|DD\\+|E-?AC-?3|EAC3").containsMatchIn(u) -> out.add("DD+")
            Regex("\\bDTS").containsMatchIn(u) -> out.add("DTS")
            Regex("\\bAC-?3\\b|\\bDD ?5").containsMatchIn(u) -> out.add("DD")
            Regex("\\bAAC").containsMatchIn(u) -> out.add("AAC")
        }
        // "DDP5.1": no word boundary before the 5, so only digits around it are excluded
        if (Regex("(?<![0-9])7[ .]1(?![0-9])").containsMatchIn(u)) out.add("7.1") else if (Regex("(?<![0-9])5[ .]1(?![0-9])").containsMatchIn(u)) out.add("5.1")
        Regex("(\\d+(?:[.,]\\d+)?)\\s?(GB|MB)\\b").find(u)?.let { out.add(it.groupValues[1].replace(',', '.') + " " + it.groupValues[2]) }
        return out
    }

    /** Quality tag found in a stream's text, for sorting (4K first). */
    fun quality(text: String): Int {
        val s = text.uppercase()
        return when {
            "2160" in s || "4K" in s || "UHD" in s -> 3
            "1080" in s || "FHD" in s -> 2
            "720" in s -> 1
            else -> 0
        }
    }
}
