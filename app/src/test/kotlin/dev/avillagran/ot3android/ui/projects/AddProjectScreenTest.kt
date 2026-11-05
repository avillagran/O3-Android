package dev.avillagran.ot3android.ui.projects

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.robolectric.annotation.Config

@org.junit.runner.RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
@Config(application = Application::class)
class AddProjectScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val environment = listOf(ProjectEnvironment("node", "NODE", true))
    private val sources = listOf(ProjectSource("new", true), ProjectSource("local", true), ProjectSource("url", true)) +
        listOf("github", "gitlab", "gitcafe", "forgejo", "bitbucket", "azure-devops").map { ProjectSource(it, it == "github") }

    @Test fun desktopSourcesRemainVisibleWithUnavailableProvidersDisabled() {
        rule.setContent { Ot3Theme { AddProjectScreen(environment, "node", emptyList(), ProjectCreationState(sources = sources), {}, {}, { _, _, _ -> }, {}, {}, {}, {}) } }
        rule.onNodeWithTag("project_source_github").performScrollTo().assertIsEnabled()
        rule.onNodeWithTag("project_source_gitlab").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("project_source_azure-devops").performScrollTo().assertIsNotEnabled()
    }
    @Test fun newProjectSubmitsTheNameOnTheSelectedEnvironment() {
        var submitted: List<String>? = null
        rule.setContent { Ot3Theme { AddProjectScreen(environment, "node", emptyList(), ProjectCreationState(sources = sources, newProjectsRoot = "/host/Projects"), {}, {},
            { source, value, folder -> submitted = listOf(source, value, folder) }, {}, {}, {}, {}) } }
        rule.onNodeWithTag("project_source_new").performScrollTo().performClick()
        rule.onNodeWithTag("project_create").assertIsNotEnabled()
        rule.onNodeWithTag("project_name").performTextInput("Fresh")
        rule.onNodeWithTag("project_create").performScrollTo().performClick()
        assertEquals(listOf("new", "Fresh", ""), submitted)
    }
    @Test fun browseResolvesHostHomeAndEditingThePathRequiresBrowsingAgain() {
        val state = mutableStateOf(ProjectCreationState(sources = sources, baseDirectory = "~/"))
        var submitted: String? = null
        rule.setContent { Ot3Theme { AddProjectScreen(environment, "node", emptyList(), state.value, {},
            { state.value = state.value.copy(parentPath = "/host/Work") },
            { _, _, folder -> submitted = folder }, {}, {}, {}, {}) } }
        rule.onNodeWithTag("project_source_local").performScrollTo().performClick()
        rule.onNodeWithTag("project_path").assertTextContains("/host/Work")
        rule.onNodeWithTag("project_open_folder").assertIsEnabled()
        rule.onNodeWithTag("project_path").performTextReplacement("/different")
        rule.onNodeWithTag("project_open_folder").assertIsNotEnabled()
        assertNull(submitted)
    }
}
