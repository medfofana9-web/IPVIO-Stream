package com.ipvio.stream

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Real-Debrid: turns torrent results of Stremio addons (infoHash) into a direct link. The user's own API token
 * (real-debrid.com/apitoken) is stored encrypted on this device only. Only torrents Real-Debrid already has
 * ("cached") play right away; others are removed again and reported as not ready.
 */
object Debrid {
    private const val API = "https://api.real-debrid.com/rest/1.0"
    private val http by lazy { Net.withTimeouts(10, 20) }
    private val VIDEO = Regex("\\.(mkv|mp4|avi|m4v|mov|ts|webm|wmv)$", RegexOption.IGNORE_CASE)

    /** Is a token saved? (Torrent results are shown only then.) */
    var on by mutableStateOf(false)
        private set
    @Volatile private var token: String = ""
    @Volatile private var inited = false
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("fof_debrid", Context.MODE_PRIVATE)

    /** Reads the token once (keystore decryption: also run in the background at app start, see MainActivity). */
    @Synchronized
    fun init(c: Context) {
        if (inited) return
        val enc = prefs(c).getString("rd", null)
        if (enc != null) {
            token = Secrets.decrypt(enc).orEmpty()
            on = token.isNotBlank()
        }
        inited = true
    }

    fun masked(): String = if (token.length > 6) "…" + token.takeLast(4) else ""

    /** Saves and checks the token. Returns the Real-Debrid user name, or an error text starting with "!". */
    suspend fun save(c: Context, t: String): String = withContext(Dispatchers.IO) {
        val v = t.trim().filter { it.isLetterOrDigit() }
        if (v.isEmpty()) {
            prefs(c).edit().remove("rd").apply(); token = ""; withContext(Dispatchers.Main) { on = false }
            return@withContext "!Removed"
        }
        val user = runCatching { JSONObject(call("GET", "/user", null, v)).optString("username") }.getOrNull()
            ?: return@withContext "!The token was not accepted. Copy it again from real-debrid.com/apitoken."
        val enc = Secrets.encrypt(v) ?: return@withContext "!Could not store the token on this device."
        prefs(c).edit().putString("rd", enc).apply()
        token = v
        withContext(Dispatchers.Main) { on = true }
        user
    }

    private fun call(method: String, path: String, form: Map<String, String>?, tk: String = token): String {
        val b = Request.Builder().url(API + path).header("Authorization", "Bearer $tk")
        when (method) {
            "POST" -> b.post(FormBody.Builder().apply { form?.forEach { (k, v) -> add(k, v) } }.build())
            "DELETE" -> b.delete()
            else -> b.get()
        }
        http.newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) error("Real-Debrid ${r.code}")
            return r.body?.string().orEmpty()
        }
    }

    /**
     * Direct link for a torrent result: add the magnet, pick the file ([fileName] if given, otherwise the largest video),
     * wait a few seconds for Real-Debrid, then unlock the link. Throws with a readable message when it cannot play now.
     */
    suspend fun resolve(hash: String, fileName: String?): String = withContext(Dispatchers.IO) {
        if (token.isBlank()) error("Add your Real-Debrid token under Settings > Stremio addons.")
        val id = JSONObject(call("POST", "/torrents/addMagnet", mapOf("magnet" to "magnet:?xt=urn:btih:$hash"))).optString("id")
        if (id.isBlank()) error("Real-Debrid did not accept this torrent.")
        try {
            var info = JSONObject(call("GET", "/torrents/info/$id", null))
            if (info.optString("status") == "waiting_files_selection") {
                val files = info.optJSONArray("files") ?: JSONArray()
                var pick = -1; var best = -1L
                for (i in 0 until files.length()) {
                    val f = files.optJSONObject(i) ?: continue
                    val path = f.optString("path")
                    val named = fileName != null && path.endsWith(fileName, ignoreCase = true)
                    val size = f.optLong("bytes")
                    if (named) { pick = f.optInt("id"); break }
                    if (VIDEO.containsMatchIn(path) && size > best) { best = size; pick = f.optInt("id") }
                }
                call("POST", "/torrents/selectFiles/$id", mapOf("files" to if (pick > 0) pick.toString() else "all"))
            }
            repeat(8) {
                info = JSONObject(call("GET", "/torrents/info/$id", null))
                when (info.optString("status")) {
                    "downloaded" -> {
                        val link = info.optJSONArray("links")?.optString(0).orEmpty()
                        if (link.isBlank()) error("Real-Debrid has no link for this file.")
                        val dl = JSONObject(call("POST", "/unrestrict/link", mapOf("link" to link))).optString("download")
                        if (dl.isBlank()) error("Real-Debrid could not unlock the link.")
                        return@withContext dl
                    }
                    "magnet_error", "error", "virus", "dead" -> error("Real-Debrid reports this torrent as broken.")
                }
                delay(1000)
            }
            runCatching { call("DELETE", "/torrents/delete/$id", null) }
            error("Not cached on Real-Debrid yet. Pick another result.")
        } catch (e: Exception) {
            if (e.message?.startsWith("Not cached") != true) runCatching { call("DELETE", "/torrents/delete/$id", null) }
            throw e
        }
    }
}
