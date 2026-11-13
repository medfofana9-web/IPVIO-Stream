package com.ipvio.stream

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Chosen avatar per profile (device-wide prefs "fof_avatars", key "a<profile id>").
 * The avatars are flat illustrated portraits (DiceBear "personas"), each a fixed look: skin tone, hair and beard are set,
 * so the same avatar always looks the same. Profiles without a choice keep the avatar drawn from their name.
 */
object ProfileAvatars {
    class Preset(val key: String, val skin: String, val hair: String, val hairColor: String, val beard: String?, val bg: String)

    private const val DARK = "623d36"
    private const val BROWN = "92594b"
    private const val BLACK_HAIR = "1c1714"

    val PRESETS = listOf(
        // Black men
        Preset("m1", DARK, "fade", BLACK_HAIR, "beardMustache", "1f2a44"),
        Preset("m2", BROWN, "buzzcut", BLACK_HAIR, "goatee", "3b2a4a"),
        Preset("m3", DARK, "curlyHighTop", BLACK_HAIR, null, "244038"),
        Preset("m4", BROWN, "bald", BLACK_HAIR, "beardMustache", "4a2c2a"),
        Preset("m5", DARK, "fade", BLACK_HAIR, "shadow", "2d3a4a"),
        Preset("m6", BROWN, "cap", BLACK_HAIR, "beardMustache", "3a3524"),
        Preset("m7", DARK, "curly", BLACK_HAIR, "soulPatch", "1f2a44"),
        // Black women
        Preset("w1", DARK, "curlyBun", BLACK_HAIR, null, "4a2c3a"),
        Preset("w2", BROWN, "curly", BLACK_HAIR, null, "244038"),
        Preset("w3", DARK, "extraLong", BLACK_HAIR, null, "3b2a4a"),
        Preset("w4", BROWN, "bobCut", BLACK_HAIR, null, "4a2c2a"),
        Preset("w5", DARK, "bunUndercut", BLACK_HAIR, null, "2d3a4a"),
        Preset("w6", BROWN, "straightBun", "6c4545", null, "3a3524"),
        Preset("w7", DARK, "long", BLACK_HAIR, null, "1f2a44"),
        // More looks
        Preset("o1", "b16a5b", "shortCombover", "362c47", "beardMustache", "244038"),
        Preset("o2", "d78774", "sideShave", "6c4545", null, "4a2c3a"),
        Preset("o3", "eeb4a4", "bobBangs", "f29c65", null, "2d3a4a"),
        Preset("o4", "e5a07e", "long", "362c47", null, "3b2a4a"),
        Preset("o5", "e7a391", "mohawk", "6c4545", "shadow", "1f2a44"),
        Preset("o6", "d78774", "beanie", BLACK_HAIR, "goatee", "3a3524"),
        Preset("o7", "b16a5b", "curly", "6c4545", null, "4a2c2a"),
        Preset("o8", "eeb4a4", "balding", "dee1f5", "walrus", "244038")
    )

    private const val BASE = "https://api.dicebear.com/9.x/"

    fun url(p: Preset): String = BASE + "personas/png?size=256&seed=fof" + p.key +
        "&skinColor=" + p.skin + "&hair=" + p.hair + "&hairColor=" + p.hairColor +
        "&facialHairProbability=" + (if (p.beard != null) "100&facialHair=" + p.beard else "0") +
        "&backgroundColor=" + p.bg

    /** Avatar drawn from the name (the look before an avatar was chosen). */
    private fun byName(name: String) = BASE + "avataaars/png?size=256&seed=" + java.net.URLEncoder.encode(name.ifBlank { "Fof" }, "UTF-8")

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("fof_avatars", Context.MODE_PRIVATE)

    /** Bumped on every change so the screens showing avatars redraw. */
    var version by mutableIntStateOf(0)
        private set

    fun chosen(c: Context, id: Int): Preset? = prefs(c).getString("a$id", null)?.let { k -> PRESETS.firstOrNull { it.key == k } }

    fun urlFor(c: Context, id: Int, name: String): String = chosen(c, id)?.let { url(it) } ?: byName(name)

    fun set(c: Context, id: Int, key: String?) {
        prefs(c).edit().apply { if (key == null) remove("a$id") else putString("a$id", key) }.apply()
        version++
    }
}

/** Avatar gallery: OK picks one for the profile, Back closes without a change. */
@Composable
fun AvatarPicker(profileId: Int, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val current = remember { ProfileAvatars.chosen(ctx, profileId)?.key }
    val start = remember { FocusRequester() }
    val startKey = current ?: ProfileAvatars.PRESETS.first().key
    BackHandler { onClose() }
    LaunchedEffect(Unit) {
        repeat(8) { delay(60); if (runCatching { start.requestFocus() }.getOrDefault(false)) return@LaunchedEffect }
    }
    Box(Modifier.fillMaxSize().background(Color(0xF2000000)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(26.dp)) {
            Text("Choose an avatar", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold, raw = true)
            Column(verticalArrangement = Arrangement.spacedBy(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                ProfileAvatars.PRESETS.chunked(8).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        row.forEach { p ->
                            AvatarChoice(ProfileAvatars.url(p), p.key == current,
                                if (p.key == startKey) Modifier.focusRequester(start) else Modifier) {
                                ProfileAvatars.set(ctx, profileId, p.key); onClose()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AvatarChoice(url: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (f) 1.12f else 1f, androidx.compose.animation.core.tween(140), label = "av")
    Box(
        modifier.size(104.dp).graphicsLayer { scaleX = s; scaleY = s }
            .onFocusChanged { f = it.isFocused }
            .clip(CircleShape).background(Color(0xFF1C1C20))
            .border(if (f) 4.dp else if (selected) 3.dp else 0.dp, if (f) Color.White else Color(0xB3FFFFFF), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        coil.compose.AsyncImage(url, null, Modifier.fillMaxSize().padding(if (f || selected) 4.dp else 0.dp).clip(CircleShape),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        if (selected && !f) Box(Modifier.align(Alignment.BottomEnd).size(30.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
            AppIcon(AIcon.Check, Color.Black, size = 18.dp)
        }
    }
}
