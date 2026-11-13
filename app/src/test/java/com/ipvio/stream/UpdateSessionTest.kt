package com.ipvio.stream

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class UpdateSessionTest {
    private fun apk(vararg entries: Pair<String, ByteArray>): File {
        val f = File.createTempFile("test", ".apk")
        ZipOutputStream(f.outputStream()).use { z -> entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return f
    }

    @Test fun packsTheProfileAsDexMetadata() {
        val prof = ByteArray(300) { (it * 7).toByte() }
        val profm = byteArrayOf(1, 2, 3)
        val dm = UpdateSession.dexMetadata(apk("classes.dex" to byteArrayOf(9), "assets/dexopt/baseline.prof" to prof, "assets/dexopt/baseline.profm" to profm))!!
        val got = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(dm)).use { z -> while (true) { val e = z.nextEntry ?: break; got[e.name] = z.readBytes() } }
        assertEquals(listOf("primary.prof", "primary.profm"), got.keys.toList())
        assertArrayEquals(prof, got["primary.prof"])
        assertArrayEquals(profm, got["primary.profm"])
    }

    @Test fun noProfileNoMetadata() {
        assertNull(UpdateSession.dexMetadata(apk("classes.dex" to byteArrayOf(9))))
    }
}
