package com.ipvio.stream

import android.content.Context
import androidx.media3.common.C
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

/**
 * Intro found by its sound (27 Nov 2026), the way Plex and Jellyfin's intro skipper do it: while an episode plays, a
 * small sound fingerprint of its first minutes is kept (one 31-bit number per 100 ms, never the sound itself). Two
 * episodes of a season share one long stretch of identical sound: the intro. Once it is known (from two watched episodes,
 * or one episode plus database times), the next episode hears the theme start, "Skip intro" appears by itself, and OK
 * jumps exactly to where the theme ends. Sound sent straight to the TV (Dolby bitstream) is not decoded by the app and
 * cannot be heard; then the databases and the learned Skip intro button remain.
 *
 * Pure part (tested in IntroPrintLogicTest).
 */
object IntroPrintLogic {
    const val HOP_MS = 100
    const val NONE = -1
    /** No frame finished with this sample. */
    const val PENDING = Int.MIN_VALUE
    const val FFT = 4096
    const val BANDS = 32
    /** Two frames are the same sound when at most this many of the 31 bits differ (random sound: about 1 in 200). */
    const val MAX_BITS = 8
    const val MIN_RUN = 150     // 15 s
    const val MAX_RUN = 1800    // 3 min

    /**
     * Fingerprint of mono sound at [rateHz] (about 8 kHz): every 100 ms, the energy of 32 bands between 300 and 3000 Hz
     * over the last 0.5 s; bit b = whether the difference between band b and b+1 grew since the previous frame
     * (Haitsma & Kalker). Independent of loudness; robust to the small time offsets between two copies of the same sound.
     */
    class Extractor(private val rateHz: Int) {
        private val hop = (rateHz * HOP_MS / 1000).coerceAtLeast(1)
        private val ring = FloatArray(FFT)
        private var w = 0
        private var filled = 0
        private var sinceHop = 0
        private val re = DoubleArray(FFT)
        private val im = DoubleArray(FFT)
        private val win = DoubleArray(FFT) { 0.5 - 0.5 * cos(2 * PI * it / (FFT - 1)) }
        private val edges = IntArray(BANDS + 1).also { e ->
            val binHz = rateHz.toDouble() / FFT
            val hi = minOf(3000.0, rateHz * 0.45)
            for (i in 0..BANDS) {
                val f = 300.0 * (hi / 300.0).pow(i.toDouble() / BANDS)
                e[i] = maxOf((f / binHz).toInt(), if (i > 0) e[i - 1] + 1 else 1)
            }
        }
        private val cur = DoubleArray(BANDS)
        private val prev = DoubleArray(BANDS)
        private var hasPrev = false

        /** Adds one sample; returns a code, [NONE] (silence) or [PENDING] (no frame finished). */
        fun push(x: Float): Int {
            ring[w] = x; w = (w + 1) % FFT
            if (filled < FFT) filled++
            if (++sinceHop < hop || filled < FFT) return PENDING
            sinceHop = 0
            return frame()
        }

        private fun frame(): Int {
            var power = 0.0
            for (i in 0 until FFT) {
                val v = ring[(w + i) % FFT].toDouble()
                power += v * v
                re[i] = v * win[i]; im[i] = 0.0
            }
            if (power / FFT < 1e-7) { hasPrev = false; return NONE }
            fft(re, im)
            var total = 0.0
            for (b in 0 until BANDS) {
                var e = 0.0
                for (k in edges[b] until edges[b + 1]) e += re[k] * re[k] + im[k] * im[k]
                cur[b] = e; total += e
            }
            // Bands 40 dB under the frame count as quiet (their noise would flip bits at random); differences that small
            // are no change (a steady tone gives steady bits)
            val floor = total * 1e-4 + 1e-12
            for (b in 0 until BANDS) cur[b] = ln(cur[b] + floor)
            var code = NONE
            if (hasPrev) {
                code = 0
                for (b in 0 until BANDS - 1) if ((cur[b] - cur[b + 1]) - (prev[b] - prev[b + 1]) > 1e-3) code = code or (1 shl b)
            }
            System.arraycopy(cur, 0, prev, 0, BANDS); hasPrev = true
            return code
        }
    }

