package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderVodLogicTest {
    @Test fun providerNamesMatchAddonTitles() {
        val want = ProviderVodLogic.key("Reasonable Doubt", true)
        assertEquals(want, ProviderVodLogic.key("EN - Reasonable Doubt (2022)", true))
        assertEquals(want, ProviderVodLogic.key("|US| Reasonable Doubt", true))
        assertEquals(ProviderVodLogic.key("Dune: Part Two", false), ProviderVodLogic.key("4K - Dune Part Two (2024) HEVC", false))
    }

    @Test fun differentTitlesAndKindsStayApart() {
        assertNotEquals(ProviderVodLogic.key("Dune", false), ProviderVodLogic.key("Dune: Part Two", false))
        assertNotEquals(ProviderVodLogic.key("Fargo", false), ProviderVodLogic.key("Fargo", true))
    }

    @Test fun yearAndLanguageDecide() {
        val c = listOf(ProviderVodLogic.Cand("FR - Dune (2021)", 2021), ProviderVodLogic.Cand("EN - Dune (2021)", 2021), ProviderVodLogic.Cand("Dune (1984)", 1984))
        assertEquals(1, ProviderVodLogic.pick(c, 2021))
        assertEquals(2, ProviderVodLogic.pick(c, 1984))
        assertNull(ProviderVodLogic.pick(listOf(ProviderVodLogic.Cand("Dune (1984)", 1984)), 2021))
        assertEquals(2024, ProviderVodLogic.year("Show (2024)", null))
        assertEquals(2019, ProviderVodLogic.year("Show", "2019-05-01"))
    }

    @Test fun dragonMp4IsLastResortOnly() {
        // MKV present: MP4 links stay out
        val l = listOf("x" to "https://a/b.mkv", "y" to "https://a/c.mp4")
        assertEquals(listOf(0), StremioLogic.dragonFilter(l))
        assertTrue(StremioLogic.LAST_RESORT.startsWith("MP4"))
    }
}
