package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SportNetTest {
    @Test fun network_keys() {
        assertEquals("espn", SportNet.key("ESPN"))
        assertEquals("espn", SportNet.key(SportNet.stripPrefix("US| ESPN HD")))
        assertEquals("espn2", SportNet.key(SportNet.stripPrefix("USA: ESPN 2 FHD")))
        assertEquals("fanduelsportsindiana", SportNet.key("FanDuel SN IN"))
        assertEquals("fanduelsportsindiana", SportNet.key("Bally Sports Indiana"))
        assertEquals("nbatv", SportNet.key("NBA TV"))
    }

    @Test fun channel_is_network() {
        assertTrue(SportNet.channelIs("US: ESPN", SportNet.key("ESPN")))
        assertFalse(SportNet.channelIs("US: ESPN 2", SportNet.key("ESPN")))
        assertFalse(SportNet.channelIs("ESPNews", SportNet.key("ESPN")))
        assertTrue(SportNet.channelIs("[US] FanDuel Sports Indiana HD", SportNet.key("FanDuel SN IN")))
    }

    @Test fun team_channels() {
        val home = listOf("pacers", "indiana pacers", "indiana")
        val away = listOf("celtics", "boston celtics", "boston")
        assertEquals(1, SportNet.teamOf("NBA: Indiana Pacers", home, away))
        assertEquals(2, SportNet.teamOf("USA | Boston Celtics HD", home, away))
        assertEquals(0, SportNet.teamOf("NBA 01: Pacers vs Celtics", home, away))
        assertTrue(SportNet.hasTag("USA | NBA TEAMS", SportNet.leagueTags("NBA")))
        assertFalse(SportNet.hasTag("USA | WNBA", SportNet.leagueTags("NBA")))
    }
}
