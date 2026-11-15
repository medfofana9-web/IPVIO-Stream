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
