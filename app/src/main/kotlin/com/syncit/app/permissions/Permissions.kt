package com.syncit.app.permissions

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.syncit.media.MediaListenerService

/** Settings deep links and permission checks. No permission is requested without explanation first. */
object Permissions {

    /** Opens our own toggle on Android 11+, otherwise the notification-listener list. */
    fun openNotificationAccessSettings(context: Context) {
        val component = MediaListenerService.componentName(context)
        val detail = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
        } else {
            null
        }
        context.startFirst(detail, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /**
     * Opens the battery-optimization list, where the user picks Sync it → "Don't optimize".
     * We don't use ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS: it needs an extra permission
     * that Play restricts.
     */
    fun openBatteryOptimizationSettings(context: Context) {
        context.startFirst(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            appDetails(context),
        )
    }

    fun canPostNotifications(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun openOverlaySettings(context: Context) {
        context.startFirst(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri()),
            appDetails(context),
        )
    }

    private fun appDetails(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())

    private fun Context.startFirst(vararg intents: Intent?) {
        for (intent in intents.filterNotNull()) {
            try {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
                // Some OEM builds lack the more specific screen; try the next one.
            }
        }
    }
}
