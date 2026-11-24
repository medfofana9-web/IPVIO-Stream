package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Test

class SourcePriorityTest {
    @Test fun dragonStreamsThenIptvThenOthers() {
        assertEquals(0, StremioLogic.sourcePriority("DragonStreams"))
        assertEquals(0, StremioLogic.sourcePriority("dragonstreams.example"))
        assertEquals(1, StremioLogic.sourcePriority("Your IPTV"))
        assertEquals(2, StremioLogic.sourcePriority("Pengu"))
    }
}
