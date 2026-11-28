package com.ipvio.stream

import android.content.Context
import androidx.media3.common.C
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Listens to the decoded sound for automatic subtitle sync: how much dialogue there is every 20 ms for [SECONDS] from a
 * known spot (the player reopens the stream there when subtitles are added, see [begin]). Dialogue is told apart from
 * music and effects the way films are mixed: voices sit in the centre, so only the voice band (250–3500 Hz) of the
 * centre is kept – the centre channel of 5.1 sound, or for stereo the part both sides share (mid energy minus side
 * energy, see [SubSyncLogic.Band]). Nothing is stored or sent; only these numbers are kept in memory. Sound passed
 * straight to the TV (Dolby bitstream) is not decoded here, then nothing is heard and the subtitles keep their timing.
 */
object SpeechTap : TeeAudioProcessor.AudioBufferSink {
    const val WIN_MS = 20
    /** 150 s: tested offline, 75 s gave a clear and right answer about half as often (see SubSyncLogic.align). */
    const val SECONDS = 150
    @Volatile private var wanted = false
    @Volatile private var startMs = 0L
    @Volatile var done: Pair<Long, FloatArray>? = null
        private set
    private var energies = FloatArray(SECONDS * 1000 / WIN_MS)
    private var n = 0
    private var rate = 0; private var channels = 0; private var encoding = 0
    private var accMid = 0.0; private var accSide = 0.0; private var accN = 0; private var perWin = 0
    private var midBand = SubSyncLogic.Band(48_000); private var sideBand = SubSyncLogic.Band(48_000)

    /** Next audio after the coming flush starts at media time [mediaMs] (call right before the player reopens there). */
    @Synchronized fun begin(mediaMs: Long) { wanted = true; startMs = mediaMs; done = null; n = 0; reset() }
    @Synchronized fun stop() { wanted = false }
    /** Listening for a sync right now. */
    val listening: Boolean get() = wanted

    private fun reset() { accMid = 0.0; accSide = 0.0; accN = 0; midBand.reset(); sideBand.reset() }

    @Synchronized override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        rate = sampleRateHz; channels = channelCount.coerceAtLeast(1); this.encoding = encoding
        perWin = (sampleRateHz * WIN_MS / 1000).coerceAtLeast(1)
        if (midBand.rate != sampleRateHz && sampleRateHz > 0) { midBand = SubSyncLogic.Band(sampleRateHz); sideBand = SubSyncLogic.Band(sampleRateHz) }
        n = 0; reset()
    }

    @Synchronized override fun handleBuffer(buffer: ByteBuffer) {
        if (!wanted || perWin == 0) return
        val b = buffer.duplicate().order(ByteOrder.nativeOrder())
        val pcm16 = encoding == C.ENCODING_PCM_16BIT
        val flt = encoding == C.ENCODING_PCM_FLOAT
        if (!pcm16 && !flt) return
        val bytes = if (pcm16) 2 else 4
        val frameBytes = channels * bytes
        val frame = DoubleArray(channels)
        while (b.remaining() >= frameBytes) {
            for (ch in 0 until channels) frame[ch] = if (pcm16) b.short / 32768.0 else b.float.toDouble()
            // 5.1 and more: the centre channel (FL FR FC …) carries the dialogue; stereo: mid and side; mono: as is
            val mid: Double; val side: Double
            when {
                channels >= 6 -> { mid = frame[2]; side = 0.0 }
                channels >= 2 -> { mid = (frame[0] + frame[1]) / 2; side = (frame[0] - frame[1]) / 2 }
                else -> { mid = frame[0]; side = 0.0 }
            }
            val m = midBand.step(mid); val s = sideBand.step(side)
            accMid += m * m; accSide += s * s; accN++
            if (accN >= perWin) {
                if (n < energies.size) energies[n++] = SubSyncLogic.dialogue(accMid / accN, accSide / accN)
                accMid = 0.0; accSide = 0.0; accN = 0
                if (n >= energies.size) { done = startMs to energies.copyOf(); wanted = false; return }
            }
        }
    }
}

