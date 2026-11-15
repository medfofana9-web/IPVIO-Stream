package com.ipvio.stream

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
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
    /**
     * One fixed look. [style] "avataaars" (detailed cartoon portraits, the default set) or "personas" (flat style, kept so
     * avatars chosen before still show the same picture). [opts] are the style's options (skin, hair, beard, clothes).
     */
    class Preset(val key: String, val style: String, val opts: String, val bg: String)

    private fun aa(key: String, skin: String, top: String, hair: String, beard: String?, clothes: String, color: String, bg: String) =
        Preset(key, "avataaars", "skinColor=$skin&top=$top&hairColor=$hair&topProbability=100" +
            "&facialHairProbability=" + (if (beard != null) "100&facialHair=$beard&facialHairColor=$hair" else "0") +
            "&clothing=$clothes&clothesColor=$color&accessoriesProbability=0&eyes=happy&eyebrows=defaultNatural&mouth=smile", bg)

    private fun pe(key: String, skin: String, hair: String, hairColor: String, beard: String?, bg: String) =
        Preset(key, "personas", "skinColor=$skin&hair=$hair&hairColor=$hairColor&facialHairProbability=" +
            (if (beard != null) "100&facialHair=$beard" else "0"), bg)

    private const val DARK = "614335"
    private const val BROWN = "ae5d29"
    private const val TAN = "d08b5b"
    private const val LIGHT = "edb98a"
    private const val BLACK = "2c1b18"

    val PRESETS = listOf(
        // Black men
        aa("a1", DARK, "shortFlat", BLACK, "beardMedium", "hoodie", "262e33", "1f2a44"),
        aa("a2", BROWN, "theCaesar", BLACK, "beardLight", "blazerAndShirt", "3c4f5c", "3b2a4a"),
        aa("a3", DARK, "dreads01", BLACK, null, "graphicShirt", "e6e6e6", "244038"),
        aa("a4", DARK, "shortCurly", BLACK, "beardMajestic", "shirtCrewNeck", "929598", "4a2c2a"),
        aa("a5", BROWN, "fro", BLACK, null, "collarAndSweater", "65c9ff", "2d3a4a"),
        aa("a6", DARK, "theCaesarAndSidePart", BLACK, "moustacheFancy", "blazerAndSweater", "262e33", "3a3524"),
        // Black women
        aa("b1", DARK, "curly", BLACK, null, "shirtScoopNeck", "ff488e", "4a2c3a"),
        aa("b2", BROWN, "bun", BLACK, null, "blazerAndShirt", "262e33", "244038"),
        aa("b3", DARK, "froBand", BLACK, null, "overall", "5199e4", "3b2a4a"),
        aa("b4", BROWN, "bob", BLACK, null, "shirtVNeck", "ff5c5c", "1f2a44"),
        aa("b5", DARK, "curvy", BLACK, null, "collarAndSweater", "a7ffc4", "2d3a4a"),
        aa("b6", BROWN, "straight01", "4a312c", null, "hoodie", "ffffb1", "4a2c2a"),
        // More looks
        aa("c1", TAN, "shortWaved", "4a312c", "beardLight", "hoodie", "3c4f5c", "244038"),
        aa("c2", LIGHT, "shortRound", "b58143", null, "shirtCrewNeck", "65c9ff", "1f2a44"),
        aa("c3", TAN, "longButNotTooLong", BLACK, null, "blazerAndSweater", "ff488e", "3a3524"),
        aa("c4", LIGHT, "bigHair", "a55728", null, "overall", "25557c", "4a2c3a"),
        // Flat style (the first set)
        pe("m1", "623d36", "fade", "1c1714", "beardMustache", "1f2a44"),
        pe("m2", "92594b", "buzzcut", "1c1714", "goatee", "3b2a4a"),
        pe("m3", "623d36", "curlyHighTop", "1c1714", null, "244038"),
        pe("m4", "92594b", "bald", "1c1714", "beardMustache", "4a2c2a"),
        pe("m5", "623d36", "fade", "1c1714", "shadow", "2d3a4a"),
        pe("m6", "92594b", "cap", "1c1714", "beardMustache", "3a3524"),
        pe("m7", "623d36", "curly", "1c1714", "soulPatch", "1f2a44"),
        pe("w1", "623d36", "curlyBun", "1c1714", null, "4a2c3a"),
        pe("w2", "92594b", "curly", "1c1714", null, "244038"),
        pe("w3", "623d36", "extraLong", "1c1714", null, "3b2a4a"),
        pe("w4", "92594b", "bobCut", "1c1714", null, "4a2c2a"),
        pe("w5", "623d36", "bunUndercut", "1c1714", null, "2d3a4a"),
        pe("w6", "92594b", "straightBun", "6c4545", null, "3a3524"),
        pe("w7", "623d36", "long", "1c1714", null, "1f2a44")
    )

    private const val BASE = "https://api.dicebear.com/9.x/"

    fun url(p: Preset): String = BASE + p.style + "/png?size=256&seed=fof" + p.key + "&" + p.opts + "&backgroundColor=" + p.bg

    /** Profiles without a choice get a fixed look by their number (no random cartoon from the name). */
    private fun default(id: Int): Preset = PRESETS[(id * 5) % 16]

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("fof_avatars", Context.MODE_PRIVATE)

    /** Bumped on every change so the screens showing avatars redraw. */
    var version by mutableIntStateOf(0)
        private set

    fun chosen(c: Context, id: Int): Preset? = prefs(c).getString("a$id", null)?.let { k -> PRESETS.firstOrNull { it.key == k } }

    fun presetFor(c: Context, id: Int): Preset = chosen(c, id) ?: default(id)

    @Suppress("UNUSED_PARAMETER")
    fun urlFor(c: Context, id: Int, name: String): String = url(presetFor(c, id))

    fun set(c: Context, id: Int, key: String?) {
        prefs(c).edit().apply { if (key == null) remove("a$id") else putString("a$id", key) }.apply()
        version++
    }
}

