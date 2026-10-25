package dev.avillagran.ot3android.core.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.avillagran.ot3android.core.domain.Environment

/** Schema 1. Columns beyond these come with a tested migration. */
@Entity(tableName = "environments")
data class EnvironmentEntity(
    @PrimaryKey val id: String,
    val label: String,
    val baseUrl: String,
    val environmentId: String?,
    val createdAt: Long,
    val lastConnectedAt: Long?,
    val sortOrder: Int,
)

fun EnvironmentEntity.toDomain(): Environment = Environment(
    id = id,
    label = label,
    baseUrl = baseUrl,
    environmentId = environmentId,
    createdAt = createdAt,
    lastConnectedAt = lastConnectedAt,
    sortOrder = sortOrder,
)

fun Environment.toEntity(): EnvironmentEntity = EnvironmentEntity(
    id = id,
    label = label,
    baseUrl = baseUrl,
    environmentId = environmentId,
    createdAt = createdAt,
    lastConnectedAt = lastConnectedAt,
    sortOrder = sortOrder,
)
