package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtColorTest {
    @Test
    fun greyGivesNoMood() {
        assertEquals(0, ArtColor.mood(IntArray(50) { 0xFF808080.toInt() }))
    }

    @Test
    fun colorfulPixelsWin() {
        // Mostly grey, some strong red: the mood is a deep red (red channel dominant, brightness ~0.34)
        val px = IntArray(100) { if (it < 20) 0xFFE02020.toInt() else 0xFF7A7A7A.toInt() }
        val m = ArtColor.mood(px)
        val r = (m shr 16) and 0xFF; val g = (m shr 8) and 0xFF; val b = m and 0xFF
        assertTrue("r=$r g=$g b=$b", r > g && r > b && r in 70..95)
    }

    @Test
    fun teamColors() {
        assertEquals(0xFF1D428A.toInt(), teamArgb("1d428a"))
        assertEquals(0xFF1D428A.toInt(), teamArgb("#1D428A"))
        assertNull(teamArgb("xyz"))
        assertNull(teamArgb(null))
    }
}
