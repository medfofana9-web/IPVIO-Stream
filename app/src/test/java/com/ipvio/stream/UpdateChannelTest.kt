package com.ipvio.stream

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateChannelTest {
    @Test fun newestReleaseSkipsDraftsAndPicksHighestVersion() {
        val a = JSONArray("""[
            {"tag_name":"v1.3.70","draft":true},
            {"tag_name":"v1.3.69","prerelease":true},
            {"tag_name":"v1.3.9"},
            {"tag_name":"v1.3.68"}
        ]""")
        assertEquals("v1.3.69", UpdateLogic.newestRelease(a)?.optString("tag_name"))
    }

    @Test fun emptyListHasNoRelease() {
        assertNull(UpdateLogic.newestRelease(JSONArray("[]")))
        assertNull(UpdateLogic.newestRelease(JSONArray("""[{"tag_name":"v2","draft":true}]""")))
    }
}
