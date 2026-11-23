package com.ipvio.stream

import android.annotation.SuppressLint
import android.graphics.Color as AColor
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

/** A trailer stops after the remote has been untouched this long, and starts again with the next key press. */
private const val IDLE_STOP_MS = 3 * 60_000L
private val firstShown = System.currentTimeMillis()
private fun remoteIdle(): Boolean = System.currentTimeMillis() - maxOf(Busy.last, firstShown) >= IDLE_STOP_MS

/**
 * Netflix-style trailer in the big picture: after [delayMs] on the same title the YouTube trailer fades in over the
 * picture, muted, without controls, and loops while the remote is in use
 * (it stops after [IDLE_STOP_MS] without a key press). It never takes the remote focus. Off on low-memory sticks unless
 * switched on under Settings > View, and only while [active] (the big picture has the focus or is in view).
 *
 * [onPlaying] tells the caller to pause the slideshow while the trailer runs.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HeroTrailer(key: String?, active: Boolean, modifier: Modifier, delayMs: Long = 4_000L, onPlaying: (Boolean) -> Unit = {}) {
    val ctx = LocalContext.current
    if (key.isNullOrBlank() || !General.trailersOn(ctx)) { LaunchedEffect(Unit) { onPlaying(false) }; return }
    var show by remember(key) { mutableStateOf(false) }
    val alpha = remember(key) { Animatable(0f) }
    LaunchedEffect(key, active) {
        if (!active) { alpha.snapTo(0f); show = false; onPlaying(false); return@LaunchedEffect }
        while (true) {
            // Nobody at the remote: no trailer until the next key press
            while (remoteIdle()) delay(1_000)
            delay(delayMs)
            show = true
            // Give the player a moment to start, so the fade shows moving pictures instead of YouTube's black loading frame
            delay(1_600)
            onPlaying(true)
            alpha.animateTo(1f, tween(if (Look.motion == 0) 0 else 700))
            // The looping trailer kept the GPU busy for as long as Home stayed open (95 % GPU time, 70 % slow frames
            // on an onn box left idle): stop it once the remote has been untouched for a while
            while (!remoteIdle()) delay(5_000)
            alpha.animateTo(0f, tween(if (Look.motion == 0) 0 else 700))
            show = false
            onPlaying(false)
        }
    }
    DisposableEffect(key) { onDispose { onPlaying(false) } }
    if (!show) return
    AndroidView(
        factory = { c ->
            WebView(c).apply {
                isFocusable = false
                isFocusableInTouchMode = false
                descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                setBackgroundColor(AColor.BLACK)
                settings.javaScriptEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.domStorageEnabled = true
                webChromeClient = WebChromeClient()
                webViewClient = WebViewClient()
                val k = key.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
                // Cropped a little (scale 1.2) so YouTube's title bar and logo stay outside the card
                val html = """<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
                    <style>html,body{margin:0;height:100%;background:#000;overflow:hidden}iframe{position:absolute;top:50%;left:50%;
                    width:120%;height:120%;transform:translate(-50%,-50%);border:0;pointer-events:none}</style></head><body>
                    <iframe src="https://www.youtube-nocookie.com/embed/$k?autoplay=1&mute=1&controls=0&loop=1&playlist=$k&modestbranding=1&playsinline=1&rel=0&iv_load_policy=3&disablekb=1&fs=0"
                    allow="autoplay; encrypted-media"></iframe></body></html>"""
                loadDataWithBaseURL("https://fof.tv/", html, "text/html", "utf-8", null)
            }
        },
        onRelease = { it.stopLoading(); it.loadUrl("about:blank"); it.destroy() },
        modifier = modifier.graphicsLayer { this.alpha = alpha.value }
    )
}
