package com.ipvio.stream

/**
 * Loads the backup fonts in the background at start. The first time a screen draws a letter the main font lacks
 * (Turkish, Greek, Cyrillic or Arabic team names, flag emoji in provider folder names), Android reads a backup font
 * file on the main thread; on an onn 4K that froze Sports for over 2 s inside text drawing (freeze report:
 * nDrawText < BoringLayout.draw). Measuring the same characters here first puts those fonts in the shared font cache.
 */
object FontWarmUp {
    private const val SAMPLE = "Ağrı Beşiktaş Fenerbahçe Galatasaray Göztepe Şakhtar Ñ Ü ß Ø Å Ł Ž č ğ ı ş " +
        "Ολυμπιακός Παναθηναϊκός Спартак Зенит الهلال النصر הפועל 東京 서울 ⚽ 🏀 🏈 \uD83C\uDDE9\uD83C\uDDEA \uD83C\uDDFA\uD83C\uDDF8"

    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        Thread({
            runCatching {
                val p = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                for (bold in listOf(false, true)) {
                    p.typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
                    p.textSize = 32f
                    p.measureText(SAMPLE)
                    // A layout pass as well: the shaping path text drawing takes
                    android.text.StaticLayout.Builder.obtain(SAMPLE, 0, SAMPLE.length, p, 2000).build()
                }
            }
        }, "fof-font-warmup").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }
}
