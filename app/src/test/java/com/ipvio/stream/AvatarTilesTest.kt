package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The 3D avatars: unique keys, a bundled picture for each, people and animals, distinct defaults. */
class AvatarTilesTest {
    @Test fun everyAvatarIsBundled() {
        val p = ProfileAvatars.PRESETS
        assertEquals(34, p.size)
        assertEquals(p.size, p.map { it.key }.toSet().size)
        assertEquals(17, p.count { it.people })
        val dir = listOf(File("src/main/assets/avatars"), File("app/src/main/assets/avatars")).first { it.isDirectory }
        for (a in p) assertTrue(a.key, File(dir, a.key + ".webp").length() in 1_000..80_000)
        assertTrue(p.all { it.phoneUrl.startsWith("https://raw.githubusercontent.com/microsoft/fluentui-emoji/") && " " !in it.phoneUrl })
        // Neighbouring tiles never share a color
        assertTrue(p.zipWithNext().all { (a, b) -> a.gradient != b.gradient })
    }
}
