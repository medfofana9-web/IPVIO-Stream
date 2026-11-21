package com.ipvio.stream

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SportAddonCacheTest {
    @Test fun cachedFeedIsOnlyReusableForTwoMinutes() {
        val now = 1_000_000L
        assertTrue(SportAddons.cacheFresh(now, now - 119_999))
        assertFalse(SportAddons.cacheFresh(now, now - 120_000))
        assertFalse(SportAddons.cacheFresh(now, now - 500_000))
    }

    @Test fun futureDatedCacheNeverCountsAsFresh() {
        assertFalse(SportAddons.cacheFresh(1_000_000, 1_001_000))
    }
}
