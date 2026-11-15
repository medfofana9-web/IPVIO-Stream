package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AvatarValueTest {
    @Test fun artworkRoundTrip() {
        val v = AvatarValue.parse(AvatarValue.art("https://image.example/b.jpg", "Mob|Land"))
        assertTrue(v is AvatarValue.V.Art)
        v as AvatarValue.V.Art
        assertEquals("https://image.example/b.jpg", v.url)
        assertEquals("Mob Land", v.name)
    }

    @Test fun photoAndUnknown() {
        assertEquals(42, (AvatarValue.parse("photo:42") as AvatarValue.V.Photo).n)
        assertNull(AvatarValue.parse("tv_detective"))      // the old built-in avatars fall back to the letter tile
        assertNull(AvatarValue.parse("img:http://x/y.jpg|a")) // only https pictures
        assertNull(AvatarValue.parse(null))
    }
}
