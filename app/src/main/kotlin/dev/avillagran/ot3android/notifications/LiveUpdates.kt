package dev.avillagran.ot3android.notifications

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/**
 * What the app can say about Live Updates without lying. Requesting promotion is not being
 * promoted: the system decides per notification ([Notification.FLAG_PROMOTED_ONGOING]) and
 * the user can turn promotion off per app, demote a card, or use a build that does not
 * implement it. Settings show [support]; only a posted notification shows [isPromoted].
 */
object LiveUpdates {
    /** Android 16 QPR2, API 36.1 (`Build.VERSION_CODES_FULL.BAKLAVA_1`, absent from compileSdk 36). */
    const val SDK_INT_FULL_36_1 = 3_600_001

    enum class Support {
        /** Below API 36.1: a normal ongoing card, no chip. */
        Unsupported,

        /** API 36.1+, but the user turned Live Updates off for ot3. */
        NotAllowed,

        /** The app may post promoted cards; a card may still be refused or demoted. */
        Allowed,
    }

    fun support(context: Context): Support {
        if (Build.VERSION.SDK_INT < 36 || Build.VERSION.SDK_INT_FULL < SDK_INT_FULL_36_1) return Support.Unsupported
        return if (NotificationManagerCompat.from(context).canPostPromotedNotifications()) Support.Allowed else Support.NotAllowed
    }

    /** True only when the system actually promoted this posted notification. */
    fun isPromoted(notification: Notification): Boolean =
        Build.VERSION.SDK_INT >= 36 && notification.flags and Notification.FLAG_PROMOTED_ONGOING != 0

    /**
     * The app's Live Update settings on API 36+ (`Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS`,
     * the constant in the SDK; the live-update guide names a different one that the SDK lacks),
     * else the app's notification settings (API 26+), else its details page.
     */
    fun settingsIntent(context: Context): Intent = when {
        Build.VERSION.SDK_INT >= 36 -> Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    }
}