    /** In-place radix-2 FFT (size a power of two). */
    fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val t = re[i]; re[i] = re[j]; re[j] = t; val u = im[i]; im[i] = im[j]; im[j] = u }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k; val b = a + len / 2
                    val tr = re[b] * cr - im[b] * ci
                    val ti = re[b] * ci + im[b] * cr
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun same(x: Int, y: Int) = x >= 0 && y >= 0 && Integer.bitCount(x xor y) <= MAX_BITS

    /** Enough different sound in [codes] (a steady tone or a hum is no theme). */
    fun varied(codes: IntArray): Boolean {
        val present = codes.filter { it >= 0 }
        return present.size >= MIN_RUN / 2 && present.toSet().size * 2 >= present.size
    }

    /**
     * The longest stretch of the same sound in two episodes' fingerprints [a] and [b] (index = 100 ms from the start):
     * the intro. Returns its frames in a and in b, or null (nothing shared for 15 s to 3 min).
     */
    fun common(a: IntArray, b: IntArray): Pair<IntRange, IntRange>? {
        if (a.isEmpty() || b.isEmpty()) return null
        // 1. The shift between the two (b index - a index) where most frames are the same sound
        var bestD = 0; var bestVotes = 0
        for (d in -(a.size - 1) until b.size) {
            val i0 = maxOf(0, -d); val i1 = minOf(a.size, b.size - d)
            if (i1 - i0 < MIN_RUN) continue
            var votes = 0
            var i = i0
            while (i < i1) { if (same(a[i], b[i + d])) votes++; i += 2 }
            if (votes > bestVotes) { bestVotes = votes; bestD = d }
        }
        if (bestVotes < MIN_RUN / 4) return null
        // 2. Along that shift, the longest run where most frames of every 2 s agree
        val i0 = maxOf(0, -bestD); val i1 = minOf(a.size, b.size - bestD)
        val n = i1 - i0
        val ok = BooleanArray(n) { same(a[i0 + it], b[i0 + it + bestD]) }
        val half = 10
        val good = BooleanArray(n)
        var cnt = 0
        for (k in 0 until minOf(half, n)) if (ok[k]) cnt++
        for (k in 0 until n) {
            if (k + half < n && ok[k + half]) cnt++
            if (k - half - 1 >= 0 && ok[k - half - 1]) cnt--
            val span = minOf(n - 1, k + half) - maxOf(0, k - half) + 1
            good[k] = cnt * 2 >= span
        }
        var bestS = -1; var bestLen = 0; var s = -1
        for (k in 0..n) {
            if (k < n && good[k]) { if (s < 0) s = k }
            else if (s >= 0) { if (k - s > bestLen) { bestLen = k - s; bestS = s }; s = -1 }
        }
        if (bestS < 0) return null
        // Trim to the first and last frame that really agree
        var st = bestS; var en = bestS + bestLen - 1
        while (st < en && !ok[st]) st++
        while (en > st && !ok[en]) en--
        val len = en - st + 1
        if (len < MIN_RUN || len > MAX_RUN) return null
        val ra = (i0 + st)..(i0 + en)
        if (!varied(a.copyOfRange(ra.first, ra.last + 1))) return null
        return ra to (ra.first + bestD)..(ra.last + bestD)
    }

    /**
     * Where the last few seconds heard ([recent], 100 ms frames) sit in the intro [template]: the template index that
     * lines up with recent[0] (negative when recent began before the intro), or null when the intro is not playing.
     */
    fun locate(template: IntArray, recent: IntArray): Int? {
        val heard = recent.count { it >= 0 }
        if (heard < recent.size / 2 || template.isEmpty()) return null
        var best: Int? = null; var bestN = 0
        for (j in -(recent.size / 2)..(template.size - recent.size / 2)) {
            var n = 0
            for (k in recent.indices) { val t = j + k; if (t in template.indices && same(template[t], recent[k])) n++ }
            if (n > bestN) { bestN = n; best = j }
        }
        return if (bestN * 2 >= heard) best else null
    }
}

