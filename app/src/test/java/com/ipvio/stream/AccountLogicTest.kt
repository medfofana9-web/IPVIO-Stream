package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountLogicTest {
    @Test fun readsSessionFromSignIn() {
        val s = AccountLogic.sessionFrom("""{"access_token":"A","refresh_token":"R","expires_at":2000000000,"user":{"id":"U1","email":"med@example.com"}}""")!!
        assertEquals("A", s.access); assertEquals("R", s.refresh); assertEquals("U1", s.userId); assertEquals(2000000000L, s.expiresAt)
        val back = AccountLogic.readSession(AccountLogic.writeSession(s))!!
        assertEquals("med@example.com", back.email)
        // Sign-up without a session (email confirmation on): no session, no crash
        assertNull(AccountLogic.sessionFrom("""{"id":"U1","email":"x@y.z"}"""))
    }

    @Test fun readsMembersAndConfig() {
        val m = AccountLogic.memberFrom("""[{"household_id":"H","role":"owner","display_name":"Med","avatar":"a1"}]""")!!
        assertTrue(m.owner); assertEquals("a1", m.avatar)
        assertNull(AccountLogic.memberFrom("[]"))
        assertEquals(2, AccountLogic.membersFrom("""[{"role":"owner"},{"role":"member"}]""").size)
        val cfg = AccountLogic.configFrom("""[{"playlist_url":"http://p/get.php?username=a&password=b","addons":["https://x/manifest.json","bad"]}]""")!!
        assertEquals(listOf("https://x/manifest.json"), cfg.second)
    }

    @Test fun readableErrors() {
        assertEquals("Invalid login credentials", AccountLogic.message("""{"error":"invalid_grant","error_description":"invalid login credentials"}"""))
        assertEquals("Invite code not valid", AccountLogic.message("""{"code":"P0001","message":"invite code not valid"}"""))
    }

    @Test fun reportJsonIsUtcAndLimited() {
        val j = org.json.JSONObject(AccountLogic.reportJson("h1", "Med", "Freeze".repeat(100), "1.3.70", "Amazon AFTKA", 0L))
        assertEquals("1970-01-01T00:00:00Z", j.getString("happened_at"))
        assertEquals(400, j.getString("kind").length)
        assertEquals("h1", j.getString("household_id"))
    }

    @Test fun reportsListParses() {
        val l = AccountLogic.reportsFrom("""[{"member_name":"Awa","kind":"Freeze","version":"1.3.70","device":"Fire TV","happened_at":"2026-11-14T20:00:00+00:00"}]""")
        assertEquals(1, l.size); assertEquals("Awa", l[0].who); assertEquals("Freeze", l[0].kind)
        assertEquals(0, AccountLogic.reportsFrom("not json").size)
    }
}
