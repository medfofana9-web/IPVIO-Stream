package com.ipvio.stream

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.runtime.remember

/**
 * Background of the whole app in the clean look (Option A): not plain black but a soft glow in the colors of what is
 * selected – the artwork's mood on Home, Movies and Shows, the two team colors in Sports, a calm Fof red elsewhere.
 * Drawn in its own layer, so it is only painted again while the colors change (not while rows scroll).
 */
object AppGlow {
    private const val DEF_A = 0xFF3A0B12.toInt()
    private const val DEF_B = 0xFF161A2A.toInt()
    var a by mutableIntStateOf(DEF_A)
        private set
    var b by mutableIntStateOf(DEF_B)
        private set

    fun reset() { a = DEF_A; b = DEF_B }

    fun set(top: Color, bottom: Color) { a = deep(top); b = deep(bottom) }

    /** Mood color of an artwork for the top glow; the bottom glow keeps a calm tone of the same color. */
    suspend fun art(c: Context, url: String) {
        val m = ArtColor.of(c, url)
        if (m == 0) { reset(); return }
        a = m; b = deep(Color(m), 0.55f)
    }

    private fun deep(c: Color, k: Float = 0.62f): Int =
        Color(c.red * k, c.green * k, c.blue * k).let { (0xFF shl 24) or ((it.red * 255).toInt() shl 16) or ((it.green * 255).toInt() shl 8) or (it.blue * 255).toInt() }
}

@Composable
fun AppGlowLayer(modifier: Modifier = Modifier) {
    // Each step of the colour change repaints the glow and redraws the whole window: 900 ms of that on every title change
    // kept Home drawing (#49); 300 ms, and none on Lite
    val spec = if (Look.motion == 0 || Lite.on) snap<Color>() else tween(300)
    val ca = animateColorAsState(Color(AppGlow.a), spec, label = "amb_a")
    val cb = animateColorAsState(Color(AppGlow.b), spec, label = "amb_b")
    // The glow is soft, so it is painted into a small picture (192 x 108) when its colors change and stretched over the
    // screen: one plain picture fill per frame instead of the background plus two full-screen gradient fills (the
    // performance test measured Home at 150 ms per frame with the gradients and 89 ms without the glow, 26 Nov 2026).
    // It also paints the background color, so the screen behind it is not filled first (see MainActivity).
    val pic = remember { ImageBitmap(GLOW_W, GLOW_H) }
    val painted = remember { longArrayOf(0L, 0L) }
    val cds = remember { CanvasDrawScope() }
    Box(modifier.fillMaxSize().drawBehind {
        val a = ca.value; val b = cb.value
        val key = a.value.toLong() xor (b.value.toLong() * 31)
        if (painted[1] == 0L || painted[0] != key) {
            painted[0] = key; painted[1] = 1L
            cds.draw(this, layoutDirection, Canvas(pic), Size(GLOW_W.toFloat(), GLOW_H.toFloat())) {
                val w = GLOW_W.toFloat(); val h = GLOW_H.toFloat()
                drawRect(Bg)
                drawRect(Brush.radialGradient(listOf(a.copy(alpha = 0.85f), a.copy(alpha = 0.25f), Color.Transparent), center = Offset(w * 0.8f, h * 0.02f), radius = w * 0.62f))
                drawRect(Brush.radialGradient(listOf(b.copy(alpha = 0.7f), Color.Transparent), center = Offset(w * 0.06f, h * 1.02f), radius = w * 0.5f))
            }
        }
        drawImage(pic, srcOffset = IntOffset.Zero, srcSize = IntSize(GLOW_W, GLOW_H), dstSize = IntSize(size.width.toInt(), size.height.toInt()),
            filterQuality = FilterQuality.Low)
    })
}

private const val GLOW_W = 192
private const val GLOW_H = 108
