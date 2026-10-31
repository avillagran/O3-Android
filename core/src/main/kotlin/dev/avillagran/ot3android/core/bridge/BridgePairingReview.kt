package dev.avillagran.ot3android.core.bridge

import org.json.JSONObject

/** Consent belongs to this invitation and this screen lifetime, never to a display name. */
class BridgePairingReview(private val clockSeconds: () -> Long = { System.currentTimeMillis() / 1000 }) {
    data class Target(val peerId: String, val expiresAt: Long, val revision: Long)
    private var invitation: JSONObject? = null
    private var generation = 0L
    private var closed = false
    var target: Target? = null
        private set

    @Synchronized
    fun inspect(value: JSONObject): Target {
        check(!closed)
        clear()
        require(!value.optBoolean("manage", false)) { "management_invitation_unsupported" }
        val peer = value.getJSONObject("address").getString("id")
        require(peer.length == 64 && peer.all { it in '0'..'9' || it in 'a'..'f' }) { "invalid_peer_id" }
        val expires = value.getLong("expires")
        require(expires > clockSeconds()) { "invitation_expired" }
        invitation = JSONObject(value.toString())
        return Target(peer, expires, generation).also { target = it }
    }

    /** Consumes consent before any suspension. A retry must inspect and consent again. */
    @Synchronized
    fun consume(exactPeerId: String, consent: Boolean): Pair<Long, JSONObject> {
        check(!closed)
        val current = checkNotNull(target)
        require(consent && exactPeerId == current.peerId) { "peer_confirmation_required" }
        require(current.expiresAt > clockSeconds()) { "invitation_expired" }
        val request = checkNotNull(invitation)
        invitation = null
        target = null
        return generation to request
    }

    @Synchronized
    fun isCurrent(ticket: Long): Boolean = !closed && generation == ticket

    @Synchronized
    fun clear() {
        generation++
        invitation = null
        target = null
    }

    @Synchronized
    fun close() {
        clear()
        closed = true
    }
}
