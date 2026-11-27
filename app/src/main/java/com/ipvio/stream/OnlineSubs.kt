package com.ipvio.stream

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/**
 * Subtitles from the internet for movies and episodes whose stream has none in the wanted language. Source: the free
 * OpenSubtitles addon of Stremio (opensubtitles-v3.strem.io; no account, no key), asked by IMDb id. Only the IMDb id,
 * season and episode are sent – never the stream address. The files are saved in the app's cache (at most [KEEP]) and
 * handed to the player as local files, so the stream's own request headers never go to the subtitle server.
 */
object OnlineSubs {
    private const val KEEP = 40
    private const val MAX_BYTES = 2_000_000
    private val found = HashMap<String, List<OnlineSubsLogic.Sub>>()

    /** Up to [max] subtitles in [lang] (two letters) for the title; empty when nothing fits or the server is not reachable. */
    suspend fun find(imdb: String, series: Boolean, season: Int, ep: Int, lang: String, max: Int = 3, hint: OnlineSubsLogic.Hint? = null): List<OnlineSubsLogic.Sub> =
        OnlineSubsLogic.pick(all(imdb, series, season, ep), lang, max, hint)

    /** A copy of a downloaded subtitle moved by [ms] (see [OnlineSubsLogic.shiftSrt]); the original when [ms] is 0. */
    suspend fun shifted(c: Context, original: File, ms: Long): File? = withContext(Dispatchers.IO) {
        if (ms == 0L) return@withContext original
        runCatching {
            val f = File(original.parentFile, original.nameWithoutExtension + "_d" + ms + ".srt")
            if (!f.isFile) f.writeText(OnlineSubsLogic.shiftSrt(original.readText(), ms))
            f
        }.getOrNull()
    }

    /** The downloaded original behind a (possibly moved) subtitle file. */
    fun originalOf(f: File): File = File(f.parentFile, f.name.replace(Regex("_d-?\\d+\\.srt$"), ".srt"))

    /** Every subtitle OpenSubtitles lists for the title (all languages), kept for the session. */
    suspend fun all(imdb: String, series: Boolean, season: Int, ep: Int): List<OnlineSubsLogic.Sub> =
        withContext(Dispatchers.IO) {
            val url = OnlineSubsLogic.url(imdb, series, season, ep) ?: return@withContext emptyList()
            synchronized(found) { found[url] } ?: runCatching {
                val req = Request.Builder().url(url).header("User-Agent", "FofTV").build()
                Net.withTimeouts(6, 8).newCall(req).execute().use { r ->
                    if (!r.isSuccessful) emptyList() else OnlineSubsLogic.parse(r.body?.string().orEmpty())
                }
            }.getOrDefault(emptyList()).also { l -> if (l.isNotEmpty()) synchronized(found) { found[url] = l } }
        }

    /** The subtitle as a local file (downloaded once). Null when it could not be loaded. */
    suspend fun file(c: Context, s: OnlineSubsLogic.Sub): File? = withContext(Dispatchers.IO) {
        val dir = File(c.cacheDir, "subs").apply { mkdirs() }
        val f = File(dir, "s" + Integer.toHexString(s.url.hashCode()) + ".srt")
        if (f.isFile && f.length() > 0) return@withContext f.also { it.setLastModified(System.currentTimeMillis()) }
        runCatching {
            val req = Request.Builder().url(s.url).header("User-Agent", "FofTV").build()
            Net.withTimeouts(6, 10).newCall(req).execute().use { r ->
                val body = r.body ?: return@use null
                if (!r.isSuccessful || body.contentLength() > MAX_BYTES) return@use null
                val bytes = body.bytes()
                if (bytes.isEmpty() || bytes.size > MAX_BYTES || !OnlineSubsLogic.looksLikeSrt(bytes)) return@use null
                f.writeBytes(bytes)
                f
            }
        }.getOrNull().also {
            // Keep the newest files only
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(KEEP)?.forEach { it.delete() }
        }
    }
}

/** Pure part (tested in OnlineSubsLogicTest). */
object OnlineSubsLogic {
    const val BASE = "https://opensubtitles-v3.strem.io"

    /** [release]: file and release names the subtitle was made for; [fps]: its frame rate (0 = unknown). */
    class Sub(val url: String, val lang: String, val release: String = "", val fps: Float = 0f)

    /** What is playing: its file/release name (from the addon) and the video's frame rate (0 = unknown). */
    class Hint(val name: String, val fps: Float)

    fun url(imdb: String, series: Boolean, season: Int, ep: Int): String? {
        if (!Regex("tt\\d{5,}").matches(imdb)) return null
        return if (series) {
            if (season < 1 || ep < 1) null else "$BASE/subtitles/series/$imdb:$season:$ep.json"
        } else "$BASE/subtitles/movie/$imdb.json"
    }

    // OpenSubtitles' own codes that are not ISO 639-2
    private val EXTRA = mapOf("pob" to "pt", "pb" to "pt", "spn" to "es", "ze" to "zh", "zht" to "zh", "zhe" to "zh")

    fun lang(code: String?): String? {
        val c = code?.trim()?.lowercase() ?: return null
        return EXTRA[c] ?: TrackLogic.norm(c)
    }

