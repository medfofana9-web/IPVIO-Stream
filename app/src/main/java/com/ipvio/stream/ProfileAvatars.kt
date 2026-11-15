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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Chosen avatar per profile (device-wide prefs "fof_avatars", key "a<profile id>").
 * Polished 3D characters (people and animals from Microsoft's Fluent Emoji, MIT license, see docs/THIRD-PARTY.md) on a rich
 * color gradient. The pictures ship inside the app (assets/avatars), so they show instantly and also offline.
 */
object ProfileAvatars {
    /** [people] = person (else animal); [src] = path in the Fluent Emoji project (picture for the phone page). */
    class Preset(val key: String, val people: Boolean, val src: String) {
        val asset: String get() = "file:///android_asset/avatars/$key.webp"
        val phoneUrl: String get() = "https://raw.githubusercontent.com/microsoft/fluentui-emoji/main/assets/" + src.replace(" ", "%20")
        /** Gradient (top, bottom) by position, so neighbours never share a color. */
        val gradient: Pair<Long, Long> get() = GRADIENTS[PRESETS.indexOf(this).coerceAtLeast(0) % GRADIENTS.size]
    }

    val GRADIENTS = listOf(
        0xFF2D5BE3L to 0xFF14287AL, 0xFFE0453AL to 0xFF6E1928L, 0xFFF5B700L to 0xFFBE5A00L, 0xFF13A26BL to 0xFF08463CL,
        0xFF7C4DFFL to 0xFF321982L, 0xFFF26B1DL to 0xFF962814L, 0xFFE8457AL to 0xFF6E1950L, 0xFF0E9AA7L to 0xFF0A3C5AL,
        0xFF3F3D9EL to 0xFF191446L, 0xFF505564L to 0xFF1E2028L
    )

    val PRESETS: List<Preset> = listOf(
        Preset("p_man_curly_hair_dark", true, "Man curly hair/Dark/3D/man_curly_hair_3d_dark.png"),
        Preset("p_woman_curly_hair_dark", true, "Woman curly hair/Dark/3D/woman_curly_hair_3d_dark.png"),
        Preset("p_person_beard_dark", true, "Person beard/Dark/3D/person_beard_3d_dark.png"),
        Preset("p_woman_curly_hair_medium-dark", true, "Woman curly hair/Medium-Dark/3D/woman_curly_hair_3d_medium-dark.png"),
        Preset("p_man_curly_hair_medium-dark", true, "Man curly hair/Medium-Dark/3D/man_curly_hair_3d_medium-dark.png"),
        Preset("p_woman_medium-dark", true, "Woman/Medium-Dark/3D/woman_3d_medium-dark.png"),
        Preset("p_man_dark", true, "Man/Dark/3D/man_3d_dark.png"),
        Preset("p_girl_medium-dark", true, "Girl/Medium-Dark/3D/girl_3d_medium-dark.png"),
        Preset("p_boy_dark", true, "Boy/Dark/3D/boy_3d_dark.png"),
        Preset("p_man_bald_medium-dark", true, "Man bald/Medium-Dark/3D/man_bald_3d_medium-dark.png"),
        Preset("p_older_person_dark", true, "Older person/Dark/3D/older_person_3d_dark.png"),
        Preset("p_woman_with_headscarf_medium-dark", true, "Woman with headscarf/Medium-Dark/3D/woman_with_headscarf_3d_medium-dark.png"),
        Preset("p_astronaut_medium-dark", true, "Astronaut/Medium-Dark/3D/astronaut_3d_medium-dark.png"),
        Preset("p_detective_dark", true, "Detective/Dark/3D/detective_3d_dark.png"),
        Preset("p_singer_dark", true, "Singer/Dark/3D/singer_3d_dark.png"),
        Preset("p_technologist_medium-dark", true, "Technologist/Medium-Dark/3D/technologist_3d_medium-dark.png"),
        Preset("p_ninja_dark", true, "Ninja/Dark/3D/ninja_3d_dark.png"),
        Preset("a_lion", false, "Lion/3D/lion_3d.png"),
        Preset("a_tiger_face", false, "Tiger face/3D/tiger_face_3d.png"),
        Preset("a_panda", false, "Panda/3D/panda_3d.png"),
        Preset("a_fox", false, "Fox/3D/fox_3d.png"),
        Preset("a_owl", false, "Owl/3D/owl_3d.png"),
        Preset("a_unicorn", false, "Unicorn/3D/unicorn_3d.png"),
        Preset("a_wolf", false, "Wolf/3D/wolf_3d.png"),
        Preset("a_koala", false, "Koala/3D/koala_3d.png"),
        Preset("a_penguin", false, "Penguin/3D/penguin_3d.png"),
        Preset("a_cat_face", false, "Cat face/3D/cat_face_3d.png"),
        Preset("a_dog_face", false, "Dog face/3D/dog_face_3d.png"),
        Preset("a_bear", false, "Bear/3D/bear_3d.png"),
        Preset("a_rabbit_face", false, "Rabbit face/3D/rabbit_face_3d.png"),
        Preset("a_monkey_face", false, "Monkey face/3D/monkey_face_3d.png"),
        Preset("a_frog", false, "Frog/3D/frog_3d.png"),
        Preset("a_hamster", false, "Hamster/3D/hamster_3d.png"),
        Preset("a_dragon_face", false, "Dragon face/3D/dragon_face_3d.png")
    )

    /** Profiles without a choice: a different person per profile number. */
    private fun default(id: Int): Preset = PRESETS[(id * 3) % 17]

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("fof_avatars", Context.MODE_PRIVATE)

    /** Bumped on every change so the screens showing avatars redraw. */
    var version by mutableIntStateOf(0)
        private set

    /** Earlier choices (cartoon portraits, drawn tiles) are no longer offered: those profiles get their default. */
    fun chosen(c: Context, id: Int): Preset? = prefs(c).getString("a$id", null)?.let { k -> PRESETS.firstOrNull { it.key == k } }

    fun presetFor(c: Context, id: Int): Preset = chosen(c, id) ?: default(id)

    fun set(c: Context, id: Int, key: String?) {
        prefs(c).edit().apply { if (key == null) remove("a$id") else putString("a$id", key) }.apply()
        version++
    }

    /** CSS background of the tile on the phone page. */
    fun cssGradient(p: Preset): String = "linear-gradient(180deg,#%06X,#%06X)".format(p.gradient.first and 0xFFFFFF, p.gradient.second and 0xFFFFFF)
}

/** The avatar tile: color gradient, a soft light behind the character, then the 3D character. */
@Composable
fun FofAvatar(p: ProfileAvatars.Preset, modifier: Modifier = Modifier) {
    val (top, bottom) = p.gradient
    Box(modifier.background(Brush.verticalGradient(listOf(Color(top), Color(bottom)))), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.22f), Color.Transparent))))
        coil.compose.AsyncImage(p.asset, null, Modifier.fillMaxSize(0.76f).offset(y = 2.dp),
            contentScale = androidx.compose.ui.layout.ContentScale.Fit)
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
    Box(Modifier.fillMaxSize().background(Color(0xF5000000)), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.fillMaxHeight().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(vertical = 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(26.dp)
        ) {
            Text("Choose your avatar", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold, raw = true)
            listOf("People" to ProfileAvatars.PRESETS.filter { it.people }, "Animals" to ProfileAvatars.PRESETS.filter { !it.people }).forEach { (title, list) ->
                Text(title, color = Color(0x99FFFFFF), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, raw = true)
                list.chunked(9).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        row.forEach { p ->
                            AvatarChoice(p, p.key == current, if (p.key == current) Modifier.focusRequester(start) else Modifier) {
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
private fun AvatarChoice(p: ProfileAvatars.Preset, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (f) 1.1f else 1f, androidx.compose.animation.core.tween(140), label = "av")
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
    Box(
        modifier.size(88.dp).graphicsLayer { scaleX = s; scaleY = s }
            .onFocusChanged { f = it.isFocused }
            .clip(shape)
            .border(if (f) 4.dp else 0.dp, Color.White, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        FofAvatar(p, Modifier.fillMaxSize())
        if (!f && !selected) Box(Modifier.fillMaxSize().background(Color(0x1A000000)))
        if (selected) Box(Modifier.align(Alignment.BottomEnd).padding(6.dp).size(26.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
            AppIcon(AIcon.Check, Color.Black, size = 16.dp)
        }
    }
}
