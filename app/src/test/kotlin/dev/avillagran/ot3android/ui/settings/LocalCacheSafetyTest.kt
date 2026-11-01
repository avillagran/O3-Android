package dev.avillagran.ot3android.ui.settings

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.avillagran.ot3android.core.data.local.*
import dev.avillagran.ot3android.core.thread.ThreadCache
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalCacheSafetyTest {
    @get:Rule val root = TemporaryFolder()
    @Test fun clearAndEvictionPreserveRealRoomDraftsAndPendingOutbox() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), Ot3Database::class.java).build()
        try {
            val draft = DraftEntity("env/thread", "unsent text", 123)
            val outbox = OutboxEntity("command", "env", "thread", "{\"text\":\"pending\"}", 124, "unknown")
            db.draftDao().upsert(draft); db.outboxDao().insert(outbox)
            val cache = ThreadCache(root.newFolder())
            val lease = cache.open("env", "thread", "authority")!!
            cache.write(lease, JSONObject().put("projection", JSONObject()))
            cache.clear(); cache.retainEnvironments(emptySet()); cache.trim()
            assertEquals(draft, db.draftDao().find(draft.threadKey))
            assertEquals(outbox, db.outboxDao().findById(outbox.id))
            assertEquals(0, cache.usage().entries)
        } finally { db.close() }
    }
}
