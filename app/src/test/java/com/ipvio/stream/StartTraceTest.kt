package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartTraceTest {
    @Test fun stepsAddUpToTheTotal() {
        val l = StartTraceLogic.line("sports", 1_000, 1_200, 1_250, 1_300, 5_300, 3, 1, lastOpen = 3_800)!!
        assertEquals("sports 4.3s · pick 0.2 · guard 0.1 · ui 0.1 · failed feeds 2.5 · net+decode 1.5 · feeds 3 · failover 1", l.text)
        assertTrue(l.slow)
    }

    @Test fun fastStartIsNotReported() {
        val l = StartTraceLogic.line("live", 1_000, 0, 0, 1_100, 2_400, 0, 0)!!
        assertFalse(l.slow)
        assertEquals("live 1.4s · pick 0.0 · guard 0.0 · ui 0.1 · net+decode 1.3", l.text)
    }

    @Test fun noOkNoLine() {
        assertNull(StartTraceLogic.line("x", 0, 0, 0, 0, 10, 0, 0))
    }
}
