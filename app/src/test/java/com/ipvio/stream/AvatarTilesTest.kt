package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Avatar catalog: unique keys, a bundled picture for each, every category filled, search and tabs work. */
class AvatarTilesTest {
    private val dir = listOf(File("src/main/assets/avatars"), File("app/src/main/assets/avatars")).first { it.isDirectory }
    private val all = AvatarCatalog.parse(File(dir, "catalog.json").readText())

    @Test fun everyAvatarIsBundled() {
        assertTrue(all.size >= 60)
        assertEquals(all.size, all.map { it.key }.toSet().size)
        for (a in all) assertTrue(a.key, File(dir, a.key + ".webp").length() in 1_000..60_000)
        for ((k, _) in AvatarCatalog.CATEGORIES.drop(1)) assertTrue(k, all.count { it.cat == k } >= 5)
        assertTrue(all.all { it.name.isNotBlank() && AvatarCatalog.label(it.cat).isNotBlank() })
    }

    @Test fun tabsAndSearch() {
        assertEquals(all.size, AvatarCatalog.filter(all, "all", "").size)
        assertTrue(AvatarCatalog.filter(all, "nb", "").all { it.cat == "nb" })
        assertTrue(AvatarCatalog.filter(all, "all", "detect").any { it.name == "The Detective" })
        assertTrue(AvatarCatalog.filter(all, "all", "soccer").all { it.cat == "so" })
        assertEquals(0, AvatarCatalog.filter(all, "all", "zzzz").size)
    }
}
