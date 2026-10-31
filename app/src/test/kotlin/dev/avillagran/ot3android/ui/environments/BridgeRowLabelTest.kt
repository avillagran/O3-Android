package dev.avillagran.ot3android.ui.environments

import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.core.data.local.NATIVE_AUTHORIZED
import dev.avillagran.ot3android.core.data.local.NATIVE_EXPIRED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BridgeRowLabelTest {
    @Test fun fullAccessIsNotShownAsReadOnly() {
        assertEquals(R.string.bridge_authorized, bridgeRowLabel("authorized"))
        assertEquals(R.string.bridge_full_access, bridgeRowLabel(NATIVE_AUTHORIZED))
        assertEquals(R.string.bridge_full_access_ended, bridgeRowLabel(NATIVE_EXPIRED))
        assertNotEquals(bridgeRowLabel("authorized"), bridgeRowLabel(NATIVE_AUTHORIZED))
    }

    @Test fun otherStatusesKeepTheirLabels() {
        assertEquals(R.string.bridge_revoked, bridgeRowLabel("revoked"))
        assertEquals(R.string.bridge_expired, bridgeRowLabel("expired"))
        assertEquals(R.string.bridge_needs_authority, bridgeRowLabel("discovered"))
        assertEquals(R.string.bridge_needs_authority, bridgeRowLabel("credential_updating"))
    }
}