/**
 * Listens to the decoded sound of the episode playing ([IntroPrintLogic.Extractor]) during its first minutes and keeps
 * the fingerprint by position (100 ms per entry). The player tells it now and then where it is ([anchor]); after a seek
 * the place is taken anew. Only fingerprints are kept in memory; the sound itself is never stored or sent.
 */
object IntroTap : TeeAudioProcessor.AudioBufferSink {
    private var key: String? = null
    private var print = IntArray(0)
    private var maxIdx = -1
    private var known: Pair<Long, Long>? = null
    private var ext: IntroPrintLogic.Extractor? = null
    private var channels = 1; private var encoding = 0; private var decim = 1
    private var acc = 0f; private var accN = 0
    private var frames = 0L
    private var anchorMs = Long.MIN_VALUE
    private val pending = IntArray(64); private var pendingN = 0
    private var rateEff = 0

    /** Starts listening for one episode ([key] = show and season), [frames] entries long. */
    @Synchronized fun start(key: String, frames: Int) {
        this.key = key; print = IntArray(frames.coerceIn(0, 12_000)) { IntroPrintLogic.NONE }; maxIdx = -1; known = null
        anchorMs = Long.MIN_VALUE; pendingN = 0; this.frames = 0
        if (rateEff > 0) ext = IntroPrintLogic.Extractor(rateEff)
    }

    /** Intro times known for this episode (database or heard): kept with its fingerprint. */
    @Synchronized fun known(startMs: Long, endMs: Long) { if (key != null) known = startMs to endMs }

    /** Stops; returns (key, fingerprint, known intro) of the episode heard. */
    @Synchronized fun finish(): Triple<String, IntArray, Pair<Long, Long>?>? {
        val k = key ?: return null
        key = null
        return Triple(k, print.copyOf(), known)
    }

    /** Where playback is now (player thread → main thread, while playing): places the frames heard since the last seek. */
    @Synchronized fun anchor(posMs: Long) {
        if (key == null || anchorMs != Long.MIN_VALUE) return
        anchorMs = posMs - frames * IntroPrintLogic.HOP_MS
        for (f in 0 until pendingN) store(f.toLong(), pending[f])
        pendingN = 0
    }

    /** The last [n] entries heard, with the index of the first; null before enough was heard. */
    @Synchronized fun recent(n: Int): Pair<Int, IntArray>? {
        if (key == null || maxIdx < n - 1) return null
        val from = maxIdx - n + 1
        return from to print.copyOfRange(from, maxIdx + 1)
    }

    private fun store(f: Long, code: Int) {
        if (anchorMs == Long.MIN_VALUE) { if (pendingN < pending.size) pending[pendingN++] = code; return }
        val idx = ((anchorMs + f * IntroPrintLogic.HOP_MS) / IntroPrintLogic.HOP_MS).toInt()
        if (idx in print.indices) { print[idx] = code; if (idx > maxIdx) maxIdx = idx }
    }

    @Synchronized override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        channels = channelCount.coerceAtLeast(1); this.encoding = encoding
        decim = (sampleRateHz / 8000).coerceAtLeast(1)
        rateEff = sampleRateHz / decim
        ext = if (key != null) IntroPrintLogic.Extractor(rateEff) else null
        acc = 0f; accN = 0; frames = 0; anchorMs = Long.MIN_VALUE; pendingN = 0
    }

    @Synchronized override fun handleBuffer(buffer: ByteBuffer) {
        if (key == null) return
        val e = ext ?: IntroPrintLogic.Extractor(rateEff.coerceAtLeast(8000)).also { ext = it }
        val pcm16 = encoding == C.ENCODING_PCM_16BIT
        if (!pcm16 && encoding != C.ENCODING_PCM_FLOAT) return
        val b = buffer.duplicate().order(ByteOrder.nativeOrder())
        val frameBytes = channels * (if (pcm16) 2 else 4)
        while (b.remaining() >= frameBytes) {
            var v = 0f
            for (ch in 0 until channels) v += if (pcm16) b.short / 32768f else b.float
            acc += v / channels; accN++
            if (accN >= decim) {
                val code = e.push(acc / accN)
                acc = 0f; accN = 0
                if (code != IntroPrintLogic.PENDING) store(frames++, code)
            }
        }
    }
}

