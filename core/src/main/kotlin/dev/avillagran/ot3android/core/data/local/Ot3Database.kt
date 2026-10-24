package dev.avillagran.ot3android.core.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [EnvironmentEntity::class], version = 1, exportSchema = true)
abstract class Ot3Database : RoomDatabase() {
    abstract fun environmentDao(): EnvironmentDao

    companion object {
        const val NAME = "ot3.db"

        fun create(context: Context): Ot3Database =
            Room.databaseBuilder(context.applicationContext, Ot3Database::class.java, NAME).build()
    }
}
