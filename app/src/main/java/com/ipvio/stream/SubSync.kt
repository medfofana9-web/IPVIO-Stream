package com.ipvio.stream

import android.content.Context
import androidx.media3.common.C
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Listens to the decoded sound for automatic subtitle sync: loudness every 20 ms for [SECONDS] from a known spot (the
 * player reopens the stream there when subtitles are added, see [begin]). Nothing is stored or sent; only the loudness
 * numbers are kept in memory. Sound passed straight to the TV (Dolby bitstream) is not decoded here, then nothing is
 * heard and the subtitles keep their timing.
 */
object SpeechTap : TeeAudioProcessor.AudioBufferSink {
    const val WIN_MS = 20
    const val SECONDS = 75
    @Volatile private var wanted = false
    @Volatile private var startMs = 0L
    @Volatile var done: Pair<Long, FloatArray>? = null
        private set
    private var energies = FloatArray(SECONDS * 1000 / WIN_MS)
    private var n = 0
    private var rate = 0; private var channels = 0; private var encoding = 0
    private var acc = 0.0; private var accN = 0; private var perWin = 0

    /** Next audio after the coming flush starts at media time [mediaMs] (call right before the player reopens there). */
    @Synchronized fun begin(mediaMs: Long) { wanted = true; startMs = mediaMs; done = null; n = 0; acc = 0.0; accN = 0 }
    @Synchronized fun stop() { wanted = false }
    /** Listening for a sync right now. */
    val listening: Boolean get() = wanted

    @Synchronized override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        rate = sampleRateHz; channels = channelCount.coerceAtLeast(1); this.encoding = encoding
        perWin = (sampleRateHz * WIN_MS / 1000).coerceAtLeast(1)
        n = 0; acc = 0.0; accN = 0
    }

    @Synchronized override fun handleBuffer(buffer: ByteBuffer) {
        if (!wanted || perWin == 0) return
        val b = buffer.duplicate().order(ByteOrder.nativeOrder())
        val pcm16 = encoding == C.ENCODING_PCM_16BIT
        val flt = encoding == C.ENCODING_PCM_FLOAT
        if (!pcm16 && !flt) return
        val frameBytes = channels * (if (pcm16) 2 else 4)
        while (b.remaining() >= frameBytes) {
            var v = 0.0
            for (ch in 0 until channels) v += if (pcm16) b.short / 32768.0 else b.float.toDouble()
            v /= channels
            acc += v * v; accN++
            if (accN >= perWin) {
                if (n < energies.size) energies[n++] = (acc / accN).toFloat()
                acc = 0.0; accN = 0
                if (n >= energies.size) { done = startMs to energies.copyOf(); wanted = false; return }
            }
        }
    }
}

/** Pure part of the automatic sync (tested in SubSyncLogicTest). */
object SubSyncLogic {
    /** Speech or not per window: louder than 1.5 × the median loudness (music beds and silence stay below more often). */
    fun speech(energies: FloatArray): IntArray {
        if (energies.isEmpty()) return IntArray(0)
        val sorted = energies.sorted()
        val med = sorted[sorted.size / 2].coerceAtLeast(1e-7f)
        return IntArray(energies.size) { if (energies[it] > med * 1.5f) 1 else -1 }
    }

    /** [clarity]: how far the best move stands above every move more than 1 s away, as a share of the subtitle time. */
    class Fit(val offsetMs: Long, val scale: Double, val score: Double, val clarity: Double)

    /** A match clear enough to use: a quarter of the subtitle time on speech and 6 % above any other move. */
    fun clear(f: Fit?): Boolean = f != null && f.clarity >= 0.06

    /** Frame-rate stretches worth trying (subtitle made for another release of the same video). */
    val SCALES = listOf(1.0, 25.0 / 23.976, 23.976 / 25.0, 24.0 / 23.976, 23.976 / 24.0, 25.0 / 24.0, 24.0 / 25.0)

    /**
     * Best move of the subtitle [cues] (start, end in ms) against [speech] (+1/-1 per [winMs], from [startMs]): for each
     * stretch and every move within ±[rangeMs], how much subtitle time falls on speech minus on no speech. the best stands out (see [Fit.clarity], [clear]).
     */
    fun align(cues: List<Pair<Long, Long>>, speech: IntArray, startMs: Long, winMs: Int, rangeMs: Long = 60_000, scales: List<Double> = SCALES): Fit? {
        if (speech.size < 500 || cues.isEmpty()) return null
        val prefix = IntArray(speech.size + 1)
        for (i in speech.indices) prefix[i + 1] = prefix[i] + speech[i]
        val endMs = startMs + speech.size.toLong() * winMs
        var best: Fit? = null
        for (k in scales) {
            val scaled = cues.map { (a, b) -> Math.round(a * k) to Math.round(b * k) }
                .filter { (a, b) -> b + rangeMs > startMs && a - rangeMs < endMs }
            if (scaled.isEmpty()) continue
            val steps = (2 * rangeMs / winMs).toInt()
            val scores = DoubleArray(steps + 1)
            for (si in 0..steps) {
                val d = -rangeMs + si.toLong() * winMs
                var sc = 0
                for ((a, b) in scaled) {
                    val i0 = (((a + d) - startMs) / winMs).toInt().coerceIn(0, speech.size)
                    val i1 = (((b + d) - startMs) / winMs).toInt().coerceIn(0, speech.size)
                    if (i1 > i0) sc += prefix[i1] - prefix[i0]
                }
                scores[si] = sc.toDouble()
            }
            var bi = 0
            for (i in scores.indices) if (scores[i] > scores[bi]) bi = i
            val far = scores.indices.filter { kotlin.math.abs(it - bi) > 1000 / winMs }.maxOfOrNull { scores[it] } ?: 0.0
            // Subtitle time inside the heard window at the best move
            val d = -rangeMs + bi.toLong() * winMs
            var covered = 0
            for ((a, b) in scaled) {
                val i0 = (((a + d) - startMs) / winMs).toInt().coerceIn(0, speech.size)
                val i1 = (((b + d) - startMs) / winMs).toInt().coerceIn(0, speech.size)
                if (i1 > i0) covered += i1 - i0
            }
            val clarity = if (covered < 100 || scores[bi] < covered * 0.25) 0.0 else (scores[bi] - far) / covered
            val f = Fit(d, k, scores[bi], clarity)
            if (best == null || f.score > best.score) best = f
        }
        return best
    }
}

/**
 * Subtitle timing remembered per show and source: a show's episodes from the same addon usually share the same delay,
 * so a sync (by hand or automatic) on one episode is used for the next ones. Prefs "fof_subsync", at most 200 shows.
 */
object SubSyncMemory {
    private const val PREFS = "fof_subsync"
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun key(showId: String, source: String) = (showId + "|" + source).take(160)
    fun get(c: Context, key: String): Pair<Long, Double>? = sp(c).getString(key, null)?.split('|')?.let { p ->
        val ms = p.getOrNull(0)?.toLongOrNull() ?: return null
        ms to (p.getOrNull(1)?.toDoubleOrNull() ?: 1.0)
    }
    fun put(c: Context, key: String, ms: Long, scale: Double) {
        val p = sp(c)
        val e = p.edit().putString(key, "$ms|$scale")
        if (p.all.size > 200) p.all.keys.take(20).forEach { e.remove(it) }
        e.apply()
    }
}
