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
import androidx.compose.ui.graphics.graphicsLayer

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
    val spec = if (Look.motion == 0) snap<Color>() else tween(900)
    val ca by animateColorAsState(Color(AppGlow.a), spec, label = "amb_a")
    val cb by animateColorAsState(Color(AppGlow.b), spec, label = "amb_b")
    Box(modifier.fillMaxSize().graphicsLayer { }.drawBehind {
        drawRect(Bg)
        val w = size.width; val h = size.height
        drawRect(Brush.radialGradient(listOf(ca.copy(alpha = 0.85f), ca.copy(alpha = 0.25f), Color.Transparent), center = Offset(w * 0.8f, h * 0.02f), radius = w * 0.62f))
        drawRect(Brush.radialGradient(listOf(cb.copy(alpha = 0.7f), Color.Transparent), center = Offset(w * 0.06f, h * 1.02f), radius = w * 0.5f))
    })
}
