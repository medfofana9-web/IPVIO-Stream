package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryLogicTest {
    @Test fun balanceTakesFromEveryList() {
        val l = listOf(listOf("a1", "a2", "a3"), listOf("b1", "b2"), listOf("c1"))
        assertEquals(listOf("a1", "b1", "c1", "a2", "b2"), DiscoveryLogic.balance(l, 5) { it })
        assertEquals(listOf("a1", "b1"), DiscoveryLogic.balance(listOf(listOf("a1", "x"), listOf("x", "b1")), 2) { it })
    }

    @Test fun ratingsRankGenres() {
        val g = mapOf("m1" to setOf("Crime", "Drama"), "m2" to setOf("Crime"), "m3" to setOf("Comedy"))
        val r = mapOf("m1" to Discovery.LOVED, "m2" to Discovery.LIKED, "m3" to Discovery.NOPE)
        val s = DiscoveryLogic.genreScores(r, g)
        assertEquals("Crime" to 3, s.first())
        assertTrue(s.any { it == "Comedy" to -1 })
    }

    @Test fun adaptLeansToFavoritesButKeepsExploring() {
        val byGenre = mapOf("Crime" to (1..10).map { "c$it" }, "Drama" to (1..10).map { "d$it" }, "Comedy" to (1..10).map { "x$it" })
        val more = DiscoveryLogic.adapt(listOf("Crime" to 4, "Drama" to 2), byGenre, setOf("c1"), { it }, 9)
        assertTrue(more.none { it == "c1" })                       // never repeats a shown title
        assertTrue(more.count { it.startsWith("c") || it.startsWith("d") } >= 6)   // mostly the favorites
        assertTrue(more.any { it.startsWith("x") })                // and something new
        assertTrue(DiscoveryLogic.adapt(emptyList(), byGenre, emptySet(), { it }).isEmpty())   // nothing known yet: no narrowing
    }

    @Test fun okCyclesTheRating() {
        assertEquals(Discovery.LOVED, DiscoveryLogic.next(Discovery.NONE))
        assertEquals(Discovery.LIKED, DiscoveryLogic.next(Discovery.LOVED))
        assertEquals(Discovery.NOPE, DiscoveryLogic.next(Discovery.LIKED))
        assertEquals(Discovery.NONE, DiscoveryLogic.next(Discovery.NOPE))
    }
}
