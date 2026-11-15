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
 * Netflix-style tiles: a bold color square with a simple friendly face, drawn by the app itself (no download, so they
 * show instantly and offline). The same drawing is made as SVG for the phone page ([svgUri]).
 */
object ProfileAvatars {
    /** One tile: background color, face ink and the face ([AvatarArt.FACES]). */
    class Preset(val key: String, val color: Long, val face: Int) {
        /** Dark ink on the light colors (yellow, lime, sky), white everywhere else. */
        val ink: Long get() = if (color in LIGHT) 0xFF16161AL else 0xFFFFFFFFL
    }

    private val COLORS = listOf(
        0xFF2D5BE3L, // cobalt
        0xFFE0453AL, // tomato
        0xFFF5B700L, // sunflower
        0xFF13A26BL, // emerald
        0xFF7C4DFFL, // violet
        0xFFF26B1DL, // tangerine
        0xFFE8457AL, // rose
        0xFF0E9AA7L, // teal
        0xFF3F3D9EL, // indigo
        0xFF8CC63FL, // lime
        0xFF4FB6F0L, // sky
        0xFF5B6472L  // slate
    )
    private val LIGHT = setOf(0xFFF5B700L, 0xFF8CC63FL, 0xFF4FB6F0L)

    /** 24 tiles: every color twice with different faces, so neighbours never look alike. */
    val PRESETS: List<Preset> = (0 until 24).map { i -> Preset("n" + (i + 1), COLORS[i % COLORS.size], (i + i / COLORS.size * 3) % AvatarArt.FACES) }

    /** Profiles without a choice: a distinct tile by their number (first profiles: blue, tomato, yellow, green, ...). */
    private fun default(id: Int): Preset = PRESETS[id % PRESETS.size]

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("fof_avatars", Context.MODE_PRIVATE)

    /** Bumped on every change so the screens showing avatars redraw. */
    var version by mutableIntStateOf(0)
        private set

    /** Earlier picture choices (cartoon portraits) are no longer offered: those profiles get their default tile. */
    fun chosen(c: Context, id: Int): Preset? = prefs(c).getString("a$id", null)?.let { k -> PRESETS.firstOrNull { it.key == k } }

    fun presetFor(c: Context, id: Int): Preset = chosen(c, id) ?: default(id)

    fun byKey(k: String?): Preset? = PRESETS.firstOrNull { it.key == k }

    fun set(c: Context, id: Int, key: String?) {
        prefs(c).edit().apply { if (key == null) remove("a$id") else putString("a$id", key) }.apply()
        version++
    }

    /** The tile as an SVG data address (phone page). */
    fun svgUri(p: Preset): String = "data:image/svg+xml;charset=utf-8," +
        java.net.URLEncoder.encode(AvatarArt.svg(p.color, p.ink, p.face), "UTF-8").replace("+", "%20")
}

/** The avatar tile, drawn: soft light from the top left on the color, then the face. */
@Composable
fun FofAvatar(p: ProfileAvatars.Preset, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width; val h = size.height
        val base = Color(p.color)
        drawRect(base)
        drawRect(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.16f), Color.Transparent, Color.Black.copy(alpha = 0.14f)),
            start = androidx.compose.ui.geometry.Offset.Zero, end = androidx.compose.ui.geometry.Offset(w, h)))
        val ink = Color(p.ink)
        for (s in AvatarArt.shapes(p.face)) when (s) {
            is AvatarArt.Shape.Bar -> drawRoundRect(ink, androidx.compose.ui.geometry.Offset(s.x * w, s.y * h),
                androidx.compose.ui.geometry.Size(s.w * w, s.h * h), androidx.compose.ui.geometry.CornerRadius(s.r * w, s.r * w))
            is AvatarArt.Shape.Dot -> drawCircle(ink, s.r * w, androidx.compose.ui.geometry.Offset(s.cx * w, s.cy * h))
            is AvatarArt.Shape.Curve -> drawArc(ink, s.start, s.sweep, false,
                androidx.compose.ui.geometry.Offset((s.cx - s.rx) * w, (s.cy - s.ry) * h), androidx.compose.ui.geometry.Size(2 * s.rx * w, 2 * s.ry * h),
                style = androidx.compose.ui.graphics.drawscope.Stroke(s.width * w, cap = androidx.compose.ui.graphics.StrokeCap.Round))
            is AvatarArt.Shape.Bowl -> drawArc(ink, 0f, 180f, true,
                androidx.compose.ui.geometry.Offset((s.cx - s.r) * w, (s.cy - s.r) * h), androidx.compose.ui.geometry.Size(2 * s.r * w, 2 * s.r * h))
        }
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
            ProfileAvatars.PRESETS.chunked(8).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
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

@Composable
private fun AvatarChoice(p: ProfileAvatars.Preset, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (f) 1.1f else 1f, androidx.compose.animation.core.tween(140), label = "av")
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
    Box(
        modifier.size(112.dp).graphicsLayer { scaleX = s; scaleY = s }
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
