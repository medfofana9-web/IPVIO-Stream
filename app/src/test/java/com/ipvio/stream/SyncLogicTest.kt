package com.ipvio.stream

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncLogicTest {
    private fun s(v: String) = JSONArray().put("s").put(v)
    private fun watch(vararg e: Pair<String, Long>) =
        JSONArray(e.map { JSONObject().put("i", it.first).put("t", it.first).put("ts", it.second).put("p", 1000) }).toString()

    @Test fun decideCoversAllCases() {
        assertEquals(SyncLogic.Step.UPLOAD, SyncLogic.decide(localChanged = true, cloudChanged = false, cloudExists = false))
        assertEquals(SyncLogic.Step.NOTHING, SyncLogic.decide(false, false, false))
        assertEquals(SyncLogic.Step.APPLY, SyncLogic.decide(false, true, true))
        assertEquals(SyncLogic.Step.UPLOAD, SyncLogic.decide(true, false, true))
        assertEquals(SyncLogic.Step.MERGE, SyncLogic.decide(true, true, true))
        assertEquals(SyncLogic.Step.NOTHING, SyncLogic.decide(false, false, true))
    }

    @Test fun resumeNewerWinsPerTitle() {
        val m = JSONArray(SyncLogic.mergeResume(watch("a" to 100, "b" to 300), watch("a" to 500, "c" to 200)))
        assertEquals(3, m.length())
        assertEquals("a", m.getJSONObject(0).getString("i"))
        assertEquals(500L, m.getJSONObject(0).getLong("ts"))
        assertEquals(listOf("a", "b", "c"), (0 until 3).map { m.getJSONObject(it).getString("i") })
    }

    @Test fun episodeProgressNewerSeriesWins() {
        val a = s("100|x"); val b = s("200|y")
        assertEquals("200|y", SyncLogic.newerProgress(a, b)!!.getString(1))
        assertEquals("200|y", SyncLogic.newerProgress(b, a)!!.getString(1))
    }

    @Test fun mergeJoinsListsAndKeepsOneSideOnlyKeys() {
        val local = JSONObject().put("ipvio_lib", JSONObject().put("favs", s("x\ny")))
            .put("ipvio_eprog", JSONObject().put("ser1", s("100|a")))
        val cloud = JSONObject().put("ipvio_lib", JSONObject().put("favs", s("y\nz")).put("watch", s("m")))
            .put("ipvio_eprog", JSONObject().put("ser1", s("300|b")).put("ser2", s("50|c")))
        val m = SyncLogic.merge(local, cloud)
        assertEquals("x\ny\nz", m.getJSONObject("ipvio_lib").getJSONArray("favs").getString(1))
        assertEquals("m", m.getJSONObject("ipvio_lib").getJSONArray("watch").getString(1))
        assertEquals("300|b", m.getJSONObject("ipvio_eprog").getJSONArray("ser1").getString(1))
        assertEquals("50|c", m.getJSONObject("ipvio_eprog").getJSONArray("ser2").getString(1))
    }

    @Test fun concurrentFavoriteRemovalDoesNotComeBackFromOtherTv() {
        val removed = SyncLogic.recordEdit(null, "a", false, 200L)
        val added = SyncLogic.recordEdit(null, "c", true, 150L)
        val local = JSONObject().put("ipvio_lib", JSONObject()
            .put("favs", s("b")).put("favs_edits", s(removed)))
        val cloud = JSONObject().put("ipvio_lib", JSONObject()
            .put("favs", s("a\nc")).put("favs_edits", s(added)))
        val merged = SyncLogic.merge(local, cloud)
        val lib = merged.getJSONObject("ipvio_lib")
        assertEquals("b\nc", lib.getJSONArray("favs").getString(1))
        assertEquals(false, SyncLogic.mergeEdits(
            lib.getJSONArray("favs_edits").getString(1), null
        ).getJSONObject("a").getBoolean("p"))
    }

    @Test fun addingAgainAfterRemovalWinsByEditTime() {
        val old = SyncLogic.recordEdit(null, "movie1", false, 100L)
        val newer = SyncLogic.recordEdit(old, "movie1", true, 300L)
        val local = JSONObject().put("ipvio_lib", JSONObject().put("watch", s("movie1")).put("watch_edits", s(newer)))
        val cloud = JSONObject().put("ipvio_lib", JSONObject().put("watch", s("")).put("watch_edits", s(old)))
        assertEquals("movie1", SyncLogic.merge(local, cloud).getJSONObject("ipvio_lib").getJSONArray("watch").getString(1))
    }

    @Test fun removingResumeDoesNotResurrectOldPosition() {
        val deleted = SyncLogic.recordEdit(null, "movie:a", false, 300L)
        val local = JSONObject().put("ipvio_watch", JSONObject().put("e", s("[]")).put("edits", s(deleted)))
        val cloud = JSONObject().put("ipvio_watch", JSONObject().put("e", s(watch("movie:a" to 100L))))
        val merged = SyncLogic.merge(local, cloud).getJSONObject("ipvio_watch")
        assertEquals(0, JSONArray(merged.getJSONArray("e").getString(1)).length())
    }

    @Test fun resumePlayedAfterRemovalComesBack() {
        val deleted = SyncLogic.recordEdit(null, "movie:a", false, 300L)
        val replay = SyncLogic.recordEdit(deleted, "movie:a", true, 500L)
        val local = JSONObject().put("ipvio_watch", JSONObject().put("e", s(watch("movie:a" to 500L))).put("edits", s(replay)))
        val cloud = JSONObject().put("ipvio_watch", JSONObject().put("e", s(watch("movie:a" to 100L))).put("edits", s(deleted)))
        val merged = SyncLogic.merge(local, cloud).getJSONObject("ipvio_watch")
        assertEquals(500L, JSONArray(merged.getJSONArray("e").getString(1)).getJSONObject(0).getLong("ts"))
    }

    @Test fun equalEditTimesPreferRemoval() {
        val yes = SyncLogic.recordEdit(null, "a", true, 100L)
        val no = SyncLogic.recordEdit(null, "a", false, 100L)
        val edits = SyncLogic.mergeEdits(yes, no)
        assertEquals(false, edits.getJSONObject("a").getBoolean("p"))
    }

    @Test fun fingerprintIgnoresKeyOrderButSeesChanges() {
        val a = JSONObject().put("ipvio_lib", JSONObject().put("favs", s("x")).put("watch", s("y")))
        val b = JSONObject().put("ipvio_lib", JSONObject().put("watch", s("y")).put("favs", s("x")))
        assertEquals(SyncLogic.fingerprint(a), SyncLogic.fingerprint(b))
        val c = JSONObject().put("ipvio_lib", JSONObject().put("watch", s("y")).put("favs", s("x2")))
        assertNotEquals(SyncLogic.fingerprint(a), SyncLogic.fingerprint(c))
    }

    @Test fun typedValuesRoundTrip() {
        val o = SyncLogic.encode(mapOf("s" to "t", "b" to true, "i" to 3, "l" to 4L, "f" to 1.5f, "t" to setOf("a", "b")))
        assertEquals("t", SyncLogic.decodeValue(o.getJSONArray("s")))
        assertEquals(true, SyncLogic.decodeValue(o.getJSONArray("b")))
        assertEquals(3, SyncLogic.decodeValue(o.getJSONArray("i")))
        assertEquals(4L, SyncLogic.decodeValue(o.getJSONArray("l")))
        assertEquals(1.5f, SyncLogic.decodeValue(o.getJSONArray("f")))
        assertTrue((SyncLogic.decodeValue(o.getJSONArray("t")) as Set<*>).containsAll(listOf("a", "b")))
    }
}
