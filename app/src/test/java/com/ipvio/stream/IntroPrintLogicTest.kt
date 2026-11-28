package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * Intro by sound: three made-up episodes share a 30 s theme (gliding tones) between different noise (the story). The
 * shared stretch is found in two of them, and the third recognises it a few seconds after the theme starts.
 */
class IntroPrintLogicTest {
    private val rate = 8000

    private fun theme(sec: Int): FloatArray = FloatArray(sec * rate) { i ->
        val t = i.toDouble() / rate
        (0.45 * sin(2 * PI * t * (420 + 160 * sin(1.3 * t) + 90 * sin(0.37 * t))) +
            0.3 * sin(2 * PI * t * (830 + 120 * sin(0.71 * t) + 60 * sin(2.3 * t)))).toFloat()
    }

    private fun noise(sec: Double, seed: Long): FloatArray {
        val r = java.util.Random(seed)
        var y = 0.0
        return FloatArray((sec * rate).toInt()) { y = 0.97 * y + r.nextGaussian(); (0.05 * y).toFloat() }
    }

    private fun codes(vararg parts: FloatArray): IntArray {
        val e = IntroPrintLogic.Extractor(rate)
        val out = ArrayList<Int>()
        for (p in parts) for (x in p) { val c = e.push(x); if (c != IntroPrintLogic.PENDING) out.add(c) }
        return out.toIntArray()
    }

    private val th = theme(30)
    private val a = codes(noise(10.0, 1), th, noise(20.0, 2))
    private val b = codes(noise(20.05, 3), th, noise(15.0, 4))
    private val c = codes(noise(15.0, 5), th, noise(10.0, 6))

    @Test fun findsTheSharedThemeInTwoEpisodes() {
        val r = IntroPrintLogic.common(a, b)
        assertNotNull(r)
        val (ra, rb) = r!!
        // Theme at 10–40 s in A and 20.05–50.05 s in B (frame k ≈ 0.5 s + k × 100 ms)
        assertTrue("start in A " + ra.first, ra.first in 90..125)
        assertTrue("length " + (ra.last - ra.first + 1), (ra.last - ra.first + 1) in 255..305)
        assertTrue("shift " + (rb.first - ra.first), (rb.first - ra.first) in 99..102)
    }

    @Test fun recognisesTheIntroInTheNextEpisode() {
        val (_, rb) = IntroPrintLogic.common(a, b)!!
        val tpl = b.copyOfRange(rb.first, rb.last + 1)
        // 4 s heard, ending about 6 s into the theme (which starts at 15 s in C)
        val from = 170
        val j = IntroPrintLogic.locate(tpl, c.copyOfRange(from, from + 40))
        assertNotNull(j)
        val start = from - j!!
        assertTrue("intro start frame $start", start in 140..180)
        // Before the theme: nothing
        assertNull(IntroPrintLogic.locate(tpl, c.copyOfRange(40, 80)))
    }

    @Test fun differentSoundOrASteadyToneIsNoIntro() {
        assertNull(IntroPrintLogic.common(codes(noise(60.0, 7)), codes(noise(60.0, 8))))
        val tone = FloatArray(60 * rate) { (0.3 * sin(2 * PI * 440 * it / rate)).toFloat() }
        assertNull(IntroPrintLogic.common(codes(tone), codes(tone)))
    }

    @Test fun silenceGivesNoCodes() {
        val s = codes(FloatArray(5 * rate))
        assertTrue(s.isNotEmpty())
        assertTrue(s.all { it == IntroPrintLogic.NONE })
    }

    @Test fun fftOfASineHasItsPeakInTheRightBin() {
        val n = 1024
        val re = DoubleArray(n) { sin(2 * PI * 64 * it / n) }
        val im = DoubleArray(n)
        IntroPrintLogic.fft(re, im)
        val mags = DoubleArray(n / 2) { re[it] * re[it] + im[it] * im[it] }
        assertEquals(64, mags.indices.maxByOrNull { mags[it] })
    }
}
