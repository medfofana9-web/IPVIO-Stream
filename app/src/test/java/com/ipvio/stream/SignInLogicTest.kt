package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SignInLogicTest {
    @Test
    fun codeExpiry() {
        assertEquals(SignInLogic.CODE_TTL_MS, SignInLogic.left(1000, 1000))
        assertEquals(0L, SignInLogic.left(0, SignInLogic.CODE_TTL_MS + 5))
        assertEquals("10:00", SignInLogic.clock(SignInLogic.CODE_TTL_MS))
        assertEquals("0:01", SignInLogic.clock(400))
    }

    @Test
    fun inputChecks() {
        assertEquals("Enter your email address.", SignInLogic.check("med", "x", null, false))
        assertEquals("Enter your password.", SignInLogic.check("a@b.co", "", null, false))
        assertNull(SignInLogic.check(" a@b.co ", "short", null, false))
        assertEquals("Use at least 8 characters for the password.", SignInLogic.check("a@b.co", "short", "short", true))
        assertEquals("The two passwords are different.", SignInLogic.check("a@b.co", "longenough", "longenougx", true))
        assertNull(SignInLogic.check("a@b.co", "longenough", "longenough", true))
    }

    @Test
    fun phoneProgress() {
        assertEquals(0, SignInLogic.phoneStep(100, 0, 0))
        assertEquals(0, SignInLogic.phoneStep(100, 50, 60))   // activity from an older code
        assertEquals(1, SignInLogic.phoneStep(100, 150, 0))
        assertEquals(2, SignInLogic.phoneStep(100, 150, 160))
    }
}
