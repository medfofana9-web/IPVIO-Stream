package com.ipvio.stream

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Shows the reason of the last unexpected close for 25 seconds at the bottom of the screen, once. */
@Composable
fun LastCrashNotice() {
    val ctx = LocalContext.current
    var msg by remember { mutableStateOf(Mem.takeRecent(ctx)) }
    val m = msg ?: return
    LaunchedEffect(m) { delay(25_000L); msg = null }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier.padding(bottom = 28.dp).widthIn(max = 900.dp)
                .background(Color(0xF0281414), RoundedCornerShape(12.dp))
                .padding(16.dp)
        ) {
            Text("The app closed unexpectedly last time. Take a photo of this message to get it fixed:\n$m",
                color = Color.White, fontSize = 16.sp, raw = true)
        }
    }
}
