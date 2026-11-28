package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Buffer byte limit grows with the heap, retry rules of the player. */
class PlayerBufferTest {
    private val mb = 1024 * 1024

    @Test fun byteLimit_waechstMitHeap() {
        // 256 MB heap (1 GB box): 12 % = 30 MB instead of 14 MB
        val a = BufferLogic.byteLimit(14 * mb, 256L * mb)
        assertTrue(a > 14 * mb && a < 32 * mb)
        // big heap: capped at 64 MB
        assertEquals(64 * mb, BufferLogic.byteLimit(28 * mb, 2048L * mb))
        // never below the planned value
        assertEquals(28 * mb, BufferLogic.byteLimit(28 * mb, 64L * mb))
        // no limit of our own stays no limit
        assertEquals(-1, BufferLogic.byteLimit(-1, 512L * mb))
    }

    /** #68: on a 2-3 GB TV with the large heap (512 MB) the buffer stops at 40 MB, still above the planned 28 MB. */
    @Test fun byteLimitCappedOnSmallTvs() {
        assertEquals(40 * mb, BufferLogic.byteLimit(28 * mb, 512L * mb, 40L * mb))
        assertEquals(28 * mb, BufferLogic.byteLimit(28 * mb, 128L * mb, 40L * mb))
        assertEquals(24 * 1024 * 1024, Mem.imageCacheBytes(0))
        assertEquals(40 * 1024 * 1024, Mem.imageCacheBytes(1))
        assertEquals(null, Mem.imageCacheBytes(2))
    }

    @Test fun retry_nurWoSinnvoll() {
        assertTrue(IptvLoadErrorPolicy.isFinalStatus(403))
        assertTrue(IptvLoadErrorPolicy.isFinalStatus(404))
        assertFalse(IptvLoadErrorPolicy.isFinalStatus(408))
        assertFalse(IptvLoadErrorPolicy.isFinalStatus(429))
        assertFalse(IptvLoadErrorPolicy.isFinalStatus(500))
        assertFalse(IptvLoadErrorPolicy.isFinalStatus(512))
    }

    /** #55/#68: on 2–3 GB TVs the limits come from 192 MB (128 MB at 1.5 GB), not from the large heap. */
    @Test fun planHeapOnSmallTvs() {
        assertEquals(192L * mb, Mem.planHeapFor(512L * mb, 1))
        assertEquals(128L * mb, Mem.planHeapFor(512L * mb, 0))
        assertEquals(96L * mb, Mem.planHeapFor(96L * mb, 1))
        assertEquals(512L * mb, Mem.planHeapFor(512L * mb, 2))
        assertEquals(512L * mb, Mem.planHeapFor(512L * mb, -1))
        // Playlist budget on a 2 GB TV: 192 MB × 40 % / 0.65 ≈ 118 MB of list
        assertTrue(LoadLogic.budget(Mem.planHeapFor(512L * mb, 1)) in (110L * mb)..(125L * mb))
    }
}
