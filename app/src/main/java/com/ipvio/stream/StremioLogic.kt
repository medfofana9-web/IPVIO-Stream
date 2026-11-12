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
