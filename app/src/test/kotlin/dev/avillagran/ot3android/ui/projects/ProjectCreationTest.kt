package dev.avillagran.ot3android.ui.projects

import dev.avillagran.ot3android.core.transport.*
import dev.avillagran.t3.T3Exception
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProjectCreationTest {
    @get:Rule val files = TemporaryFolder(File("/var/tmp/o3-project-tests").apply { mkdirs() })
    private suspend fun fixture(scopes: String = "orchestration:operate,source-control:write,filesystem:read"): Pair<FakeT3Transport, ProjectCreation> {
        val fake = FakeT3Transport()
        fake.connect(ConnectRoute.Bearer("http://fixture", "fixture"))
        fake.onCall("server.getConfig") { JSONObject("""{"newProjectsRoot":"/host/Projects","settings":{"addProjectBaseDirectory":"/host/Work"},"environment":{"capabilities":{"projectCloneTracking":true}}}""") }
        fake.onCall("server.discoverSourceControl") { JSONObject("""{"sourceControlProviders":[{"kind":"github","status":"available","auth":{"status":"authenticated"}},{"kind":"gitlab","status":"available","auth":{"status":"unauthenticated"}}]}""") }
        fake.onCall("filesystem.browse") { JSONObject("""{"parentPath":"/host/Work","entries":[{"name":"repo","fullPath":"/host/Work/repo"}]}""") }
        fake.onCall("filesystem.getMetadata") { JSONObject("""{"entries":[{"kind":"directory"}]}""") }
        fake.onShell { JSONObject("""{"projects":[]}""") }
        val transport = object : T3Transport by fake {
            override suspend fun authSession() = JSONObject().put("authenticated", true).put("permissions", org.json.JSONArray(scopes.split(',')))
        }
        val controller = ProjectCreation(transport, File(files.root, "attempt.json"))
        controller.refresh()
        return fake to controller
    }

    @Test fun hostCapabilitiesAndAuthGateEveryDesktopSource() = runBlocking {
        val (_, controller) = fixture()
        val sources = controller.state.value.sources.associate { it.id to it.ready }
        assertEquals(setOf("new", "local", "url", "github", "gitlab", "gitcafe", "forgejo", "bitbucket", "azure-devops"), sources.keys)
        assertEquals(true, sources["github"])
        assertEquals(false, sources["gitlab"])
        assertEquals("/host/Work", controller.state.value.baseDirectory)
    }
    @Test fun readOnlySessionCannotCreateOrClone() = runBlocking {
        val (fake, controller) = fixture("filesystem:read")
        assertTrue(controller.state.value.sources.none { it.ready })
        controller.create("new", "Fresh", "")
        assertTrue(fake.calls.none { it.fn in setOf("projects.createNew", "projectClone.start", "projects.mutate") })
    }
    @Test fun localFolderUsesHostMetadataAndReceiptIdRatherThanProposedId() = runBlocking {
        val (fake, controller) = fixture()
        controller.browse("/host/Work")
        fake.onCall("projects.mutate") { args ->
            assertEquals("project.create", args.getString("type"))
            assertEquals("/host/Work", args.getString("workspaceRoot"))
            assertFalse(args.getBoolean("createWorkspaceRootIfMissing"))
            assertTrue(File(files.root, "attempt.json").exists())
            JSONObject().put("id", "registered")
        }
        controller.create("local", "", "/host/Work")
        assertEquals("registered", controller.state.value.createdProjectId)
        assertFalse(File(files.root, "attempt.json").exists())
    }
    @Test fun staleOrFailedBrowseCannotSubmitThePreviousFolder() = runBlocking {
        val (fake, controller) = fixture()
        controller.browse("/host/Work")
        fake.onCall("filesystem.browse") { throw T3Exception("rpc_rejected") }
        controller.browse("/missing")
        controller.create("local", "", "/host/Work")
        assertTrue(fake.calls.none { it.fn == "projects.mutate" })
    }
    @Test fun unknownCreateIsPersistedAndNeverReplayedByRefreshOrRetry() = runBlocking {
        val (fake, controller) = fixture()
        fake.onCall("projects.createNew") { throw T3Exception("rpc_outcome_unknown") }
        controller.create("new", "Fresh", "")
        controller.refresh()
        controller.retry()
        controller.create("new", "Fresh", "")
        assertTrue(controller.state.value.pending)
        assertFalse(controller.state.value.retryable)
        assertEquals(1, fake.calls.count { it.fn == "projects.createNew" })
        val restored = ProjectCreation(fake, File(files.root, "attempt.json"))
        assertTrue(restored.state.value.pending)
        assertFalse(restored.state.value.retryable)
    }
    @Test fun unknownLocalRegistrationRetriesExactCommandIdAndPayload() = runBlocking {
        val (fake, controller) = fixture()
        controller.browse("/host/Work")
        fake.onCall("projects.mutate") { throw T3Exception("rpc_outcome_unknown") }
        controller.create("local", "", "/host/Work")
        assertTrue(controller.state.value.retryable)
        fake.onCall("projects.mutate") { JSONObject().put("id", "registered") }
        controller.retry()
        val calls = fake.calls.filter { it.fn == "projects.mutate" }
        assertEquals(calls[0].args.toString(), calls[1].args.toString())
        assertEquals("registered", controller.state.value.createdProjectId)
    }
    @Test fun cloneSupportsGitUrlAndProvidersWithHostDestination() = runBlocking {
        val (fake, controller) = fixture()
        controller.browse("/host/Work")
        fake.onCall("projectClone.start") { args -> JSONObject().put("projectId", args.getString("projectId")) }
        controller.create("url", "owner/repository", "/host/Work")
        val url = fake.calls.last().args
        assertEquals("https://github.com/owner/repository.git", url.getString("remoteUrl"))
        assertEquals("/host/Work/repository", url.getString("destinationPath"))
        controller.opened()
        controller.create("github", "owner/repository", "/host/Work")
        assertEquals("github", fake.calls.last().args.getString("provider"))
        assertFalse(fake.calls.last().args.has("remoteUrl"))
    }
    @Test fun existingFolderOpensWithoutRegisteringAgain() = runBlocking {
        val (fake, controller) = fixture()
        controller.browse("/host/Work")
        fake.onShell { JSONObject("""{"projects":[{"id":"existing","workspaceRoot":"/host/Work/"}]}""") }
        controller.create("local", "", "/host/Work")
        assertEquals("existing", controller.state.value.createdProjectId)
        assertTrue(fake.calls.none { it.fn == "projects.mutate" })
    }
}
