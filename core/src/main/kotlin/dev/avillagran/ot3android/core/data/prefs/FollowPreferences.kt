package dev.avillagran.ot3android.core.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.json.JSONObject

private val Context.followStore by preferencesDataStore("thread_follow")

/** Alert delivery is independent of the opt-in background activity service. */
enum class FollowNotificationMode(val wire: String, val banners: Boolean, val sound: Boolean) {
    Notifications("notifications", true, false),
    NotificationsAndSound("notifications-and-sound", true, true),
    Sound("sound", false, true),
    Off("off", false, false),
}

enum class FollowLockscreen { Hidden, State, Full }
enum class FollowAlert(val wire: String) {
    Approval("approval"), Input("input"), Plan("plan"), Completion("completion"), Failure("failure")
}
data class FollowSettings(
    val enabled: Boolean = false,
    val mode: FollowNotificationMode = FollowNotificationMode.NotificationsAndSound,
    val lockscreen: FollowLockscreen = FollowLockscreen.Full,
    val alerts: Map<FollowAlert, Boolean> = FollowAlert.entries.associateWith { true },
) {
    /** Only user preferences; Rust still decides transitions, suppression and grouping. */
    fun awarenessPreferences(): JSONObject = JSONObject().apply {
        FollowAlert.entries.forEach { put(it.wire, alerts[it] ?: true) }
    }
}
class FollowPreferences(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.followStore)
    val settings = store.data.map { prefs ->
        FollowSettings(
            enabled = prefs[ENABLED] ?: false,
            mode = FollowNotificationMode.entries.firstOrNull { it.wire == prefs[MODE] } ?: FollowNotificationMode.NotificationsAndSound,
            lockscreen = FollowLockscreen.entries.firstOrNull { it.name == prefs[LOCKSCREEN] } ?: FollowLockscreen.Full,
            alerts = FollowAlert.entries.associateWith { prefs[booleanPreferencesKey("alert_${it.wire}")] ?: true },
        )
    }.distinctUntilChanged()
    val enabled = settings.map { it.enabled }.distinctUntilChanged()
    suspend fun setEnabled(value: Boolean) { store.edit { it[ENABLED] = value } }
    suspend fun setMode(value: FollowNotificationMode) { store.edit { it[MODE] = value.wire } }
    suspend fun setLockscreen(value: FollowLockscreen) { store.edit { it[LOCKSCREEN] = value.name } }
    suspend fun setAlert(alert: FollowAlert, value: Boolean) { store.edit { it[booleanPreferencesKey("alert_${alert.wire}")] = value } }
    companion object {
        val MODE = stringPreferencesKey("notification_mode")
        val ENABLED = booleanPreferencesKey("enabled")
        val LOCKSCREEN = stringPreferencesKey("lockscreen")
    }
}
