package dev.avillagran.ot3android.core.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
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

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
