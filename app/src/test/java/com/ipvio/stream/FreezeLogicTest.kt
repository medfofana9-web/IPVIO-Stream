package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FreezeLogicTest {
    private fun e(cls: String, m: String, file: String?, line: Int) = StackTraceElement(cls, m, file, line)

    @Test fun appFramesAreReportedInnermostFirstWithTheBlockingCall() {
        val trace = listOf(
            e("java.io.FileInputStream", "readBytes", "FileInputStream.java", 0),
            e("com.ipvio.stream.PlaylistStore", "fromLocal", "PlaylistStore.kt", 812),
            e("com.ipvio.stream.HomeScreenKt", "HomeScreen", "HomeScreen.kt", 190),
            e("android.os.Looper", "loop", "Looper.java", 200)
        )
        val s = FreezeLogic.summarize(trace)
        assertEquals("[FileInputStream.readBytes] PlaylistStore.kt:812 fromLocal < HomeScreen.kt:190 HomeScreen", s)
    }

    @Test fun withoutAppFramesTheFirstNonPlumbingFramesAreUsed() {
        val trace = listOf(
            e("android.os.MessageQueue", "nativePollOnce", "MessageQueue.java", -2),
            e("androidx.compose.ui.platform.AndroidComposeView", "dispatchDraw", null, 10),
            e("android.os.Looper", "loop", "Looper.java", 200)
        )
        assertTrue(FreezeLogic.summarize(trace).startsWith("AndroidComposeView:10 dispatchDraw"))
    }

    @Test fun emptyTraceGivesNothing() {
        assertEquals("", FreezeLogic.summarize(emptyList()))
    }
}
