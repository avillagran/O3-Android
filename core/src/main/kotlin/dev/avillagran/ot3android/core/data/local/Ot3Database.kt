package dev.avillagran.ot3android.core.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [EnvironmentEntity::class, OutboxEntity::class, DraftEntity::class, BridgeManagedEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class Ot3Database : RoomDatabase() {
    abstract fun bridgeManagedDao(): BridgeManagedDao
    abstract fun environmentDao(): EnvironmentDao
    abstract fun outboxDao(): OutboxDao
    abstract fun draftDao(): DraftDao

    companion object {
        const val NAME = "ot3.db"

        /** Adds the outbox and the drafts; the SQL is Room's, from `schemas/.../2.json`. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `outbox` (`id` TEXT NOT NULL, `environmentId` TEXT NOT NULL, " +
                        "`threadId` TEXT NOT NULL, `commandJson` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "`state` TEXT NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_outbox_environmentId_threadId` ON `outbox` (`environmentId`, `threadId`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `drafts` (`threadKey` TEXT NOT NULL, `text` TEXT NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`threadKey`))",
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `bridge_managed` (`id` TEXT NOT NULL, `provenance` TEXT NOT NULL, `status` TEXT NOT NULL, `authorizationExpiresAt` INTEGER, PRIMARY KEY(`id`))")
            }
        }

        val MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3)

        fun create(context: Context): Ot3Database =
            Room.databaseBuilder(context.applicationContext, Ot3Database::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
