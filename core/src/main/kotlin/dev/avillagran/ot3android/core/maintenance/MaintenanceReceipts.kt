package dev.avillagran.ot3android.core.maintenance

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

interface MaintenanceReceipts {
    fun pending(host: MaintenanceHost, page: MaintenancePage): Boolean
    fun reserve(host: MaintenanceHost, page: MaintenancePage, function: String, args: JSONObject)
    fun complete(host: MaintenanceHost, page: MaintenancePage)
}

/** P20 supplies a private app files directory. Stores receipts only; never traverses host paths. */
class FileMaintenanceReceipts(private val directory: File) : MaintenanceReceipts {
    private fun file(host: MaintenanceHost, page: MaintenancePage): File {
        val identity = JSONObject().put("environment", host.environment).put("origin", host.origin)
            .put("viewer", host.viewer).put("page", page.name).toString()
        val key = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(directory, "$key.pending")
    }
    @Synchronized override fun pending(host: MaintenanceHost, page: MaintenancePage) = file(host, page).exists()
    @Synchronized override fun reserve(host: MaintenanceHost, page: MaintenancePage, function: String, args: JSONObject) {
        check(directory.isDirectory || directory.mkdirs())
        val target = file(host, page)
        check(!target.exists()) { "maintenance_outcome_unknown" }
        // Reserve before writing: even a partial record blocks another effect.
        check(target.createNewFile())
        target.outputStream().use { stream ->
            stream.write(JSONObject().put("function", function).put("args", args).toString().toByteArray())
            stream.fd.sync()
        }
    }
    @Synchronized override fun complete(host: MaintenanceHost, page: MaintenancePage) {
        check(file(host, page).delete())
    }
}
