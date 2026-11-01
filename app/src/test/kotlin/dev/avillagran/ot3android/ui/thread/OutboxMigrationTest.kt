package dev.avillagran.ot3android.ui.thread

import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import dev.avillagran.ot3android.core.data.local.Ot3Database
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OutboxMigrationTest {
    @Test fun oldRowsBecomeActionableWithoutChangingTextOrRetryBytes() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(
            ApplicationProvider.getApplicationContext()).callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    Ot3Database.MIGRATION_1_2.migrate(db)
                }
                override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            }).build())
        helper.use {
            val db = it.writableDatabase
            val bytes = """{"text":"old unsent message","attachments":[],"commandId":"legacy"}"""
            for (state in listOf("sending", "pending", "uncertain", "unknown")) {
                db.execSQL("INSERT INTO outbox VALUES (?, ?, ?, ?, ?, ?)", arrayOf<Any>(state, "env", "chat", bytes, 1, state))
            }
            Ot3Database.MIGRATION_4_5.migrate(db)
            db.query("SELECT state, commandJson FROM outbox").use { cursor ->
                assertEquals(4, cursor.count)
                while (cursor.moveToNext()) { assertEquals("unknown", cursor.getString(0)); assertEquals(bytes, cursor.getString(1)) }
            }
        }
    }
}
