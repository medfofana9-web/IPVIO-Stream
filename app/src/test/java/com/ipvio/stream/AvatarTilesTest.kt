package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The avatar tiles are drawn by the app: unique keys, every face drawable, and a valid SVG for the phone page. */
class AvatarTilesTest {
    @Test fun tilesAreUniqueAndDrawable() {
        val p = ProfileAvatars.PRESETS
        assertEquals(24, p.size)
        assertEquals(p.size, p.map { it.key }.toSet().size)
        assertEquals(p.size, p.map { it.color to it.face }.toSet().size)
        for (face in 0 until AvatarArt.FACES) assertTrue(AvatarArt.shapes(face).isNotEmpty())
        // The first five profiles get five different colors
        assertEquals(5, (0 until 5).map { p[it % p.size].color }.toSet().size)
    }

    @Test fun svgForThePhonePage() {
        for (t in ProfileAvatars.PRESETS) {
            val s = AvatarArt.svg(t.color, t.ink, t.face)
            assertTrue(s.startsWith("<svg") && s.endsWith("</svg>") && "NaN" !in s)
            val u = ProfileAvatars.svgUri(t)
            assertTrue(u.startsWith("data:image/svg+xml") && " " !in u && "+" !in u.substringAfter(','))
        }
        java.io.File("avatar_tiles.svg").writeText(AvatarArt.svg(ProfileAvatars.PRESETS[0].color, 0xFFFFFFFFL, 0))
    }
}
