package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameStatsTest {
    @Test
    fun summaryLine() {
        val l = FrameStats.line("tab0", 200, 30, 1, 812, longArrayOf(10, 20, 30, 40, 0), intArrayOf(150, 20, 15, 10, 4, 1))
        assertEquals("tab0 200f slow 15% frozen 1 worst 812ms h=150/20/15/10/4/1 | in 10 an 20 lay 30 dr 40 gpu 0", l)
    }
}
