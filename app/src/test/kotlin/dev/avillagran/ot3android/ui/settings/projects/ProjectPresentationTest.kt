package dev.avillagran.ot3android.ui.settings.projects

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ProjectPresentationTest {
    private fun store(): ProjectPresentation {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = app.getSharedPreferences("p05-test", 0)
        prefs.edit().clear().commit()
        return ProjectPresentation(prefs)
    }
    @Test fun threadMembershipOverridesProjectAndRemovalRestoresInheritance() {
        val store = store()
        store.setProjectGroup("e/p", "Project"); store.setThreadGroup("e/t", "Thread")
        assertEquals("Thread", store.group("e/t", "e/p"))
        store.setThreadGroup("e/t", null)
        assertEquals("Project", store.group("e/t", "e/p"))
        assertNull(store.group("other/t", "other/p"))
        assertEquals(listOf("Project"), store.names())
    }
    @Test fun groupingInheritanceRemovesOverride() {
        val store = store()
        store.setGrouping("native-physical-key", ProjectGrouping.RepositoryPath)
        assertEquals("repository_path", store.snapshot().getJSONObject("sidebarProjectGroupingOverrides").getString("native-physical-key"))
        store.setGrouping("native-physical-key", ProjectGrouping.Inherit)
        assertFalse(store.snapshot().getJSONObject("sidebarProjectGroupingOverrides").has("native-physical-key"))
    }
    @Test(expected = IllegalArgumentException::class) fun groupNameIsBounded() { store().setThreadGroup("e/t", "x".repeat(49)) }
}
