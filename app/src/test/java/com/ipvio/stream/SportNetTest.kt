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
        // Streaming services keep their "+": a WSL game on Paramount+ must not land on "US: Paramount Network"
        assertEquals("paramountplus", SportNet.key("Paramount+"))
        assertFalse(SportNet.channelIs("US: Paramount Network", SportNet.key("Paramount+")))
        assertFalse(SportNet.channelIs("US: ESPN", SportNet.key("ESPN+")))
        assertTrue(SportNet.looseKey("Paramount Network") != SportNet.looseKey("Paramount+"))
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

    @Test fun provider_names() {
        assertEquals(SportNet.key("FS1"), SportNet.key("FOX Sports 1 FHD"))
        assertEquals(SportNet.key("ESPNU"), SportNet.key("ESPN U"))
        assertEquals(SportNet.key("YES"), SportNet.key("YES Network HD"))
        assertEquals(SportNet.looseKey("Altitude"), SportNet.looseKey("Altitude Sports"))
        assertEquals(1, SportNet.teamOf("NBA INDIANA PACERS FHD", listOf("pacers", "indiana pacers", "indiana"), listOf("wizards", "washington wizards")))
        assertTrue(SportNet.hasTag("USA | NBA Teams", SportNet.leagueTags("NBA")))
        assertTrue(SportNet.hasTag("USA | NCAAF", SportNet.leagueTags("College Football")))
    }

    @Test fun gameWithoutTvDataUsesUsualNetworks() {
        fun t(n: String, a: String) = SportData.Team(n, n, a, null, null, false)
        val g = SportData.Game("1", "Concacaf Nations League", "Fußball", 0L, 1, "", t("United States", "USA"), t("Haiti", "HAI"))
        val nets = SportRights.usual(g)
        assertTrue("TBS" in nets && "truTV" in nets)
        // The provider's channel name finds the network
        assertEquals(SportNet.key("TBS"), SportNet.key(SportNet.stripPrefix("US| TBS HD")))
        // South Alabama ("USA") in college basketball is not the national team
        val c = SportData.Game("2", "College Basketball", "Basketball", 0L, 1, "", t("South Alabama", "USA"), t("Troy", "TROY"))
        assertTrue(SportRights.usual(c).isEmpty())
    }
}
