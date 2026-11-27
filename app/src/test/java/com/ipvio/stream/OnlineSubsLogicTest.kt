package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineSubsLogicTest {
    @Test fun addressByImdbIdSeasonAndEpisode() {
        assertEquals("https://opensubtitles-v3.strem.io/subtitles/movie/tt0454848.json", OnlineSubsLogic.url("tt0454848", false, 0, 0))
        assertEquals("https://opensubtitles-v3.strem.io/subtitles/series/tt0903747:2:5.json", OnlineSubsLogic.url("tt0903747", true, 2, 5))
        assertNull(OnlineSubsLogic.url("tt0903747", true, 0, 5))
        assertNull(OnlineSubsLogic.url("../x", false, 0, 0))
    }

    @Test fun parsesAndPicksTheWantedLanguageInServerOrder() {
        val json = """{"subtitles":[
            {"id":"1","url":"https://subs.example/1","lang":"ger"},
            {"id":"2","url":"https://subs.example/2","lang":"eng"},
            {"id":"3","url":"http://insecure.example/3","lang":"eng"},
            {"id":"4","url":"https://subs.example/4","lang":"eng"},
            {"id":"5","url":"https://subs.example/2","lang":"eng"},
            {"id":"6","url":"https://subs.example/6","lang":"pob"}]}"""
        val all = OnlineSubsLogic.parse(json)
        assertEquals(listOf("https://subs.example/2", "https://subs.example/4"), OnlineSubsLogic.pick(all, "en", 3).map { it.url })
        assertEquals(listOf("https://subs.example/1"), OnlineSubsLogic.pick(all, "de", 3).map { it.url })
        assertEquals(listOf("https://subs.example/6"), OnlineSubsLogic.pick(all, "pt", 3).map { it.url })
        assertTrue(OnlineSubsLogic.parse("not json").isEmpty())
    }

    @Test fun onlyRealSubtitleFilesAreKept() {
        assertTrue(OnlineSubsLogic.looksLikeSrt("﻿1\r\n00:00:01,000 --> 00:00:02,000\r\nHi\r\n".toByteArray()))
        assertFalse(OnlineSubsLogic.looksLikeSrt("<html>Too many requests</html>".toByteArray()))
    }

    @Test fun languagesPreferredFirstThenByCount() {
        val all = listOf("eng", "ron", "ron", "ron", "spa", "spa", "eng").mapIndexed { i, l -> OnlineSubsLogic.Sub("https://s/$i", OnlineSubsLogic.lang(l)!!) }
        assertEquals(listOf("en" to 2, "ro" to 3, "es" to 2), OnlineSubsLogic.languages(all, listOf("en", "")))
        assertEquals("English", OnlineSubsLogic.langName("en"))
        assertEquals("Spanish", OnlineSubsLogic.langName("es"))
    }

    @Test fun subtitlesForTheSameReleaseComeFirst() {
        val hint = OnlineSubsLogic.Hint("Inside.Man.2006.1080p.BluRay.x264-SEPTiC.mkv", 23.976f)
        val wrongFps = OnlineSubsLogic.Sub("https://s/1", "en", "Inside.Man.2006.DVDRip.XviD", 25f)
        val sameRelease = OnlineSubsLogic.Sub("https://s/2", "en", "Inside.Man.2006.1080p.BluRay.x264-SEPTiC", 23.976f)
        val unknown = OnlineSubsLogic.Sub("https://s/3", "en", "inside man", 0f)
        val picked = OnlineSubsLogic.pick(listOf(wrongFps, unknown, sameRelease), "en", 3, hint).map { it.url }
        assertEquals(listOf("https://s/2", "https://s/3", "https://s/1"), picked)
        // Without a hint: the server's order
        assertEquals(listOf("https://s/1", "https://s/3", "https://s/2"), OnlineSubsLogic.pick(listOf(wrongFps, unknown, sameRelease), "en", 3).map { it.url })
    }

    @Test fun timingShiftMovesEveryTimeAndNeverBeforeZero() {
        val srt = "1\r\n00:00:01,000 --> 00:00:02,500\r\nHi\r\n\r\n2\r\n01:59:59,900 --> 02:00:00,100\r\nBye\r\n"
        val later = OnlineSubsLogic.shiftSrt(srt, 1500)
        assertTrue(later.contains("00:00:02,500 --> 00:00:04,000"))
        assertTrue(later.contains("02:00:01,400 --> 02:00:01,600"))
        val earlier = OnlineSubsLogic.shiftSrt(srt, -2000)
        assertTrue(earlier.contains("00:00:00,000 --> 00:00:00,500"))
        assertEquals(srt, OnlineSubsLogic.shiftSrt(srt, 0))
    }
}
