package dev.avillagran.ot3android.ui.environments

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BridgePeerPresentationTest {
    @Test fun duplicateNamesRetainDistinctIdentityAndGrantDirection() {
        val admin = bridgePeerPresentation(JSONObject().put("name", "mbp-m2").put("id", "a".repeat(64))
            .put("managed", true).put("services", org.json.JSONArray(listOf("drafts"))))
        val drafts = bridgePeerPresentation(JSONObject().put("name", "mbp-m2").put("id", "b".repeat(64))
            .put("remote_services", org.json.JSONArray(listOf("drafts"))))
        assertEquals(admin.name, drafts.name)
        assertNotEquals(admin.id, drafts.id)
        assertFalse(admin.drafts)
        assertTrue(admin.managed)
        assertTrue(drafts.drafts)
        assertFalse(drafts.managed)
        assertTrue(drafts.validId)
    }
    @Test fun invalidIdentityCannotBeSelected() {
        assertFalse(bridgePeerPresentation(JSONObject().put("id", "not-an-id")).validId)
        assertFalse(bridgePeerPresentation(JSONObject()).drafts)
    }
}
