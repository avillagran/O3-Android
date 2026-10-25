package dev.avillagran.ot3android

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import dev.avillagran.ot3android.ui.pair.PairingInbox

/**
 * Where pairing links from other apps land (`http(s)://<host>/pair#token=…`, see the
 * manifest). The intent carries a one-time code, so this activity is invisible, has no task
 * of its own in recents and keeps no history: it hands the link to [PairingInbox], opens
 * [MainActivity] with a plain intent (which then shows the pairing screen) and finishes.
 * The link never reaches the app's task or its recent-tasks record.
 */
class PairingLinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PairingInbox.linkOf(intent)?.let(appGraph.pairingInbox::offer)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}
