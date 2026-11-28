package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class HomeRowLogicTest {
    private fun e(id: String, ts: Long, pos: Long = 60_000, s: Int = 0, ep: Int = 0, next: Boolean = false, title: String = "T") =
        Entry(id, title, "", pos, 3_000_000, ts, s, ep, next)

    @Test fun picksResumableTitlesNewestFirst() {
        val l = listOf(e("a", 1), e("b", 3), e("c", 2, pos = 0), e("d", 4, pos = 0, s = 1, ep = 2, next = true))
        assertEquals(listOf("d", "b", "a"), HomeRowLogic.pick(l).map { it.itemId })
        assertEquals(HomeRowLogic.MAX, HomeRowLogic.pick((1..15).map { e("x$it", it.toLong()) }).size)
    }

    @Test fun newEpisodeIsANewEntry() {
        assertNotEquals(HomeRowLogic.key(e("s", 1, s = 1, ep = 2)), HomeRowLogic.key(e("s", 1, s = 1, ep = 3)))
        assertEquals(HomeRowLogic.key(e("m", 1)), HomeRowLogic.key(e("m", 2, pos = 90_000)))
    }

    @Test fun showNameWithoutTheEpisodeTag() {
        assertEquals("Reasonable Doubt", HomeRowLogic.title(e("s", 1, s = 4, ep = 10, title = "Reasonable Doubt S4E10")))
        assertEquals("Movie", HomeRowLogic.title(e("m", 1, title = "Movie")))
    }
}