/** Pure part of the automatic sync (tested in SubSyncLogicTest). */
object SubSyncLogic {
    /**
     * Voice band 250–3500 Hz: a 2nd-order Butterworth high-pass then low-pass (bilinear, as scipy's butter), so low
     * rumble, bass and hiss do not count as speech.
     */
    class Band(val rate: Int) {
        private val hp = coefficients(250.0, rate, high = true)
        // Below the Nyquist limit at any rate (8 kHz sound keeps up to 3.6 kHz)
        private val lp = coefficients(minOf(3500.0, rate * 0.45), rate, high = false)
        private val z = DoubleArray(4)
        fun reset() = z.fill(0.0)
        fun step(x: Double): Double {
            // Transposed direct form II, two sections
            val y1 = hp[0] * x + z[0]
            z[0] = hp[1] * x - hp[3] * y1 + z[1]; z[1] = hp[2] * x - hp[4] * y1
            val y2 = lp[0] * y1 + z[2]
            z[2] = lp[1] * y1 - lp[3] * y2 + z[3]; z[3] = lp[2] * y1 - lp[4] * y2
            return y2
        }
    }

    /** b0, b1, b2, a1, a2 of a 2nd-order Butterworth filter at [f] Hz (a0 = 1). */
    fun coefficients(f: Double, rate: Int, high: Boolean): DoubleArray {
        val k = kotlin.math.tan(Math.PI * f / rate)
        val q = Math.sqrt(2.0)
        val norm = 1 / (1 + q * k + k * k)
        val a1 = 2 * (k * k - 1) * norm
        val a2 = (1 - q * k + k * k) * norm
        return if (high) doubleArrayOf(norm, -2 * norm, norm, a1, a2)
        else doubleArrayOf(k * k * norm, 2 * k * k * norm, k * k * norm, a1, a2)
    }

    /**
     * Dialogue in one window from the voice-band energy of mid and side: what both stereo sides share. Never below 5 %
     * of the mid energy: wide sound (music, rain, crowds) leaves mid minus side near or below 0, and a run of zeros would
     * pull the quiet floor of [speech] down so far that any noise counted as a voice.
     */
    fun dialogue(mid: Double, side: Double): Float = maxOf(mid - side, mid * 0.05, 0.0).toFloat()

    /**
     * Speech or not per window. Measured on a log scale between the quiet floor (10th percentile) and the loud end
     * (97th): above 45 % of that span is speech. Blips under 60 ms are dropped and pauses up to 200 ms between words are
     * filled, so a line counts as one.
     * (The old rule, louder than 1.5 × the median, marked only half of a dialogue-heavy scene as speech.)
     */
    fun speech(energies: FloatArray): IntArray {
        if (energies.isEmpty()) return IntArray(0)
        val lg = DoubleArray(energies.size) { Math.log10(energies[it].toDouble() + 1e-12) }
        val sorted = lg.sortedArray()
        val lo = sorted[(sorted.size * 10 / 100).coerceIn(0, sorted.size - 1)]
        val hi = sorted[(sorted.size * 97 / 100).coerceIn(0, sorted.size - 1)]
        // A flat signal (silence, a steady tone) has no speech to find
        if (hi - lo < 0.5) return IntArray(energies.size) { -1 }
        val thr = lo + 0.45 * (hi - lo)
        val on = BooleanArray(energies.size) { lg[it] > thr }
        // Blips shorter than 60 ms are noise, not words: dropped first, or the pause filling below would join a blip
        // just before a line to it and move the line's start early
        var runStart = -1
        for (i in 0..on.size) {
            val v = i < on.size && on[i]
            if (v && runStart < 0) runStart = i
            if (!v && runStart >= 0) { if (i - runStart < MIN_WINS) for (j in runStart until i) on[j] = false; runStart = -1 }
        }
        var last = -1
        for (i in on.indices) if (on[i]) {
            if (last >= 0 && i - last - 1 in 1..GAP_WINS) for (j in last + 1 until i) on[j] = true
            last = i
        }
        return IntArray(energies.size) { if (on[it]) 1 else -1 }
    }
    private const val GAP_WINS = 10
    private const val MIN_WINS = 3

    /** [clarity]: how far the best move stands above every move more than 1 s away, as a share of the scored subtitle time. */
    class Fit(val offsetMs: Long, val scale: Double, val score: Double, val clarity: Double)

