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
    suspend fun find(imdb: String, series: Boolean, season: Int, ep: Int, lang: String, max: Int = 3): List<OnlineSubsLogic.Sub> =
        withContext(Dispatchers.IO) {
            val url = OnlineSubsLogic.url(imdb, series, season, ep) ?: return@withContext emptyList()
            val all = synchronized(found) { found[url] } ?: runCatching {
                val req = Request.Builder().url(url).header("User-Agent", "FofTV").build()
                Net.withTimeouts(6, 8).newCall(req).execute().use { r ->
                    if (!r.isSuccessful) emptyList() else OnlineSubsLogic.parse(r.body?.string().orEmpty())
                }
            }.getOrDefault(emptyList()).also { l -> if (l.isNotEmpty()) synchronized(found) { found[url] = l } }
            OnlineSubsLogic.pick(all, lang, max)
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

    class Sub(val url: String, val lang: String)

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
            if (!u.startsWith("https://")) null else Sub(u, l)
        }
    }.getOrDefault(emptyList())

    /** The first [max] in [lang], in the server's order (best rated first), without duplicates. */
    fun pick(all: List<Sub>, lang: String, max: Int): List<Sub> {
        val want = lang(lang) ?: return emptyList()
        return all.filter { it.lang == want }.distinctBy { it.url }.take(max)
    }

    /** A subtitle file, not an error page: an SRT starts with a cue number (after an optional BOM). */
    fun looksLikeSrt(bytes: ByteArray): Boolean {
        val head = String(bytes, 0, minOf(bytes.size, 200), Charsets.UTF_8).trimStart('﻿', ' ', '\r', '\n', '\t')
        return head.isNotEmpty() && head[0].isDigit() && "-->" in String(bytes, 0, minOf(bytes.size, 400), Charsets.UTF_8)
    }
}
