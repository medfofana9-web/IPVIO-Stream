package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SurpriseLogicTest {
    private fun c(id: String, g: List<String>, rt: Int? = 95, seen: Boolean = false, r: Double? = 7.0) =
        SurpriseLogic.Cand(id, id, g, rt, r, seen, "top rated")

    @Test
    fun filtersApply() {
        val all = listOf(c("a", listOf("Drama")), c("b", listOf("Comedy"), rt = 130), c("c", listOf("Drama"), seen = true), c("a", listOf("Drama")))
        assertEquals(listOf("a"), SurpriseLogic.filter(all, "drama", 1, 1, emptySet(), emptySet()).map { it.id })
        assertEquals(listOf("c"), SurpriseLogic.filter(all, null, 0, 2, emptySet(), emptySet()).map { it.id })
        assertEquals(listOf("b", "c"), SurpriseLogic.filter(all, null, 0, 0, setOf("x"), setOf("a")).map { it.id })
    }

    @Test
    fun pickAndWhy() {
        assertNull(SurpriseLogic.pick(emptyList(), emptyList(), kotlin.random.Random(1)))
        val x = c("a", listOf("Drama"), r = 8.2)
        assertEquals("a", SurpriseLogic.pick(listOf(x), listOf("Drama"), kotlin.random.Random(1))?.id)
        val w = SurpriseLogic.why(x, listOf("Drama"))
        assertTrue(w, w.startsWith("You like Drama") && "rated 8.2" in w && "95 min" in w)
    }
}
