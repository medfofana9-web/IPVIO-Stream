package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StremioAlternateImdbTest {
    private val candidates = listOf(
        StremioLogic.ImdbHit("tt1000001", "Survivor", 2000, "series"),
        StremioLogic.ImdbHit("tt1000002", "Survivor", 2023, "series"),
        StremioLogic.ImdbHit("tt1000003", "Survivor", 2000, "movie"),
        StremioLogic.ImdbHit("tt1000004", "Survivor: Australia", 2000, "series")
    )
    @Test fun retriesWithAnotherExactEditionOnly() {
        assertEquals("tt1000001", StremioLogic.chooseAlternateImdb("Survivor", 2000, true, "tt9999999", candidates))
    }
    @Test fun doesNotSwitchToOtherYearOrSpinoff() {
        assertNull(StremioLogic.chooseAlternateImdb("Survivor", 2000, true, "tt1000001", candidates))
    }
    @Test fun missingYearNeverGuesses() {
        assertNull(StremioLogic.chooseAlternateImdb("Survivor", null, true, "tt9999999", candidates))
    }
    @Test fun rejectsMalformedPreviousIdentifier() {
        assertNull(StremioLogic.chooseAlternateImdb("Survivor", 2000, true, "bad", candidates))
    }
}
