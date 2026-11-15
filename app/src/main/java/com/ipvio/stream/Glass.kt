package com.ipvio.stream

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

/**
 * FOF Glass: Fof TV's surface language. A glass surface is three cheap draw layers, no live blur (TV GPUs, Fire TV
 * on Android 9–11): a tinted translucent body (readable over posters), a soft top-down sheen, and a thin edge that is
 * bright at the top and fades downward like light catching a pane. Focus brightens body and edge; no glow, no color.
 *
 * Levels: 0 = chip/pill over artwork, 1 = row/card/bar, 2 = panel/dialog/menu with a lot of text.
 * Brushes are cached per theme background, so drawing allocates nothing.
 */
object FofGlass {
    private val bodyAlpha = floatArrayOf(0.55f, 0.70f, 0.88f)
    private val sheenTop = floatArrayOf(0.10f, 0.07f, 0.06f)
    private var cacheBg: Color? = null
    private val cache = HashMap<Int, Brush>()

    private fun cached(key: Int, make: () -> Brush): Brush {
        val bg = Look.bg
        if (bg != cacheBg) { cache.clear(); cacheBg = bg }
        return cache.getOrPut(key, make)
    }

    /** Translucent body tinted with the theme background, a touch lighter when focused. */
    fun body(level: Int, focused: Boolean): Brush = cached(level * 4 + (if (focused) 1 else 0)) {
        val l = level.coerceIn(0, 2)
        val base = lerp(Look.bg, Color.White, if (focused) 0.16f else 0.035f)
        Brush.verticalGradient(listOf(base.copy(alpha = bodyAlpha[l] + if (focused) 0.06f else 0f), base.copy(alpha = (bodyAlpha[l] + 0.08f).coerceAtMost(0.97f))))
    }

    /** Light from above: a soft white sheen over the upper part of the pane. */
    fun sheen(level: Int, focused: Boolean): Brush = cached(100 + level * 4 + (if (focused) 1 else 0)) {
        val a = sheenTop[level.coerceIn(0, 2)] + if (focused) 0.06f else 0f
        Brush.verticalGradient(0f to Color.White.copy(alpha = a), 0.45f to Color.White.copy(alpha = a * 0.25f), 1f to Color.Transparent)
    }

    /** Edge: bright along the top, fading down the sides. */
    fun edge(focused: Boolean): Brush = cached(200 + (if (focused) 1 else 0)) {
        Brush.verticalGradient(listOf(Color.White.copy(alpha = if (focused) 0.75f else 0.22f), Color.White.copy(alpha = if (focused) 0.28f else 0.05f)))
    }

    /** Edge drawn on focused artwork (posters, game cards): the picture stays untouched, only its rim catches light. */
    fun rim(): Brush = cached(300) {
        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.85f), Color.White.copy(alpha = 0.30f)))
    }

    /** Solid fallback when a plain color is needed (e.g. text backgrounds). */
    fun solid(level: Int): Color = lerp(Look.bg, Color.White, 0.035f).copy(alpha = bodyAlpha[level.coerceIn(0, 2)] + 0.04f)
}

/** A FOF Glass surface (clip, body, sheen, edge). */
fun Modifier.glass(shape: Shape, level: Int = 1, focused: Boolean = false): Modifier =
    this.clip(shape)
        .background(FofGlass.body(level, focused))
        .background(FofGlass.sheen(level, focused))
        .border(if (focused) 1.5.dp else 1.dp, FofGlass.edge(focused), shape)