/** Avatar gallery: OK picks one for the profile, Back closes without a change. Scrolls when it is taller than the screen. */
@Composable
fun AvatarPicker(profileId: Int, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val current = remember { ProfileAvatars.presetFor(ctx, profileId).key }
    val start = remember { FocusRequester() }
    BackHandler { onClose() }
    LaunchedEffect(Unit) {
        repeat(8) { delay(60); if (runCatching { start.requestFocus() }.getOrDefault(false)) return@LaunchedEffect }
    }
    val groups = listOf("Portraits" to ProfileAvatars.PRESETS.filter { it.style == "avataaars" }, "Flat" to ProfileAvatars.PRESETS.filter { it.style == "personas" })
    Box(Modifier.fillMaxSize().background(Color(0xF5000000)), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.fillMaxHeight().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            Text("Choose an avatar", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold, raw = true)
            groups.forEach { (title, list) ->
                Text(title, color = Color(0x99FFFFFF), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, raw = true)
                list.chunked(8).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        row.forEach { p ->
                            AvatarChoice(ProfileAvatars.url(p), p.key == current,
                                if (p.key == current) Modifier.focusRequester(start) else Modifier) {
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
    val s by animateFloatAsState(if (f) 1.1f else 1f, androidx.compose.animation.core.tween(140), label = "av")
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)
    Box(
        modifier.size(100.dp).graphicsLayer { scaleX = s; scaleY = s }
            .onFocusChanged { f = it.isFocused }
            .clip(shape).background(Color(0xFF1C1C20))
            .border(if (f) 4.dp else if (selected) 2.dp else 0.dp, if (f) Color.White else Color(0x99FFFFFF), shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        coil.compose.AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        if (selected && !f) Box(Modifier.align(Alignment.BottomEnd).padding(6.dp).size(26.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
            AppIcon(AIcon.Check, Color.Black, size = 16.dp)
        }
    }
}
