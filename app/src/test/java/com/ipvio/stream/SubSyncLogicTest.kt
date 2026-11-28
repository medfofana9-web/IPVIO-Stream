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

    /** Lines as in a film: 1–3.5 s long, 0.4–5 s apart (irregular; evenly spaced lines would match one line over). */
    private val cues = run {
        val rnd = java.util.Random(11)
        var t = 5_000L
        (0 until 120).map { val a = t; val b = a + 1_000L + rnd.nextInt(2_500); t = b + 400L + rnd.nextInt(4_600); a to b }
    }

    @Test fun findsAMoveOfSeveralSeconds() {
        val sp = speechFor(cues, 120_000L, 7_340L, 1.0)
        val fit = SubSyncLogic.align(cues, sp, 120_000L, win)!!
        assertEquals(1.0, fit.scale, 0.0)
        assertTrue("offset ${fit.offsetMs}", kotlin.math.abs(fit.offsetMs - 7_340L) <= 40)
        assertTrue("clarity ${fit.clarity}", SubSyncLogic.clear(fit))
    }

    @Test fun findsAFrameRateStretch() {
        val k = 25.0 / 23.976
        val sp = speechFor(cues, 200_000L, -2_000L, k)
        val fit = SubSyncLogic.align(cues, sp, 200_000L, win)!!
        assertEquals(k, fit.scale, 1e-9)
        assertTrue("offset ${fit.offsetMs}", kotlin.math.abs(fit.offsetMs + 2_000L) <= 40)
    }

    @Test fun noiseIsNotAClearMatch() {
        val rnd = java.util.Random(7)
        val sp = IntArray(75_000 / win) { if (rnd.nextInt(3) == 0) 1 else -1 }
        val fit = SubSyncLogic.align(cues, sp, 120_000L, win)
        assertTrue("clarity ${fit?.clarity}", !SubSyncLogic.clear(fit))
    }

    @Test fun loudnessToSpeechFillsPausesBetweenWords() {
        // 1 s blocks of voice with a 100 ms pause inside, 2 s of quiet between lines
        val e = FloatArray(1_000) { i ->
            val inLine = i % 150 < 50
            val wordGap = i % 150 in 20..24
            if (inLine && !wordGap) 0.05f else 0.0005f
        }
        val s = SubSyncLogic.speech(e)
        assertEquals(1, s[0]); assertEquals(1, s[22])   // pause between words counts as speech
        assertEquals(-1, s[100])                       // the quiet between lines does not
    }

    @Test fun aSteadySignalHasNoSpeech() {
        assertTrue(SubSyncLogic.speech(FloatArray(500) { 0.01f }).all { it == -1 })
    }

    @Test fun voiceBandFilterPassesVoicesNotRumble() {
        fun gain(hz: Double): Double {
            val band = SubSyncLogic.Band(48_000)
            var e = 0.0
            for (i in 0 until 48_000) { val y = band.step(Math.sin(2 * Math.PI * hz * i / 48_000)); if (i > 24_000) e += y * y }
            return Math.sqrt(e / 24_000 * 2)
        }
        assertTrue("1 kHz ${gain(1_000.0)}", gain(1_000.0) > 0.9)
        assertTrue("50 Hz ${gain(50.0)}", gain(50.0) < 0.1)
        assertTrue("12 kHz ${gain(12_000.0)}", gain(12_000.0) < 0.15)
    }

    /**
     * The whole chain as on the TV: stereo at 8 kHz, a voice in the centre (noise bursts in the voice band) where the
     * lines are, under wide music twice as loud (different noise left and right), subtitles 3.2 s late.
     */
    @Test fun voicesInTheCentreAreFoundUnderWideMusic() {
        val rate = 8_000
        val start = 60_000L
        val secs = 75
        val rnd = java.util.Random(3)
        val mid = SubSyncLogic.Band(rate); val side = SubSyncLogic.Band(rate)
        val perWin = rate * win / 1000
        val energies = FloatArray(secs * 1000 / win)
        var am = 0.0; var asd = 0.0; var k = 0; var w = 0
        for (i in 0 until secs * rate) {
            val t = start + i * 1000L / rate
            val talking = cues.any { (a, b) -> t >= a && t < b - 300 }
            val voice = if (talking) rnd.nextGaussian() * 0.2 else 0.0
            val l = voice + rnd.nextGaussian() * 0.4
            val r = voice + rnd.nextGaussian() * 0.4
            val m = mid.step((l + r) / 2); val s = side.step((l - r) / 2)
            am += m * m; asd += s * s; k++
            if (k == perWin) { energies[w++] = SubSyncLogic.dialogue(am / k, asd / k); am = 0.0; asd = 0.0; k = 0 }
        }
        val late = cues.map { (a, b) -> a + 3_200L to b + 3_200L }
        val fit = SubSyncLogic.align(late, SubSyncLogic.speech(energies), start, win)!!
        assertEquals(1.0, fit.scale, 0.0)
        assertTrue("offset ${fit.offsetMs}", kotlin.math.abs(fit.offsetMs + 3_200L) <= 60)
        assertTrue("clarity ${fit.clarity}", SubSyncLogic.clear(fit))
    }

    @Test fun filterCoefficientsMatchButterworth() {
        // scipy.signal.butter(2, 250, 'high', fs=48000)
        val c = SubSyncLogic.coefficients(250.0, 48_000, high = true)
        val want = doubleArrayOf(0.977125627, -1.954251255, 0.977125627, -1.953727949, 0.95477456)
        for (i in want.indices) assertEquals(want[i], c[i], 1e-8)
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
