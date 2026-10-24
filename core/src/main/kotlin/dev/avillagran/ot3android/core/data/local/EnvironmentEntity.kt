package dev.avillagran.ot3android.core.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.avillagran.ot3android.core.domain.Environment

@Entity(tableName = "environments")
data class EnvironmentEntity(
    @PrimaryKey val id: String,
    val label: String,
    val baseUrl: String,
)

fun EnvironmentEntity.toDomain(): Environment = Environment(id = id, label = label, baseUrl = baseUrl)

fun Environment.toEntity(): EnvironmentEntity = EnvironmentEntity(id = id, label = label, baseUrl = baseUrl)
