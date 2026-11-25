package com.ipvio.stream

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-007: one account per household. Profile lining-up between a TV and the account, sync sides, PIN rules, addons,
 * and two simulated TVs syncing one profile's Continue Watching through the same steps as CloudSync.
 */
class AccountCloudLogicTest {
    private fun lp(id: Int, name: String, blank: Boolean = false) = AccountCloudLogic.LocalP(id, name, blank)
    private fun cp(id: String, slot: Int, name: String, deleted: Boolean = false) = AccountCloudLogic.CloudP(id, slot, name, "", deleted)

    @Test fun firstTvUploadsAllProfiles() {
        val p = AccountCloudLogic.plan(listOf(lp(0, "Med"), lp(1, "Naya")), emptyList(), emptyMap())
        assertEquals(listOf(0 to 0, 1 to 1), p.upload)
        assertTrue(p.createLocal.isEmpty()); assertTrue(p.links.isEmpty())
    }

    @Test fun freshSecondTvGetsAccountProfilesAndKeepsItsOwnApart() {
        // The TV had only its untouched first profile; the account has Med and Naya: nothing is merged into them
        val p = AccountCloudLogic.plan(listOf(lp(0, "Main profile", blank = true)), listOf(cp("med", 0, "Med"), cp("naya", 1, "Naya")), emptyMap(), localOnly = setOf(0))
        assertTrue(p.links.isEmpty())
        assertEquals(listOf("med", "naya"), p.createLocal.map { it.id })
        assertTrue(p.upload.isEmpty())
    }

    @Test fun sameNamesAreNeverMerged() {
        // A local profile called like an account profile stays apart (this mixed profiles before)
        val p = AccountCloudLogic.plan(listOf(lp(0, "Naya"), lp(1, "Med")), listOf(cp("med", 0, "Med"), cp("naya", 1, "Naya")), emptyMap(), localOnly = setOf(0, 1))
        assertTrue(p.links.isEmpty()); assertTrue(p.upload.isEmpty())
        assertEquals(2, p.createLocal.size)
    }

    @Test fun profileCreatedAfterSignInBecomesItsOwnAccountProfile() {
        val p = AccountCloudLogic.plan(listOf(lp(0, "Med"), lp(1, "Guest")), listOf(cp("med", 0, "Med"), cp("naya", 1, "Naya")), mapOf(0 to "med"))
        assertEquals(listOf(1 to 2), p.upload)          // next free slot, never Naya's
        assertEquals(listOf("naya"), p.createLocal.map { it.id })
    }

    @Test fun deletedInAccountRemovesLocalCopy() {
        val p = AccountCloudLogic.plan(listOf(lp(0, "Med"), lp(2, "Old")), listOf(cp("med", 0, "Med"), cp("old", 1, "Old", deleted = true)), mapOf(0 to "med", 2 to "old"))
        assertEquals(listOf(2), p.removeLocal)
        assertEquals(mapOf(0 to "med"), p.links)
        // The deleted profile's slot is not reused (unique per account)
        val q = AccountCloudLogic.plan(listOf(lp(0, "Med"), lp(3, "New")), listOf(cp("med", 0, "Med"), cp("old", 1, "Old", deleted = true)), mapOf(0 to "med"))
        assertEquals(listOf(3 to 2), q.upload)
    }

    @Test fun neverMoreThanTenProfiles() {
        val cloud = (0 until 10).map { cp("c$it", it, "P$it") }
        val p = AccountCloudLogic.plan(listOf(lp(0, "Extra")), cloud, emptyMap())
        assertTrue(p.upload.isEmpty())
    }

    @Test fun changedSideWins() {
        assertEquals(AccountCloudLogic.Side.NONE, AccountCloudLogic.side("Med", "Med", "Med"))
        assertEquals(AccountCloudLogic.Side.PUSH, AccountCloudLogic.side("Medo", "Med", "Med"))   // renamed on this TV
        assertEquals(AccountCloudLogic.Side.APPLY, AccountCloudLogic.side("Med", "Medo", "Med"))  // renamed elsewhere
        assertEquals(AccountCloudLogic.Side.PUSH, AccountCloudLogic.side("A", "B", "C"))           // both: this TV
        assertEquals("Med" to "img:x", AccountCloudLogic.readSnap(AccountCloudLogic.snap("Med", "img:x")))
    }

    @Test fun pinRules() {
        assertTrue(AccountCloudLogic.validPin("1234")); assertTrue(AccountCloudLogic.validPin("123456"))
        assertFalse(AccountCloudLogic.validPin("123")); assertFalse(AccountCloudLogic.validPin("1234567")); assertFalse(AccountCloudLogic.validPin("12a4"))
        assertEquals("Try again in a minute.", AccountCloudLogic.waitText(60))
        assertEquals("Try again in 4 minutes.", AccountCloudLogic.waitText(240))
    }

    @Test fun addonsJoinWithoutDuplicates() {
        val m = AccountCloudLogic.mergeAddons(listOf("https://a/manifest.json", "https://B/manifest.json"), listOf("https://b/manifest.json", "https://c/manifest.json"))
        assertEquals(listOf("https://a/manifest.json", "https://B/manifest.json", "https://c/manifest.json"), m)
        assertEquals(m, AccountCloudLogic.parseAddons(AccountCloudLogic.addonsJson(m)))
        assertEquals(emptyList<String>(), AccountCloudLogic.parseAddons("not json"))
    }