    /**
     * A match clear enough to use. 0.2 with the onset scoring of [align]: offline (real speech over real music, 108
     * mixes, 150 s heard) 86 synced within 150 ms, 8 within 400 ms, none wrong, 14 left alone; at 0.15 one moved 8 s
     * wrong. The old rule (whole lines, loudness, 75 s) synced 17 right and 7 wrong.
     */
    fun clear(f: Fit?): Boolean = f != null && f.clarity >= 0.2

    /** Frame-rate stretches worth trying (subtitle made for another release of the same video). */
    val SCALES = listOf(1.0, 25.0 / 23.976, 23.976 / 25.0, 24.0 / 23.976, 23.976 / 24.0, 25.0 / 24.0, 24.0 / 25.0)

    /** Scored part of a line: its first 1.2 s (subtitle makers time the start to the voice; the end is reading time). */
    const val HEAD_MS = 1_200L
    /** Before a line starts there should be no voice: speech in these 400 ms counts against the move. */
    const val PRE_MS = 400L
    /** Another frame rate must fit at least 10 % better than none: 1.001 and 1.0 score almost the same. */
    const val STRETCH_MARGIN = 1.10

    /**
     * Best move of the subtitle [cues] (start, end in ms) against [speech] (+1/-1 per [winMs], from [startMs]): for each
     * stretch and every move within ±[rangeMs], how much of the lines' first [HEAD_MS] falls on speech minus on no
     * speech, minus the speech in the [PRE_MS] before each line. Scoring the starts, not whole lines, keeps the long
     * reading time after a voice from pulling the subtitles early or late. The best stands out (see [Fit.clarity], [clear]).
     */
    fun align(cues: List<Pair<Long, Long>>, speech: IntArray, startMs: Long, winMs: Int, rangeMs: Long = 60_000, scales: List<Double> = SCALES): Fit? {
        if (speech.size < 500 || cues.isEmpty()) return null
        val prefix = IntArray(speech.size + 1)
        for (i in speech.indices) prefix[i + 1] = prefix[i] + speech[i]
        val endMs = startMs + speech.size.toLong() * winMs
        fun idx(t: Long) = ((t - startMs) / winMs).toInt().coerceIn(0, speech.size)
        var best: Fit? = null
        var bestCmp = 0.0
        for (k in scales) {
            val scaled = cues.map { (a, b) -> Math.round(a * k) to Math.round(b * k) }
                .filter { (a, b) -> b + rangeMs > startMs && a - rangeMs < endMs }
                .map { (a, b) -> a to minOf(b, a + HEAD_MS) }
            if (scaled.isEmpty()) continue
            val steps = (2 * rangeMs / winMs).toInt()
            val scores = DoubleArray(steps + 1)
            for (si in 0..steps) {
                val d = -rangeMs + si.toLong() * winMs
                var sc = 0
                for ((a, b) in scaled) {
                    val i0 = idx(a + d); val i1 = idx(b + d); val p0 = idx(a + d - PRE_MS)
                    if (i1 > i0) sc += prefix[i1] - prefix[i0]
                    if (i0 > p0) sc -= prefix[i0] - prefix[p0]
                }
                scores[si] = sc.toDouble()
            }
            var bi = 0
            for (i in scores.indices) if (scores[i] > scores[bi]) bi = i
            val far = scores.indices.filter { kotlin.math.abs(it - bi) > 1000 / winMs }.maxOfOrNull { scores[it] } ?: 0.0
            // Scored subtitle time inside the heard window at the best move
            val d = -rangeMs + bi.toLong() * winMs
            var covered = 0
            for ((a, b) in scaled) { val i0 = idx(a + d); val i1 = idx(b + d); if (i1 > i0) covered += i1 - i0 }
            val clarity = if (covered < 100 || scores[bi] < covered * 0.25) 0.0 else (scores[bi] - far) / covered
            val f = Fit(d, k, scores[bi], clarity)
            val cmp = if (k == 1.0) scores[bi] * STRETCH_MARGIN else scores[bi]
            if (best == null || cmp > bestCmp) { best = f; bestCmp = cmp }
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
