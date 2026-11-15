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
}