/** Both listeners on the decoded sound (subtitle sync and intro fingerprint) through the player's one tee. */
object AudioTaps : TeeAudioProcessor.AudioBufferSink {
    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        SpeechTap.flush(sampleRateHz, channelCount, encoding)
        IntroTap.flush(sampleRateHz, channelCount, encoding)
    }
    override fun handleBuffer(buffer: ByteBuffer) {
        SpeechTap.handleBuffer(buffer)
        IntroTap.handleBuffer(buffer)
    }
}

/**
 * Fingerprints and intro templates on the TV (files/intro_prints, per show and season; at most 120 files, oldest
 * dropped). Not user data: nothing is synced or backed up; losing them only means learning again.
 */
internal object IntroPrints {
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val MAX_FILES = 120

    private fun dir(c: Context) = File(c.filesDir, "intro_prints").apply { mkdirs() }
    private fun file(c: Context, kind: String, key: String) = File(dir(c), kind + "_" + key.hashCode().toUInt().toString(16) + ".bin")

    private fun write(f: File, codes: IntArray) = runCatching {
        DataOutputStream(f.outputStream().buffered()).use { o -> o.writeInt(codes.size); codes.forEach { o.writeInt(it) } }
    }

    private fun read(f: File): IntArray? {
        if (!f.exists()) return null
        return runCatching {
            DataInputStream(f.inputStream().buffered()).use { i -> val n = i.readInt(); if (n !in 0..20_000) null else IntArray(n) { i.readInt() } }
        }.getOrNull()
    }

    /** The intro template for this season, else the show's latest. */
    fun template(c: Context, seasonKey: String, showId: String): IntArray? =
        read(file(c, "tpl", seasonKey)) ?: read(file(c, "tpl", showId))

    private fun log(s: String) { if (BuildConfig.VERSION_NAME.endsWith("-test")) android.util.Log.i("FofPlay", s) }

    /**
     * An episode was heard: with database (or heard) intro times its intro becomes the template; else it is compared with
     * the previous episode of the season and the shared stretch becomes the template. The show's intro length follows.
     */
    fun finished(c: Context, seasonKey: String, showId: String, print: IntArray, known: Pair<Long, Long>?) {
        val app = c.applicationContext
        io.launch {
            val heard = print.count { it >= 0 }
            log("intro print heard " + heard / 10 + "s")
            if (heard < 300) return@launch
            var tpl: IntArray? = null
            if (known != null) {
                val a = (known.first / IntroPrintLogic.HOP_MS).toInt().coerceAtLeast(0)
                val b = (known.second / IntroPrintLogic.HOP_MS).toInt().coerceAtMost(print.size - 1)
                if (b - a >= IntroPrintLogic.MIN_RUN) print.copyOfRange(a, b + 1).takeIf { t -> t.count { it >= 0 } * 10 >= t.size * 8 }?.let { tpl = it }
            }
            if (tpl == null) read(file(app, "print", seasonKey))?.let { prev ->
                IntroPrintLogic.common(prev, print)?.let { (_, rb) -> tpl = print.copyOfRange(rb.first, rb.last + 1); log("intro shared " + rb.first / 10 + "s-" + (rb.last + 1) / 10 + "s") }
            }
            write(file(app, "print", seasonKey), print)
            val t = tpl
            if (t != null && IntroPrintLogic.varied(t)) {
                write(file(app, "tpl", seasonKey), t)
                write(file(app, "tpl", showId), t)
                log("intro learned " + t.size / 10 + "s")
                withContext(Dispatchers.Main) { IntroLearn.save(app, showId, t.size.toLong() * IntroPrintLogic.HOP_MS) }
            }
            // Keep the folder small
            runCatching {
                val fs = dir(app).listFiles()?.sortedBy { it.lastModified() } ?: return@runCatching
                if (fs.size > MAX_FILES) fs.take(fs.size - MAX_FILES).forEach { it.delete() }
            }
        }
    }
}
