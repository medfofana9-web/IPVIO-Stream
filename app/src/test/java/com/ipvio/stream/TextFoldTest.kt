package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TextFoldTest {
    @Test fun foldsLettersNfdKeeps() {
        assertEquals("bodo/glimt", TextFold.fold("Bodø/Glimt"))
        assertEquals("aesir", TextFold.fold("Æsir"))
        assertEquals("strasse", TextFold.fold("Straße"))
    }

    @Test fun scoreboardAndPlaylistSpellingsMatch() {
        assertEquals("bodo glimt", SportData.norm("Bodø/Glimt"))
        assertEquals(SportData.norm("Bodo/Glimt"), SportData.norm("Bodø/Glimt"))
        assertEquals("bodo glimt", SportNet.norm("FK Bodø/Glimt").removePrefix("fk "))
    }

    @Test fun legacyKeyDiffersOnlyForFoldedLetters() {
        assertEquals(SportData.norm("Aston Villa"), SportData.normLegacy("Aston Villa"))
        assertFalse(SportData.norm("Bodø/Glimt") == SportData.normLegacy("Bodø/Glimt"))
    }
}
