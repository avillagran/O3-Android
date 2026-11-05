package dev.avillagran.ot3android.ui.projects

import dev.avillagran.ot3android.core.transport.T3Transport
import dev.avillagran.t3.T3Exception
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class ProjectSource(val id: String, val ready: Boolean)
data class ProjectFolder(val name: String, val path: String)
data class ProjectCreationState(
    val busy: Boolean = false, val error: String? = null,
    val newProjectsRoot: String = "", val baseDirectory: String = "~/",
    val sources: List<ProjectSource> = emptyList(), val parentPath: String = "",
    val folders: List<ProjectFolder> = emptyList(), val canBrowse: Boolean = false,
    val canOperate: Boolean = false, val canClone: Boolean = false,
    val pending: Boolean = false, val retryable: Boolean = false,
    val createdProjectId: String? = null,
)

/** Conserva el intento antes de enviar; crear/clonar nunca se reenvían sin una respuesta. */
class ProjectCreation(private val transport: T3Transport, private val journal: File,
    private val admit: suspend (suspend () -> Unit) -> Unit = { it() },
) {
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(ProjectCreationState(pending = journal.exists(),
        retryable = journal.takeIf { it.exists() }?.readText()?.let { JSONObject(it).optString("function") == "projects.mutate" } == true))
    val state = mutable.asStateFlow()

    suspend fun refresh() = exclusive {
        val config = transport.call("server.getConfig") as JSONObject
        val session = transport.authSession()
        val permissions = session.optJSONArray("permissions") ?: session.optJSONArray("scopes") ?: JSONArray()
        val scopes = (0 until permissions.length()).map { permissions.getString(it) }.toSet()
        val authenticated = session.optBoolean("authenticated")
        val operate = authenticated && "orchestration:operate" in scopes
        val clone = authenticated && "source-control:write" in scopes
        val browse = authenticated && "filesystem:read" in scopes
        val discovery = try { transport.call("server.discoverSourceControl") as JSONObject }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { JSONObject() }
        val providers = discovery.optJSONArray("sourceControlProviders") ?: JSONArray()
        val tracking = config.optJSONObject("environment")?.optJSONObject("capabilities")?.optBoolean("projectCloneTracking") == true
        val root = config.optString("newProjectsRoot").takeUnless { it == "null" }.orEmpty()
        val sources = buildList {
            if (root.isNotBlank()) add(ProjectSource("new", operate))
            add(ProjectSource("local", operate && browse))
            add(ProjectSource("url", tracking && clone && browse))
            addAll(listOf("github", "gitlab", "gitcafe", "forgejo", "bitbucket", "azure-devops").map { kind ->
                val provider = (0 until providers.length()).map { providers.getJSONObject(it) }.firstOrNull { it.optString("kind") == kind }
                ProjectSource(kind, tracking && clone && browse && provider?.optString("status") == "available" &&
                    provider.optJSONObject("auth")?.optString("status") != "unauthenticated")
            }.sortedWith(compareByDescending<ProjectSource> { it.ready }.thenBy { it.id }))
        }
        mutable.value = mutable.value.copy(newProjectsRoot = root,
            baseDirectory = config.optJSONObject("settings")?.optString("addProjectBaseDirectory")?.takeIf { it.isNotBlank() && it != "null" } ?: "~/",
            sources = sources, canOperate = operate, canClone = clone, canBrowse = browse)
        // Una lectura explícita permite recuperar un clon aceptado sin repetirlo.
        if (journal.exists()) {
            val attempt = JSONObject(journal.readText())
            val id = attempt.getJSONObject("args").optString("projectId")
            if (id.isNotBlank()) {
                val projects = projects()
                val project = (0 until projects.length()).map { projects.getJSONObject(it) }.firstOrNull { it.optString("id") == id }
                if (project != null) complete(id)
            }
        }
    }

    suspend fun browse(path: String) = exclusive {
        check(mutable.value.canBrowse) { "action_unavailable" }
        mutable.value = mutable.value.copy(parentPath = "", folders = emptyList())
        val listing = transport.call("filesystem.browse", JSONObject().put("partialPath", path.trim().let { if (it.endsWith('/')) it else "$it/" })) as JSONObject
        val entries = listing.optJSONArray("entries") ?: JSONArray()
        mutable.value = mutable.value.copy(parentPath = listing.getString("parentPath"), folders =
            (0 until entries.length()).map { entries.getJSONObject(it) }.map { ProjectFolder(it.getString("name"), it.getString("fullPath")) })
    }

    suspend fun create(source: String, nameOrRepository: String, folder: String) = exclusive {
        check(!journal.exists()) { "rpc_outcome_unknown" }
        check(mutable.value.sources.any { it.id == source && it.ready }) { "action_unavailable" }
        val value = nameOrRepository.trim()
        val id = "omarchy-project-${UUID.randomUUID()}"
        val function: String
        val args: JSONObject
        when (source) {
            "new" -> {
                require(value.isNotEmpty() && value.length <= 200 && value.none(Char::isISOControl)) { "invalid_title" }
                function = "projects.createNew"; args = JSONObject().put("name", value)
            }
            "local" -> {
                check(folder.isNotBlank() && folder == mutable.value.parentPath) { "invalid_folder" }
                val meta = transport.call("filesystem.getMetadata", JSONObject().put("paths", JSONArray().put(folder))) as JSONObject
                check(meta.getJSONArray("entries").optJSONObject(0)?.optString("kind") == "directory") { "invalid_folder" }
                val projects = projects()
                val existing = (0 until projects.length()).map { projects.getJSONObject(it) }.firstOrNull { it.optString("workspaceRoot").trimEnd('/') == folder.trimEnd('/') }
                if (existing != null) { mutable.value = mutable.value.copy(createdProjectId = existing.getString("id")); return@exclusive }
                function = "projects.mutate"
                args = JSONObject().put("type", "project.create").put("commandId", "ot3a-${UUID.randomUUID()}")
                    .put("projectId", id).put("title", folder.trimEnd('/').substringAfterLast('/').ifBlank { "Project" })
                    .put("workspaceRoot", folder).put("createWorkspaceRootIfMissing", false)
            }
            else -> {
                check(folder.isNotBlank() && folder == mutable.value.parentPath) { "invalid_folder" }
                require(value.isNotEmpty() && value.length <= 4096 && value.none(Char::isISOControl)) { "invalid_project" }
                val name = cloneName(value)
                function = "projectClone.start"
                args = JSONObject().put("projectId", id).put("title", name).put("createdAt", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date()))
                    .put("destinationPath", folder.trimEnd('/') + "/" + name)
                if (source == "url") args.put("remoteUrl", if (value.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")))
                    "https://github.com/$value" + if (value.endsWith(".git")) "" else ".git" else value)
                else args.put("provider", source).put("repository", value)
            }
        }
        val attempt = JSONObject().put("function", function).put("args", args)
        persist(attempt)
        mutable.value = mutable.value.copy(pending = true, retryable = function == "projects.mutate")
        dispatch(attempt)
    }

    suspend fun retry() = exclusive {
        val attempt = JSONObject(journal.readText())
        check(attempt.getString("function") == "projects.mutate") { "rpc_outcome_unknown" }
        dispatch(attempt)
    }
    fun opened() { mutable.value = mutable.value.copy(createdProjectId = null) }
    /** El usuario revisó sus proyectos; abandona el seguimiento sin reenviar el intento. */
    fun reviewed() {
        if (mutable.value.busy || mutable.value.retryable) return
        if (journal.exists()) check(journal.delete())
        mutable.value = mutable.value.copy(pending = false, error = null)
    }

    private suspend fun projects(): JSONArray {
        val shell = transport.shell()
        return shell.optJSONObject("snapshot")?.optJSONArray("projects") ?: shell.optJSONArray("projects") ?: JSONArray()
    }
    private suspend fun dispatch(attempt: JSONObject) {
        val function = attempt.getString("function")
        try {
            admit {
                val receipt = transport.call(function, attempt.getJSONObject("args")) as JSONObject
                val id = receipt.optString("projectId").ifBlank { receipt.optString("id") }.ifBlank { attempt.getJSONObject("args").optString("projectId") }
                check(id.isNotBlank()) { "project_receipt_missing" }
                complete(id)
            }
        } catch (error: T3Exception) {
            if (error.code != "rpc_outcome_unknown") {
                check(journal.delete()); mutable.value = mutable.value.copy(pending = false, retryable = false)
            }
            throw error
        }
    }
    private fun complete(id: String) {
        if (journal.exists()) check(journal.delete())
        mutable.value = mutable.value.copy(pending = false, retryable = false, createdProjectId = id)
    }
    private fun persist(attempt: JSONObject) {
        journal.parentFile!!.mkdirs()
        val temporary = File(journal.path + ".tmp")
        FileOutputStream(temporary).use { it.write(attempt.toString().toByteArray()); it.fd.sync() }
        check(temporary.renameTo(journal))
    }
    private suspend fun exclusive(block: suspend () -> Unit) {
        if (!mutex.tryLock()) return
        mutable.value = mutable.value.copy(busy = true, error = null)
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { mutable.value = mutable.value.copy(error = (error as? T3Exception)?.code ?: error.message ?: "action_unavailable") }
        finally { mutable.value = mutable.value.copy(busy = false); mutex.unlock() }
    }
    companion object {
        fun cloneName(repository: String): String {
            val name = repository.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/').substringAfterLast(':').removeSuffix(".git")
            require(name.isNotBlank() && name !in setOf(".", "..") && name.length <= 255 && name.none(Char::isISOControl)) { "invalid_project" }
            return name
        }
    }
}
