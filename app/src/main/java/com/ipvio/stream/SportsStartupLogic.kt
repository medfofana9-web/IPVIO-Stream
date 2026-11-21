package com.ipvio.stream

/**
 * Bound initial feed selection time without shortening network recovery for a feed
 * that has already played successfully. A sports failover is meaningful only if
 * another channel can be attempted.
 */
internal object SportsStartupLogic {
    fun nextFeedDelayMs(verified: Boolean, candidates: Int): Long? =
        if (candidates <= 1) null else if (verified) 7_500L else 4_500L

    fun shouldTryNext(readyOnce: Boolean, isLiveGame: Boolean, candidates: Int): Boolean =
        !readyOnce && isLiveGame && candidates > 1
}
