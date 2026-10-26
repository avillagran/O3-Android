package dev.avillagran.ot3android.core.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Starting point for migration tests: every exported schema must open and accept rows. */
@RunWith(AndroidJUnit4::class)
class Ot3DatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        Ot3Database::class.java,
    )

    @Test
    fun schema1AcceptsAnEnvironmentRow() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO environments (id, label, baseUrl, environmentId, createdAt, lastConnectedAt, sortOrder) " +
                    "VALUES ('env-1', 'Laptop', 'https://t3.example:3773', NULL, 1, NULL, 0)",
            )
            db.query("SELECT COUNT(*) FROM environments").use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
            }
        }
    }

    @Test
    fun migration1To2KeepsEnvironmentsAndAddsTheOutboxAndDrafts() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO environments (id, label, baseUrl, environmentId, createdAt, lastConnectedAt, sortOrder) " +
                    "VALUES ('env-1', 'Laptop', 'https://t3.example:3773', 'uuid-1', 1, 2, 0)",
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 2, true, Ot3Database.MIGRATION_1_2).use { db ->
            db.query("SELECT label, environmentId FROM environments WHERE id = 'env-1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Laptop", cursor.getString(0))
                assertEquals("uuid-1", cursor.getString(1))
            }
            db.execSQL(
                "INSERT INTO outbox (id, environmentId, threadId, commandJson, createdAt, state) " +
                    "VALUES ('ot3a-1', 'env-1', 't1', '{\"type\":\"message.dispatch\"}', 3, 'unknown')",
            )
            db.execSQL("INSERT INTO drafts (threadKey, text, updatedAt) VALUES ('env-1/t1', 'half a thought', 4)")
            db.query("SELECT state FROM outbox WHERE id = 'ot3a-1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("unknown", cursor.getString(0))
            }
            db.query("SELECT text FROM drafts WHERE threadKey = 'env-1/t1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("half a thought", cursor.getString(0))
            }
        }
    }

    @Test
    fun migration2To3KeepsLocalStateAndAddsManagedProvenance() {
        helper.createDatabase(TEST_DB, 2).use { db ->
            db.execSQL("INSERT INTO environments VALUES ('local', 'Local', 'https://local.invalid/', 'uuid', 1, 2, 3)")
            db.execSQL("INSERT INTO drafts VALUES ('local/thread', 'unfinished', 4)")
        }
        helper.runMigrationsAndValidate(TEST_DB, 3, true, Ot3Database.MIGRATION_2_3).use { db ->
            db.query("SELECT label FROM environments WHERE id = 'local'").use { cursor ->
                assertTrue(cursor.moveToFirst()); assertEquals("Local", cursor.getString(0))
            }
            db.query("SELECT text FROM drafts WHERE threadKey = 'local/thread'").use { cursor ->
                assertTrue(cursor.moveToFirst()); assertEquals("unfinished", cursor.getString(0))
            }
            db.execSQL("INSERT INTO bridge_managed VALUES ('managed', 'bridge:owner:client', 'discovered', NULL)")
        }
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
