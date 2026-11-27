package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubSyncLogicTest {
    private val win = 20

    /** Speech exactly where [cues] (moved by [shift], stretched by [k]) are, in a 75 s window from [start]. */
    private fun speechFor(cues: List<Pair<Long, Long>>, start: Long, shift: Long, k: Double): IntArray {
        val n = 75_000 / win
        return IntArray(n) { i ->
            val t = start + i.toLong() * win
            if (cues.any { (a, b) -> t >= Math.round(a * k) + shift && t < Math.round(b * k) + shift }) 1 else -1
        }
    }

    private val cues = (0 until 120).map { i -> val a = 5_000L + i * 4_100L + (i * 977 % 1300); a to a + 1_200L + (i * 311 % 900) }

    @Test fun findsAMoveOfSeveralSeconds() {
        val sp = speechFor(cues, 120_000L, 7_340L, 1.0)
        val fit = SubSyncLogic.align(cues, sp, 120_000L, win)!!
        assertEquals(1.0, fit.scale, 0.0)
        assertTrue("offset ${fit.offsetMs}", kotlin.math.abs(fit.offsetMs - 7_340L) <= 40)
        assertTrue("z ${fit.z}", fit.z > 5)
    }

    @Test fun findsAFrameRateStretch() {
        val k = 25.0 / 23.976
        val sp = speechFor(cues, 200_000L, -2_000L, k)
        val fit = SubSyncLogic.align(cues, sp, 200_000L, win)!!
        assertEquals(k, fit.scale, 1e-9)
        assertTrue("offset ${fit.offsetMs}", kotlin.math.abs(fit.offsetMs + 2_000L) <= 40)
    }

    @Test fun loudnessToSpeech() {
        val e = FloatArray(100) { if (it % 2 == 0) 0.001f else 0.1f }
        val s = SubSyncLogic.speech(e)
        assertEquals(-1, s[0]); assertEquals(1, s[1])
    }

    @Test fun openSubtitlesFingerprint() {
        // Zero bytes: the fingerprint is the size alone
        val h = OnlineSubsLogic.osHash(200_000L, ByteArray(65536), ByteArray(65536))
        assertEquals("0000000000030d40", h.hash)
        // One little-endian 1 in the first chunk adds 1
        val head = ByteArray(65536).also { it[0] = 1 }
        assertEquals("0000000000030d41", OnlineSubsLogic.osHash(200_000L, head, ByteArray(65536)).hash)
    }

    @Test fun exactMatchesComeFirstAndHashGoesIntoTheAddress() {
        val all = listOf(OnlineSubsLogic.Sub("https://s/1", "en"), OnlineSubsLogic.Sub("https://s/2", "en", exact = true))
        assertEquals("https://s/2", OnlineSubsLogic.pick(all, "en", 2).first().url)
        assertEquals("https://opensubtitles-v3.strem.io/subtitles/movie/tt0454848/videoHash=00ff&videoSize=5.json",
            OnlineSubsLogic.url("tt0454848", false, 0, 0, OnlineSubsLogic.VideoHash("00ff", 5)))
        assertEquals(listOf(12_000L to 14_000L), OnlineSubsLogic.spans("1\n00:00:10,000 --> 00:00:11,000\n[x]\n\n2\n00:00:12,000 --> 00:00:14,000\nHello there\n"))
    }
}
