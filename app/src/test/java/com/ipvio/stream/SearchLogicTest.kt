package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchLogicTest {
    private fun item(id: String, k: Kind, t: String) = Item(id, k, t, null, "u$id", null, "g")
    private val idx = SearchIndex(listOf(
        item("1", Kind.MOVIE, "Die fabelhafte Welt der Amélie"),
        item("2", Kind.SERIES, "Straße der Lieder"),
        item("3", Kind.LIVE, "ZDF HD"),
        item("4", Kind.MOVIE, "Amelie 2")
    ))

    @Test fun findetOhneAkzenteUndGrossKlein() {
        val (m, s, l) = idx.find("amelie")
        assertEquals(listOf("1", "4"), m.map { it.id })
        assertEquals(0, s.size + l.size)
    }

    @Test fun eszettUndArten() {
        assertEquals(listOf("2"), idx.find("strasse").second.map { it.id })
        assertEquals(listOf("3"), idx.find("zdf").third.map { it.id })
    }

    @Test fun leer() = assertEquals(0, idx.find("  ").first.size + idx.find("xyz").first.size)

    @Test fun wordStartsFirstAndChannelsOnlyAtAWordStart() {
        val x = SearchIndex(listOf(
            item("a", Kind.SERIES, "Outlaws"), item("b", Kind.SERIES, "Family Law"),
            item("c", Kind.LIVE, "NCAAW B 06: Merrimack vs Delaware"), item("d", Kind.LIVE, "Law & Crime"),
            item("e", Kind.MOVIE, "Lawless")
        ))
        val (m, s, l) = x.find("law")
        assertEquals(listOf("e"), m.map { it.id })
        assertEquals(listOf("b", "a"), s.map { it.id })
        assertEquals(listOf("d"), l.map { it.id })
    }
}