    @Test fun mapRoundTrip() {
        val m = mapOf(0 to "a", 3 to "b")
        assertEquals(m, AccountCloudLogic.readMap(AccountCloudLogic.writeMap(m)))
        assertTrue(AccountCloudLogic.readMap(null).isEmpty())
    }

    // ---------------------------------------------------------------- two TVs, one profile (CloudSync's steps)

    /** The account's copy of one profile's data, with a change counter as "updated_at". */
    private class Cloud { var data: JSONObject? = null; var at = 0 }

    /** One TV: its local stores of the profile and what it saw at its last sync. */
    private class Tv { var files = JSONObject(); var hash: String? = null; var at: String? = null }

    private fun resume(vararg e: Triple<String, Long, Long>): JSONObject {
        val a = JSONArray(); e.forEach { (id, pos, ts) -> a.put(JSONObject().put("i", id).put("p", pos).put("ts", ts)) }
        return JSONObject().put("ipvio_watch", JSONObject().put("e", JSONArray().put("s").put(a.toString())))
    }

    private fun positions(files: JSONObject): Map<String, Long> {
        val raw = files.optJSONObject("ipvio_watch")?.optJSONArray("e")?.optString(1) ?: return emptyMap()
        val a = JSONArray(raw)
        return (0 until a.length()).associate { a.getJSONObject(it).let { o -> o.getString("i") to o.getLong("p") } }
    }

    /** Same decisions as CloudSync.once. */
    private fun sync(tv: Tv, cloud: Cloud) {
        val localChanged = SyncLogic.fingerprint(tv.files) != tv.hash
        val cloudAt = cloud.data?.let { cloud.at.toString() }
        val cloudChanged = cloudAt != null && cloudAt != tv.at
        fun upload(f: JSONObject) { cloud.data = JSONObject(f.toString()); cloud.at++; tv.hash = SyncLogic.fingerprint(f); tv.at = cloud.at.toString() }
        when (SyncLogic.decide(localChanged, cloudChanged, cloudAt != null)) {
            SyncLogic.Step.NOTHING -> {}
            SyncLogic.Step.UPLOAD -> upload(tv.files)
            SyncLogic.Step.APPLY -> { tv.files = JSONObject(cloud.data.toString()); tv.hash = SyncLogic.fingerprint(tv.files); tv.at = cloudAt }
            SyncLogic.Step.MERGE -> { tv.files = SyncLogic.merge(tv.files, cloud.data!!); upload(tv.files) }
        }
    }

    @Test fun movieResumesOnTheOtherTv() {
        val cloud = Cloud(); val living = Tv(); val bedroom = Tv()
        living.files = resume(Triple("movie:tt1", 47 * 60_000L, 1000))
        sync(living, cloud)
        sync(bedroom, cloud)
        assertEquals(47 * 60_000L, positions(bedroom.files)["movie:tt1"])
    }

    @Test fun staleTvDoesNotOverwriteNewerProgress() {
        val cloud = Cloud(); val a = Tv(); val b = Tv()
        a.files = resume(Triple("movie:tt1", 10 * 60_000L, 100)); sync(a, cloud); sync(b, cloud)
        // B goes offline and keeps an old point; A watches on to 47 min
        a.files = resume(Triple("movie:tt1", 47 * 60_000L, 300)); sync(a, cloud)
        b.files = resume(Triple("movie:tt1", 12 * 60_000L, 200), Triple("show:tt2:s1e3", 5 * 60_000L, 210))
        // B comes back online: both changed, merged per title, newer wins
        sync(b, cloud); sync(a, cloud)
        assertEquals(47 * 60_000L, positions(cloud.data!!)["movie:tt1"])
        assertEquals(5 * 60_000L, positions(cloud.data!!)["show:tt2:s1e3"])
        assertEquals(positions(cloud.data!!), positions(a.files))
    }

    @Test fun profilesStaySeparate() {
        // Each profile has its own account copy: A's progress never reaches B's copy
        val cloudA = Cloud(); val cloudB = Cloud(); val tv = Tv(); val tvB = Tv()
        tv.files = resume(Triple("movie:tt1", 30 * 60_000L, 100)); sync(tv, cloudA)
        sync(tvB, cloudB)
        assertTrue(positions(tvB.files).isEmpty())
        assertTrue(positions(cloudB.data ?: JSONObject()).isEmpty())
        assertEquals(30 * 60_000L, positions(cloudA.data!!)["movie:tt1"])
    }

    @Test fun addonListCoversOnlyWhenEveryAccountAddonIsPresent() {
        val dragon = "https://dragon.example.com/abc123"
        val pengu = "https://pengu.example.org"
        val local = AccountCloudLogic.addonsJson(listOf(pengu))
        assertFalse(AccountCloudLogic.covers(local, AccountCloudLogic.addonsJson(listOf(dragon, pengu))))
        assertTrue(AccountCloudLogic.covers(AccountCloudLogic.addonsJson(listOf(pengu, dragon)), AccountCloudLogic.addonsJson(listOf(dragon))))
        // Same addon written as a manifest link or with another case counts as present
        assertTrue(AccountCloudLogic.covers(AccountCloudLogic.addonsJson(listOf(dragon)), AccountCloudLogic.addonsJson(listOf("$dragon/manifest.json"))))
        assertTrue(AccountCloudLogic.covers(local, "[]"))
    }

    @Test fun addonHostNeverIncludesThePrivateConfigPath() {
        assertEquals("dragon.example.com", AccountCloudLogic.addonHost("https://dragon.example.com/eyJzZWNyZXQiOjF9/manifest.json"))
    }
}
