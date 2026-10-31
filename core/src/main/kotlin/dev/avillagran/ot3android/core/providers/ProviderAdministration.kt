package dev.avillagran.ot3android.core.providers

import dev.avillagran.ot3android.core.transport.T3Transport
import dev.avillagran.t3.T3Exception
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** One selected host per controller. No retries, credentials on disk, or optimistic mutations. */
class ProviderAdministration(val environmentId: String, private val transport: T3Transport,
    initiallyUncertain: Boolean = false, private val configurationReceipt: (Boolean) -> Unit = {}) {
    data class State(
        val providers: List<JSONObject> = emptyList(), val busy: Boolean = false,
        val canManage: Boolean = false, val errorCode: String? = null,
        val uncertain: Boolean = false, val setup: JSONObject? = null,
        val setupInstanceId: String? = null,
        val configuration: JSONObject? = null, val canConfigure: Boolean = false,
    )
    private var configurationUnresolved = initiallyUncertain
    private val mutable = MutableStateFlow(State(uncertain = initiallyUncertain))
    val state = mutable.asStateFlow()

    suspend fun refresh(fresh: Boolean = false) = run(false) {
        if (fresh) transport.call("server.refreshProviders", JSONObject().put("fresh", true).put("refreshModels", true))
        val config = transport.call("server.getConfig") as JSONObject
        val auth = transport.authSession()
        val scopes = auth.optJSONArray("permissions") ?: auth.optJSONArray("scopes")
        val manage = auth.optBoolean("authenticated") && (0 until (scopes?.length() ?: 0)).any {
            scopes?.optString(it) == "providers:manage"
        }
        val rows = config.optJSONArray("providers")
        // Deliberate display allowlist: never retain raw config/settings/environment secrets.
        val keys = listOf("instanceId", "displayName", "driver", "enabled", "installed", "version")
        val providers = (0 until (rows?.length() ?: 0)).mapNotNull { rows?.optJSONObject(it) }.map { raw ->
            JSONObject().also { row ->
                keys.forEach { key -> if (raw.has(key)) row.put(key, raw.get(key)) }
                // Direct presentation of server-advertised fields, never capability inference.
                for ((source, fields) in mapOf(
                    "setup" to mapOf("canInstall" to "canInstall", "canAuthenticate" to "canAuthenticate"),
                    "auth" to mapOf("status" to "authStatus", "canLogout" to "canLogout"),
                    "versionAdvisory" to mapOf("status" to "versionStatus", "currentVersion" to "currentVersion",
                        "latestVersion" to "latestVersion", "canUpdate" to "canUpdate"),
                )) raw.optJSONObject(source)?.let { nested -> fields.forEach { (from, to) ->
                    if (nested.has(from)) row.put(to, nested.get(from))
                } }
            }
        }
        val canConfigure = auth.optBoolean("authenticated") && (0 until (scopes?.length() ?: 0)).any { scopes!!.optString(it) == "settings:write" }
        val configuration = if (canConfigure) {
            val settings = transport.call("server.getSettings") as JSONObject
            dev.avillagran.ot3android.core.transport.NativeParity().providers(settings, config, true)
        } else null
        mutable.value = State(providers = providers, busy = true, canManage = manage, uncertain = configurationUnresolved,
            configuration = configuration, canConfigure = canConfigure)
    }

    suspend fun configure(input: JSONObject) = run(false) {
        check(mutable.value.canConfigure && !mutable.value.uncertain)
        val auth = transport.authSession()
        val permissions = auth.optJSONArray("permissions") ?: auth.optJSONArray("scopes")
        check(auth.optBoolean("authenticated") && (0 until (permissions?.length() ?: 0)).any { permissions!!.optString(it) == "settings:write" })
        val settings = transport.call("server.getSettings") as JSONObject
        val native = dev.avillagran.ot3android.core.transport.NativeParity()
        val payload = native.providerMutation(input, settings)
        // No secrets are persisted; unknown mutations require explicit readback review, never replay.
        configurationReceipt(true)
        configurationUnresolved = true
        mutable.value = mutable.value.copy(uncertain = true, configuration = null)
        transport.call("server.updateSettings", payload)
        val saved = transport.call("server.getSettings") as JSONObject
        check(native.confirmProvider(input.getString("action"), input.getString("instanceId"), payload, saved)) { "provider_readback_mismatch" }
        configurationReceipt(false)
        configurationUnresolved = false
        mutable.value = mutable.value.copy(uncertain = false, configuration = null)
    }

    /** Explicit review of a fresh safe projection releases an unknown write without replaying it. */
    fun reviewedConfiguration() {
        if (!mutable.value.busy && mutable.value.configuration != null) {
            configurationReceipt(false)
            configurationUnresolved = false
            mutable.value = mutable.value.copy(uncertain = false)
        }
    }

    enum class Action(val function: String) {
        Update("server.updateProvider"), Install("provider.install.start"),
        SignIn("provider.auth.start"), SignOut("provider.auth.logout"),
        CancelSignIn("provider.auth.cancel"), CancelInstall("provider.install.cancel"),
    }

    suspend fun act(instanceId: String, action: Action) = run(true) {
        check(mutable.value.canManage && !mutable.value.uncertain) { "provider_action_unavailable" }
        val row = mutable.value.providers.first { it.optString("instanceId") == instanceId }
        val permitted = when (action) {
            Action.Update -> row.optBoolean("canUpdate")
            Action.Install -> row.optBoolean("canInstall")
            Action.SignIn -> row.optBoolean("canAuthenticate")
            Action.SignOut -> row.optBoolean("canLogout")
            Action.CancelSignIn -> mutable.value.setupInstanceId == instanceId && mutable.value.setup?.has("flowId") == true
            Action.CancelInstall -> mutable.value.setupInstanceId == instanceId && mutable.value.setup?.has("operationId") == true
        }
        check(permitted) { "provider_action_unavailable" }
        val args = JSONObject().put("instanceId", instanceId)
        when (action) {
            Action.Update -> args.put("provider", row.getString("driver"))
            Action.CancelSignIn -> args.put("flowId", mutable.value.setup!!.getString("flowId"))
            Action.CancelInstall -> args.put("operationId", mutable.value.setup!!.getString("operationId"))
            else -> Unit
        }
        val result = transport.call(action.function, args) as? JSONObject
        mutable.value = mutable.value.copy(setup = if (action == Action.Update) null else result, setupInstanceId = instanceId)
    }

    /** Explicit refresh of the native first stream item; no client-side flow state machine. */
    suspend fun refreshSetup(instanceId: String, install: Boolean) = run(false) {
        val value = transport.call(if (install) "provider.installState" else "provider.authState",
            JSONObject().put("instanceId", instanceId)) as? JSONObject
        mutable.value = mutable.value.copy(setup = value, setupInstanceId = instanceId)
    }

    /** Values only live in the caller's ephemeral form and this single call. */
    suspend fun respond(instanceId: String, flowId: String, interactionId: String, response: JSONObject) = run(true) {
        check(mutable.value.canManage && !mutable.value.uncertain && mutable.value.setupInstanceId == instanceId)
        check(mutable.value.setup?.optString("flowId") == flowId)
        val current = mutable.value.setup?.optJSONObject("interaction")
        check(current?.optString("id") == interactionId)
        val result = transport.call("provider.auth.respond", JSONObject().put("instanceId", instanceId)
            .put("flowId", flowId).put("interactionId", interactionId).put("response", response)) as? JSONObject
        mutable.value = mutable.value.copy(setup = result)
    }

    private suspend fun run(mutation: Boolean, block: suspend () -> Unit) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, errorCode = null)
        try { block() }
        catch (cancelled: CancellationException) {
            if (mutation) mutable.value = mutable.value.copy(uncertain = true)
            throw cancelled
        } catch (error: Exception) {
            // Error messages may echo host data. Retain only stable codes.
            mutable.value = mutable.value.copy(errorCode = (error as? T3Exception)?.code ?: "provider_request_failed",
                uncertain = mutable.value.uncertain || mutation)
        } finally { mutable.value = mutable.value.copy(busy = false) }
    }
}
