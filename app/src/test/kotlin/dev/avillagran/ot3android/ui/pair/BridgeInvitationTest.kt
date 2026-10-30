package dev.avillagran.ot3android.ui.pair

import org.junit.Assert.assertEquals
import org.junit.Test

class BridgeInvitationTest {

    @Test
    fun anInvitationAskingToManageSaysSo() {
        val refused = IllegalArgumentException("management_invitation_unsupported")
        assertEquals("management_unsupported", BridgeInvitation.failureStatus(refused))
    }

    @Test
    fun anyOtherFailureStaysGeneric() {
        assertEquals("failed", BridgeInvitation.failureStatus(IllegalArgumentException("invalid_invitation")))
        assertEquals("failed", BridgeInvitation.failureStatus(IllegalStateException("management_invitation_unsupported")))
        assertEquals("failed", BridgeInvitation.failureStatus(RuntimeException("offline")))
    }
}
