package com.ipvio.stream

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StremioUrlValidationTest {
    @Test fun acceptsStandardHttpAndHttpsStreams() {
        assertTrue(StremioLogic.isHttpStreamUrl("https://cdn.example.com/movie.m3u8?token=abc"))
        assertTrue(StremioLogic.isHttpStreamUrl("http://stream.example.org/video.mp4"))
        assertTrue(StremioLogic.isHttpStreamUrl("HTTPS://stream.example.org/live"))
    }

    @Test fun rejectsMalformedAndNonHttpSources() {
        for (url in listOf("", "httpx://example.com/test", "httpwhatever", "ftp://example.com/file",
            "https://", "https://   /x", "https://user:pass@example.com/file",
            "magnet:?xt=urn:btih:123", "javascript:alert(1)")) {
            assertFalse(url, StremioLogic.isHttpStreamUrl(url))
        }
    }
}
