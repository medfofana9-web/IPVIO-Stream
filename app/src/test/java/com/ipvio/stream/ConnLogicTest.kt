package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnLogicTest {
    @Test fun loginFromStreamAddress() {
        val a = ConnLogic.acctOf("http://prov.example:8080/live/med/secret/12345.ts")!!
        assertEquals("http://prov.example:8080", a.base); assertEquals("med", a.user); assertEquals("secret", a.pass)
        assertEquals("med", ConnLogic.acctOf("http://prov.example/med/secret/12345")!!.user)
        assertEquals("med", ConnLogic.acctOf("http://prov.example/movie/med/secret/9.mkv")!!.user)
        assertNull(ConnLogic.acctOf("https://real-debrid.com/d/ABC123"))
        assertNull(ConnLogic.acctOf("http://prov.example/get.php?username=a&password=b"))
    }

    @Test fun readsActiveAndMax() {
        val s = ConnLogic.parse("""{"user_info":{"auth":1,"active_cons":"3","max_connections":"4"}}""")!!
        assertEquals(3, s.active); assertEquals(4, s.max)
        assertNull(ConnLogic.parse("""{"user_info":{"auth":1}}"""))
        assertNull(ConnLogic.parse("not json"))
    }

    @Test fun fullOnlyWhenEveryStreamIsTaken() {
        assertFalse(ConnLogic.full(3, 4, 0))
        assertTrue(ConnLogic.full(4, 4, 0))
        // Switching channel here: this device's own stream is replaced, so it does not count
        assertFalse(ConnLogic.full(4, 4, 1))
    }
}
