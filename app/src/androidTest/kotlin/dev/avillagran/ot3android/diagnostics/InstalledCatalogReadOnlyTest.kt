package dev.avillagran.ot3android.diagnostics

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.security.MessageDigest
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnitRunner
import dev.avillagran.ot3android.core.bridge.NativeBridge
import dev.avillagran.ot3android.core.data.local.Ot3Database
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** The separate runner avoids AppGraph initializers, migrations and reconnection. */
class InstalledCatalogReadOnlyRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application =
        super.newApplication(cl, Application::class.java.name, context)
}

/** Opt-in installed-client metadata probe. Never reads credentials, messages or route URLs. */
class InstalledCatalogReadOnlyTest {
    @Test fun reportSavedCatalogWithoutStartingTheApplicationGraph() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("ot3a.installedCatalogReadOnly") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertTrue("Use the dedicated read-only runner", instrumentation is InstalledCatalogReadOnlyRunner)
        val context = instrumentation.targetContext
        assertEquals("dev.avillagran.ot3android", context.packageName)
        assertEquals(Application::class.java, context.applicationContext.javaClass)
        val report = inspectMetadata(context).put("applicationGraphStarted", false)
        // A bounded whitelist report only; no database copy, SDK state, URL or token.
        instrumentation.sendStatus(0, Bundle().apply { putString("ot3a.catalog.preflight", report.toString()) })
    }
    @Test fun opaqueFixtureCredentialsAndMessagesAreNeverReadOrChanged() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "catalog-probe-${System.nanoTime()}")
        assertTrue("Create only this test-owned fixture directory", root.mkdirs())
        try {
            val context = object : ContextWrapper(base) {
                override fun getDatabasePath(name: String) = File(root, name)
                override fun getNoBackupFilesDir() = File(root, "no-backup")
            }
            val path = context.getDatabasePath(Ot3Database.NAME)
            assertTrue(path.canonicalPath != base.getDatabasePath(Ot3Database.NAME).canonicalPath)
            SQLiteDatabase.openOrCreateDatabase(path, null).use { database ->
                database.execSQL("CREATE TABLE environments (id TEXT,label TEXT,environmentId TEXT,sortOrder INTEGER,baseUrl TEXT)")
                database.execSQL("CREATE TABLE bridge_managed (id TEXT,provenance TEXT)")
                database.execSQL("CREATE TABLE credentials (token TEXT)")
                database.execSQL("CREATE TABLE messages (body TEXT)")
                database.execSQL("INSERT INTO environments VALUES ('fixture','NODE fixture','fixture-uuid',0,'PRIVATE_CANARY_URL')")
                database.execSQL("INSERT INTO bridge_managed VALUES ('fixture','PRIVATE_CANARY_PROVENANCE')")
                database.execSQL("INSERT INTO credentials VALUES ('PRIVATE_CANARY_TOKEN')")
                database.execSQL("INSERT INTO messages VALUES ('PRIVATE_CANARY_MESSAGE')")
            }
            fun digest() = MessageDigest.getInstance("SHA-256").digest(path.readBytes()).toList()
            val before = digest()
            val report = inspectMetadata(context)
            assertEquals(before, digest())
            assertEquals(1, report.getInt("bridgeManagedCount"))
            assertEquals("NODE fixture", report.getJSONArray("environments").getJSONObject(0).getString("label"))
            assertTrue(report.getJSONArray("environments").getJSONObject(0).getBoolean("bridgeManaged"))
            assertTrue(!report.toString().contains("PRIVATE_CANARY"))
            assertTrue(!report.getBoolean("selectedBridgeOnDisk"))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun inspectMetadata(context: Context): JSONObject {
        val path = context.getDatabasePath(Ot3Database.NAME)
        val report = JSONObject().put("readOnly", true)
            .put("nativeSdkAvailable", NativeBridge.isAvailable())
            // Same file-existence boot guard used by AppGraph; never decode SDK state.
            .put("selectedBridgeOnDisk", context.noBackupFilesDir.resolve("omabridge/mobile-selected-bridge.json").exists())
            .put("databasePresent", path.isFile)
        if (path.isFile) {
            SQLiteDatabase.openDatabase(path.absolutePath, null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { database ->
                assertTrue(database.isReadOnly)
                val tables = mutableSetOf<String>()
                database.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name IN ('environments','bridge_managed')", null).use {
                    while (it.moveToNext()) tables += it.getString(0)
                }
                val managed = mutableSetOf<String>()
                if ("bridge_managed" in tables) database.rawQuery("SELECT id FROM bridge_managed", null).use {
                    while (it.moveToNext()) managed += it.getString(0)
                }
                val environments = JSONArray()
                if ("environments" in tables) database.rawQuery("SELECT id,label,environmentId FROM environments ORDER BY sortOrder", null).use {
                    while (it.moveToNext()) environments.put(JSONObject()
                        .put("label", it.getString(1))
                        .put("environmentId", if (it.isNull(2)) JSONObject.NULL else it.getString(2))
                        .put("bridgeManaged", it.getString(0) in managed))
                }
                report.put("environmentsTablePresent", "environments" in tables)
                    .put("bridgeManagedTablePresent", "bridge_managed" in tables)
                    .put("bridgeManagedCount", managed.size).put("environments", environments)
            }
        }
        return report
    }

}
