package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceCheckLogicTest {
    private fun p(code: Int, bytes: Int = 1000) = SourceCheckLogic.Probe(code, bytes, 300, if (code == 0) "SocketTimeoutException" else null)

    @Test fun probeStates() {
        assertEquals(SourceHealthLogic.OK, SourceCheckLogic.state(p(206)))
        assertEquals(SourceHealthLogic.NO_SEEK, SourceCheckLogic.state(p(200)))
        assertEquals(SourceHealthLogic.FAILED, SourceCheckLogic.state(p(403)))
        assertEquals(SourceHealthLogic.FAILED, SourceCheckLogic.state(p(0, 0)))
        // Headers but no data (a hanging server)
        assertEquals(SourceHealthLogic.FAILED, SourceCheckLogic.state(p(206, 0)))
    }

    @Test fun addonVerdicts() {
        assertEquals("finds no streams for well-known movies", SourceCheckLogic.addonVerdict(0, emptyList()))
        assertNull(SourceCheckLogic.addonVerdict(5, emptyList()))
        assertTrue(SourceCheckLogic.addonVerdict(5, listOf(p(403), p(0, 0)))!!.startsWith("links do not open"))
        assertTrue(SourceCheckLogic.addonVerdict(5, listOf(p(200), p(200)))!!.startsWith("cannot jump"))
        assertNull(SourceCheckLogic.addonVerdict(5, listOf(p(403), p(206))))
    }
}
