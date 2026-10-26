package dev.avillagran.ot3android.notifications

import org.junit.Assert.*
import org.junit.Test

class FollowVisibilityTest {
    @Test fun backgroundNeverSuppressesAndOldNavigationDisposalCannotClearNewThread() {
        val visibility = FollowVisibility()
        val first = visibility.claim(ThreadRef("node", "first"))
        assertNull(visibility.onScreen.value)
        visibility.setVisible(true)
        assertEquals("first", visibility.onScreen.value!!.threadId)
        val second = visibility.claim(ThreadRef("vq", "second"))
        first.close()
        assertEquals("second", visibility.onScreen.value!!.threadId)
        visibility.setVisible(false)
        assertNull(visibility.onScreen.value)
        visibility.setVisible(true)
        assertEquals("second", visibility.onScreen.value!!.threadId)
        second.close(); second.close()
        assertNull(visibility.onScreen.value)
    }
}
