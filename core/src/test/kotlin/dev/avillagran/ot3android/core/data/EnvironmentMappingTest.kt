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
        val environment = Environment(id = "env-1", label = "Laptop", baseUrl = "https://t3.example:3773")

        val entity = environment.toEntity()

        assertEquals(EnvironmentEntity("env-1", "Laptop", "https://t3.example:3773"), entity)
        assertEquals(environment, entity.toDomain())
    }

    @Test
    fun lastEnvironmentKeyNameIsStable() {
        assertEquals("last_environment_id", SelectionPreferences.Keys.LAST_ENVIRONMENT_ID.name)
    }
}
