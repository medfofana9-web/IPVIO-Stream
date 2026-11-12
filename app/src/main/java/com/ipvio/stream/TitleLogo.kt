package com.ipvio.stream

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/** Official title logos (the stylized wordmark) from TMDB, English or language-free first. Kept for the session. */
object TitleLogos {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()

    suspend fun url(tmdbId: Int, tv: Boolean): String? {
        val k = (if (tv) "t" else "m") + tmdbId
        cache[k]?.let { return it.ifEmpty { null } }
        if (!MetaRepo.enabled()) return null
        val u = withContext(Dispatchers.IO) {
            runCatching {
                val url = "https://api.themoviedb.org/3/${if (tv) "tv" else "movie"}/$tmdbId/images?api_key=${Config.TMDB_KEY}&include_image_language=en,null"
                val body = Net.base.newCall(Request.Builder().url(url).build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
                    ?: return@runCatching null
                val logos = JSONObject(body).optJSONArray("logos") ?: return@runCatching null
                var best: JSONObject? = null
                for (i in 0 until logos.length()) {
                    val o = logos.optJSONObject(i) ?: continue
                    if (o.optString("file_path").endsWith(".svg")) continue
                    if (best == null || (o.optString("iso_639_1") == "en" && best.optString("iso_639_1") != "en")) best = o
                }
                best?.optString("file_path")?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w500$it" }
            }.getOrNull()
        }
        cache[k] = u.orEmpty()
        return u
    }
}

/** Hero title: the official logo when TMDB has one, otherwise the title in large type. */
@Composable
fun HeroTitle(item: Item, m: Meta?, size: Int, maxW: Dp, maxH: Dp) {
    val logo by produceState(Catalog.logo(item.id), item.id, m?.tmdbId) {
        value = Catalog.logo(item.id) ?: m?.takeIf { it.tmdbId > 0 }?.let { TitleLogos.url(it.tmdbId, it.isTv) }
    }
    val l = logo
    if (l != null) AsyncImage(l, item.title, Modifier.widthIn(max = maxW).heightIn(max = maxH), contentScale = ContentScale.Fit, alignment = Alignment.CenterStart)
    else Text(raw = true, text = item.title, color = Tok.TextHi, fontSize = spx(size), lineHeight = spx(size),
        fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.5).sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
}
