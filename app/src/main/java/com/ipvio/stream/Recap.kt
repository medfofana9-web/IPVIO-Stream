package com.ipvio.stream

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * Previously on (Option B): when an episode starts and the show was last watched more than 20 hours ago, a small card
 * in the lower left tells what happened in the episode before (TMDB's episode summary). It needs no key press and goes
 * away by itself after 15 s. Turned off in Settings > Player.
 */
object Recap {
    private fun prefs(c: Context) = Profiles.sp(c, "fof_recap")
    fun on(c: Context): Boolean = prefs(c).getBoolean("on", true)
    fun set(c: Context, v: Boolean) = prefs(c).edit().putBoolean("on", v).apply()

    /** TMDB summary of [season]/[ep] of the show, or null. */
    suspend fun summary(c: Context, show: Item, season: Int, ep: Int): String? = withContext(Dispatchers.IO) {
        runCatching {
            val m = MetaRepo.basic(c, show) ?: return@runCatching null
            if (m.tmdbId <= 0) return@runCatching null
            val url = "https://api.themoviedb.org/3/tv/${m.tmdbId}/season/$season/episode/$ep?api_key=${Config.TMDB_KEY}&language=en-US"
            Net.withTimeouts(6, 8).newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) null else JSONObject(r.body?.string().orEmpty()).optString("overview").trim().ifBlank { null }
            }
        }.getOrNull()
    }
}

@Composable
fun RecapCard(show: Item?, itemId: String, season: Int, episode: Int, modifier: Modifier) {
    val ctx = LocalContext.current
    // When this show was last watched – read once, before this episode's own progress is saved
    val lastTs = remember(itemId, season, episode) { WatchStore.entries.firstOrNull { it.itemId == itemId }?.ts ?: 0L }
    var text by remember(itemId, season, episode) { mutableStateOf<String?>(null) }
    var shown by remember(itemId, season, episode) { mutableStateOf(false) }
    LaunchedEffect(itemId, season, episode) {
        if (show == null || season <= 0 || episode <= 1 || !Recap.on(ctx)) return@LaunchedEffect
        if (lastTs <= 0L || System.currentTimeMillis() - lastTs < 20 * 3600_000L) return@LaunchedEffect
        text = Recap.summary(ctx, show, season, episode - 1) ?: return@LaunchedEffect
        delay(1_500); shown = true; delay(15_000); shown = false
    }
    val t = text ?: return
    AnimatedVisibility(shown, modifier, enter = fadeIn(), exit = fadeOut()) {
        Column(
            Modifier.padding(start = 40.dp, bottom = 90.dp).width(620.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xF210141C))
                .padding(horizontal = 26.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(raw = true, text = "PREVIOUSLY ON · S$season E${episode - 1}", color = Color(0xFFE50914), fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
            Text(raw = true, text = t, color = Color(0xFFEEEEEE), fontSize = 18.sp, lineHeight = 26.sp, maxLines = 5)
            Box(Modifier.fillMaxWidth().height(3.dp).background(Color(0x22FFFFFF)))
        }
    }
}
