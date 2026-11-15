package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every avatar has its own key and a picture address; the test TV workflow loads each address (file avatar_urls.txt). */
class AvatarUrlsTest {
    @Test fun everyAvatarHasAKeyAndAnAddress() {
        val keys = ProfileAvatars.PRESETS.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        val urls = ProfileAvatars.PRESETS.map { ProfileAvatars.url(it) }
        assertTrue(urls.all { it.startsWith("https://api.dicebear.com/9.x/") && " " !in it })
        // Avatars chosen before this set keep their picture
        assertTrue(listOf("m1", "w7").all { it in keys })
        java.io.File("avatar_urls.txt").writeText(urls.joinToString("\n") + "\n")
    }
}
