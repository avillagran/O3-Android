package dev.avillagran.ot3android.core.bridge

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BridgePairingReviewTest {
    private val peer = "a".repeat(64)
    private var now = 100L
    private fun review() = BridgePairingReview { now }
    private fun invitation(manage: Boolean = false, expires: Long = 200) = JSONObject()
        .put("address", JSONObject().put("id", peer)).put("token", "fixture-only")
        .put("expires", expires).put("manage", manage)

    private fun refused(block: () -> Unit) {
        try { block(); fail("Expected refusal") } catch (_: IllegalArgumentException) { }
    }

    @Test fun consentIsOffUnlessExplicitlyGranted() {
        val review = review()
        review.inspect(invitation())
        refused { review.consume(peer, false) }
        assertNotNull(review.target)
    }

    @Test fun peerMustMatchExactlyRatherThanANamePrefixOrDifferentCase() {
        val review = review()
        review.inspect(invitation())
        for (wrong in listOf("Fixture", peer.take(8), peer.uppercase(), "b".repeat(64))) {
            refused { review.consume(wrong, true) }
        }
        assertEquals(peer, review.consume(peer, true).second.getJSONObject("address").getString("id"))
    }

    @Test fun managementRequestsAreNeverAcceptedAsCatalogInvitations() {
        refused { review().inspect(invitation(manage = true)) }
    }

    @Test fun expiryIsCheckedAgainEvenWhenOffline() {
        val review = review()
        review.inspect(invitation())
        now = 200
        refused { review.consume(peer, true) }
    }

    @Test fun expiredInvitationCannotBeReviewed() {
        refused { review().inspect(invitation(expires = 100)) }
    }

    @Test fun aNewInvitationRequiresNewConsentEvenForTheSamePeerAndExpiry() {
        val review = review()
        val first = review.inspect(invitation())
        assertNotEquals(first, review.inspect(invitation()))
    }

    @Test fun closeDropsInvitationAndRejectsLateCompletion() {
        val review = review()
        review.inspect(invitation())
        val (ticket, _) = review.consume(peer, true)
        review.close()
        assertNull(review.target)
        assertFalse(review.isCurrent(ticket))
        try { review.inspect(invitation()); fail("closed") } catch (_: IllegalStateException) { }
    }

    @Test fun leavingTheScreenInvalidatesAnInFlightPair() {
        val review = review()
        review.inspect(invitation())
        val (ticket, _) = review.consume(peer, true)
        review.clear()
        assertFalse(review.isCurrent(ticket))
    }

    @Test fun invitationAndConsentAreConsumedOnlyOnce() {
        val review = review()
        review.inspect(invitation())
        review.consume(peer, true)
        assertNull(review.target)
        try { review.consume(peer, true); fail("consumed") } catch (_: IllegalStateException) { }
    }

    @Test fun sourceMutationCannotChangeTheReviewedPeer() {
        val review = review()
        val source = invitation()
        review.inspect(source)
        source.getJSONObject("address").put("id", "b".repeat(64))
        assertEquals(peer, review.consume(peer, true).second.getJSONObject("address").getString("id"))
    }
}
