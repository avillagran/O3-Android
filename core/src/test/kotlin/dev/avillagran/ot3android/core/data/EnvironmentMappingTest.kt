package dev.avillagran.ot3android.core.data

import dev.avillagran.ot3android.core.data.local.EnvironmentEntity
import dev.avillagran.ot3android.core.data.local.toDomain
import dev.avillagran.ot3android.core.data.local.toEntity
import dev.avillagran.ot3android.core.data.prefs.SelectionPreferences
import dev.avillagran.ot3android.core.domain.Environment
import org.junit.Assert.assertEquals
import org.junit.Test

class EnvironmentMappingTest {

    @Test
    fun entityRoundTripPreservesFields() {
        val environment = Environment(
            id = "env-1",
            label = "Laptop",
            baseUrl = "https://t3.example:3773",
            environmentId = "6f1c2a0e-3b7d-4c55-9e0a-2d8f4b1c7a90",
            createdAt = 1_761_300_000_000,
            lastConnectedAt = 1_761_300_060_000,
            sortOrder = 2,
        )

        val entity = environment.toEntity()

        assertEquals(
            EnvironmentEntity(
                id = "env-1",
                label = "Laptop",
                baseUrl = "https://t3.example:3773",
                environmentId = "6f1c2a0e-3b7d-4c55-9e0a-2d8f4b1c7a90",
                createdAt = 1_761_300_000_000,
                lastConnectedAt = 1_761_300_060_000,
                sortOrder = 2,
            ),
            entity,
        )
        assertEquals(environment, entity.toDomain())
    }

    @Test
    fun entityRoundTripKeepsUnknownDescriptorAndNeverConnected() {
        val environment = Environment(id = "env-2", label = "Desk", baseUrl = "http://10.0.0.5:3773", createdAt = 1L)

        val roundTrip = environment.toEntity().toDomain()

        assertEquals(environment, roundTrip)
        assertEquals(null, roundTrip.environmentId)
        assertEquals(null, roundTrip.lastConnectedAt)
    }

    @Test
    fun lastEnvironmentKeyNameIsStable() {
        assertEquals("last_environment_id", SelectionPreferences.Keys.LAST_ENVIRONMENT_ID.name)
    }
}
