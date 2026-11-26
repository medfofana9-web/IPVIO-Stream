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
}
