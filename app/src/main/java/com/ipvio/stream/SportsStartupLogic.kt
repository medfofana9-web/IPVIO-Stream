package com.ipvio.stream

/**
 * Bound initial feed selection time without shortening network recovery for a feed
 * that has already played successfully. A sports failover is meaningful only if
 * another channel can be attempted.
 */
internal object SportsStartupLogic {
    fun nextFeedDelayMs(verified: Boolean, candidates: Int): Long? =
        if (candidates <= 1) null else if (verified) 7_500L else 4_500L

    /** A feed that has sent no data at all by now is dead or hanging: the next feed is tried without waiting longer. */
    const val NO_DATA_MS = 2_500L

    /**
     * Less than this since the feed was opened counts as no data: response headers, a playlist or a placeholder, not
     * video. Counting a single byte as "data" let a dead feed that sent a few bytes keep its full 4.5-7.5 s (#62: 6.9 s).
     * A working live feed sends far more in 2.5 s (1 Mbit/s is about 300 KiB).
     */
    const val NO_DATA_BYTES = 64L * 1024

    fun noData(bytesAtOpen: Long, bytesNow: Long): Boolean = bytesNow - bytesAtOpen < NO_DATA_BYTES

    fun shouldTryNext(readyOnce: Boolean, isLiveGame: Boolean, candidates: Int): Boolean =
        !readyOnce && isLiveGame && candidates > 1
}

/**
 * Live channel that sends nothing (dead or blocked at the provider, Med 27 Nov 2026): it used to spin for 15 s and then
 * reconnect to the same dead link for ever. Now another quality of the same channel is tried after [ALT_MS] without data,
 * and after [DEAD_MS] a clear message says the channel sends no picture (Up/Down still change channels). "No data" is
 * [SportsStartupLogic.noData] (under 64 KiB since opening). Tested in SportsStartupLogicTest.
 */
internal object LiveDeadLogic {
    const val ALT_MS = 3_000L
    const val DEAD_MS = 8_000L

    fun tryOtherFeed(noData: Boolean, hasOther: Boolean, online: Boolean, sports: Boolean): Boolean = noData && hasOther && online && !sports
    fun dead(noData: Boolean, online: Boolean, sports: Boolean): Boolean = noData && online && !sports
}
