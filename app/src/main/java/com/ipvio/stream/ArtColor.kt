package com.ipvio.stream

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import coil.Coil
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * The mood color of a title's artwork: the picture is read at 24×24 pixels, colorful pixels count more than grey ones,
 * and the result is turned into a deep, calm tone that sits under the artwork (no bright tints over text).
 */
object ArtColor {
    private val cache = ConcurrentHashMap<String, Int>()

    /** Pure: weighted average of ARGB pixels → deep tone (hue kept, saturation ≤ 0.65, brightness 0.34). 0 = none. */
    fun mood(px: IntArray): Int {
        var r = 0.0; var g = 0.0; var b = 0.0; var w = 0.0
        val hsv = FloatArray(3)
        for (p in px) {
            val pr = (p shr 16) and 0xFF; val pg = (p shr 8) and 0xFF; val pb = p and 0xFF
            val mx = maxOf(pr, pg, pb); val mn = minOf(pr, pg, pb)
            val s = if (mx == 0) 0.0 else (mx - mn).toDouble() / mx
            val v = mx / 255.0
            val wt = 0.05 + s * s * v
            r += pr * wt; g += pg * wt; b += pb * wt; w += wt
        }
        if (w <= 0) return 0
        rgbToHsv((r / w).toInt(), (g / w).toInt(), (b / w).toInt(), hsv)
        if (hsv[1] < 0.12f) return 0
        return hsvToRgb(hsv[0], minOf(hsv[1] * 1.15f, 0.65f), 0.34f)
    }

    private fun rgbToHsv(r: Int, g: Int, b: Int, out: FloatArray) {
        val rf = r / 255f; val gf = g / 255f; val bf = b / 255f
        val mx = maxOf(rf, gf, bf); val mn = minOf(rf, gf, bf); val d = mx - mn
        out[0] = when { d == 0f -> 0f; mx == rf -> 60f * (((gf - bf) / d) % 6f); mx == gf -> 60f * ((bf - rf) / d + 2f); else -> 60f * ((rf - gf) / d + 4f) }.let { if (it < 0) it + 360f else it }
        out[1] = if (mx == 0f) 0f else d / mx
        out[2] = mx
    }

    private fun hsvToRgb(h: Float, s: Float, v: Float): Int {
        val c = v * s; val x = c * (1 - kotlin.math.abs((h / 60f) % 2f - 1)); val m = v - c
        val (r, g, b) = when ((h / 60f).toInt() % 6) { 0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x); 3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x) }
        return (0xFF shl 24) or (((r + m) * 255).toInt() shl 16) or (((g + m) * 255).toInt() shl 8) or ((b + m) * 255).toInt()
    }

    suspend fun of(c: Context, url: String): Int {
        cache[url]?.let { return it }
        val v = withContext(Dispatchers.IO) {
            runCatching {
                val req = ImageRequest.Builder(c).data(url).size(24, 24).allowHardware(false).build()
                val bmp = ((Coil.imageLoader(c).execute(req) as? SuccessResult)?.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                    ?: return@runCatching 0
                val sm = if (bmp.width > 32) Bitmap.createScaledBitmap(bmp, 24, 24, true) else bmp
                val px = IntArray(sm.width * sm.height); sm.getPixels(px, 0, sm.width, 0, 0, sm.width, sm.height)
                mood(px)
            }.getOrDefault(0)
        }
        cache[url] = v
        return v
    }
}

/** Soft glow in the artwork's mood color from the lower left (under the text side); fades in when the color is known. */
@Composable
fun MoodGlow(url: String?, modifier: Modifier = Modifier) {
    if (Look.motion == 0 && url == null) return
    val ctx = LocalContext.current
    val target by produceState(0, url) { value = if (url == null) 0 else ArtColor.of(ctx, url) }
    val col by animateColorAsState(if (target == 0) Color.Transparent else Color(target).copy(alpha = 0.55f), tween(700), label = "mood")
    if (col.alpha < 0.01f) return
    Box(modifier.fillMaxSize().background(Brush.radialGradient(listOf(col, Color.Transparent), center = Offset(0f, Float.POSITIVE_INFINITY), radius = 2000f)))
}
