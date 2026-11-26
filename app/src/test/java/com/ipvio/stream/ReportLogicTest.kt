package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportLogicTest {
    @Test fun shortReportStaysOneRow() {
        val k = ReportLogic.join("freeze: tab0 · A.kt:1 f", "2.5 s")
        assertEquals(listOf("freeze: tab0 · A.kt:1 f ‖ 2.5 s"), ReportLogic.split(k, "ab"))
        assertEquals("freeze: tab0 · A.kt:1 f", ReportLogic.stable(k))
    }

    @Test fun longReportIsSplitIntoRowsOfAtMost400() {
        val details = "x".repeat(900)
        val rows = ReportLogic.split(ReportLogic.join("crash: Boom", details), "id1")
        assertEquals("crash: Boom ‖#id1", rows[0])
        assertTrue(rows.all { it.length <= ReportLogic.MAX })
        assertTrue(rows[1].startsWith("+#id1 1 "))
        assertEquals(details, rows.drop(1).joinToString("") { it.substringAfter(' ').substringAfter(' ') })
        assertEquals("crash: Boom", ReportLogic.stable(rows[0]))
    }

    @Test fun linksNeverLeave() {
        assertEquals("a <link> b", ReportLogic.clean("a  http://host/x?u=1&p=2\nb"))
    }

    @Test fun stepsShowAge() {
        assertEquals("tab0 -95s > ok sports -12s", ReportLogic.steps(listOf(5_000L to "tab0", 88_000L to "ok sports"), 100_000L))
    }

    @Test fun sourceKindFromAddressShape() {
        assertEquals("live xtream ts", ReportLogic.sourceKind("http://h:80/live/u/p/123.ts", true))
        assertEquals("live xtream ts", ReportLogic.sourceKind("http://h/u/p/123.ts", true))
        assertEquals("vod xtream mkv", ReportLogic.sourceKind("http://h/movie/u/p/9.mkv", false))
        assertEquals("live m3u hls", ReportLogic.sourceKind("https://cdn.example/a/b/c/index.m3u8?t=1", true))
        assertFalse(ReportLogic.sourceKind("http://h/u/p/123.ts", true).contains("h/"))
    }

    @Test fun hotAndPlaces() {
        assertEquals("2/3 A ; 1/3 B", ReportLogic.hot(listOf("A", "B", "A")))
        assertEquals("detail 75% main 25%", ReportLogic.places(mapOf("detail" to 3, "" to 1)))
    }
}