    fun parse(json: String): List<Sub> = runCatching {
        val a = org.json.JSONObject(json).optJSONArray("subtitles") ?: return emptyList()
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val u = o.optString("url")
            val l = lang(o.optString("lang")) ?: return@mapNotNull null
            val rel = listOf("subtitleFileName", "movieReleaseName", "releaseGroup", "releaseFormat").joinToString(" ") { o.optString(it) }
            val fps = o.optInt("fpsMilli", 0) / 1000f
            if (!u.startsWith("https://")) null else Sub(u, l, rel.trim(), fps)
        }
    }.getOrDefault(emptyList())

    /**
     * The first [max] in [lang], those made for the same release as what plays first ([hint]): same frame rate (a
     * subtitle for 25 fps drifts further off on a 23.976 fps video every minute), same source (Blu-ray, WEB, HDTV, DVD),
     * same release group, same resolution. Then the server's order (most downloaded first). No duplicates.
     */
    fun pick(all: List<Sub>, lang: String, max: Int, hint: Hint? = null): List<Sub> {
        val want = lang(lang) ?: return emptyList()
        val l = all.filter { it.lang == want }.distinctBy { it.url }
        if (hint == null) return l.take(max)
        return l.withIndex().sortedWith(compareBy({ -score(it.value, hint) }, { it.index })).map { it.value }.take(max)
    }

    private val SOURCES = listOf(
        "bluray" to listOf("bluray", "blu-ray", "bdrip", "brrip", "bdremux", "remux"),
        "web" to listOf("web-dl", "webdl", "webrip", "web"),
        "hdtv" to listOf("hdtv"),
        "dvd" to listOf("dvdrip", "dvd")
    )
    private val RES = listOf("2160p", "1080p", "720p", "480p")
    private fun words(s: String) = s.lowercase().split(Regex("[^a-z0-9-]+")).filter { it.isNotEmpty() }.toSet()
    private fun source(w: Set<String>) = SOURCES.firstOrNull { (_, keys) -> keys.any { it in w } }?.first
    /** Last word after "-" in a scene name ("Inside.Man.2006.720p.HDDVD.x264-SEPTiC" -> "septic"). */
    private fun group(s: String) = Regex("-([A-Za-z0-9]{2,})(?:\\.[a-z0-9]{2,4})?(?:\\s|$)").findAll(s).lastOrNull()?.groupValues?.get(1)?.lowercase()

    /** How well a subtitle fits what plays (higher = better); 0 when nothing is known. */
    fun score(sub: Sub, hint: Hint): Int {
        var p = 0
        if (hint.fps > 0f && sub.fps > 0f) p += if (kotlin.math.abs(hint.fps - sub.fps) < 0.1f) 4 else -6
        val a = words(sub.release); val b = words(hint.name)
        val sa = source(a); val sb = source(b)
        if (sa != null && sb != null) p += if (sa == sb) 2 else -1
        val ga = group(sub.release); val gb = group(hint.name)
        if (ga != null && ga == gb) p += 3
        val ra = RES.firstOrNull { it in a }; val rb = RES.firstOrNull { it in b }
        if (ra != null && ra == rb) p += 1
        return p
    }

    /**
     * The subtitle text with every time moved by [ms] (positive = later, negative = earlier; never before 0:00), for
     * "Subtitle timing" in the player. SRT times look like 00:01:02,345.
     */
    fun shiftSrt(text: String, ms: Long): String {
        if (ms == 0L) return text
        return Regex("(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{3})").replace(text) { m ->
            val (h, mi, se, f) = m.destructured
            val t = (((h.toLong() * 60 + mi.toLong()) * 60 + se.toLong()) * 1000 + f.toLong() + ms).coerceAtLeast(0L)
            String.format(java.util.Locale.US, "%02d:%02d:%02d,%03d", t / 3_600_000, t / 60_000 % 60, t / 1000 % 60, t % 1000)
        }
    }

    /** Languages on offer with how many subtitles each: the [preferred] ones first (in that order), then by count. */
    fun languages(all: List<Sub>, preferred: List<String>): List<Pair<String, Int>> {
        val pref = preferred.mapNotNull { lang(it) }.distinct()
        return all.groupingBy { it.lang }.eachCount().entries
            .sortedWith(compareBy<Map.Entry<String, Int>>({ pref.indexOf(it.key).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { -it.value }, { it.key }))
            .map { it.key to it.value }
    }

    /** "English", "Romanian" … for a language code (two or three letters); the code itself when unknown. */
    fun langName(code: String): String {
        val n = runCatching { java.util.Locale(code).getDisplayLanguage(java.util.Locale.ENGLISH) }.getOrNull()
        return if (n.isNullOrBlank() || n.equals(code, true)) code.uppercase() else n
    }

    /** A subtitle file, not an error page: an SRT starts with a cue number (after an optional BOM). */
    fun looksLikeSrt(bytes: ByteArray): Boolean {
        val head = String(bytes, 0, minOf(bytes.size, 200), Charsets.UTF_8).trimStart('﻿', ' ', '\r', '\n', '\t')
        return head.isNotEmpty() && head[0].isDigit() && "-->" in String(bytes, 0, minOf(bytes.size, 400), Charsets.UTF_8)
    }
}
