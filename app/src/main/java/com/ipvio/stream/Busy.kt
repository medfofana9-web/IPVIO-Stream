package com.ipvio.stream

/**
 * The remote is being used: heavy background work (channel matching, score updates that redraw the page, addon
 * warm-ups) waits until the user pauses, so scrolling gets the whole processor on budget boxes.
 */
object Busy {
    @Volatile var last = 0L

    /** True while a key was pressed in the last [ms] milliseconds. */
    fun active(ms: Long = 1_200): Boolean = System.currentTimeMillis() - last < ms

    /** Waits (at most [maxMs]) until no key was pressed for a moment. */
    suspend fun quiet(maxMs: Long = 8_000) {
        val until = System.currentTimeMillis() + maxMs
        while (active() && System.currentTimeMillis() < until) kotlinx.coroutines.delay(250)
    }
}
