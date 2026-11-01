package io.github.hsd221.phonebridge

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.PowerManager
import android.view.accessibility.AccessibilityManager

data class PermissionState(val description: String, val allowed: Boolean? = null)

/** Read Android's current state; internal bridge capability switches are independent of these grants. */
object SystemPermissions {
    fun read(context: Context): Map<String, PermissionState> {
        fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        val enabledService = context.getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
                val service = it.resolveInfo.serviceInfo
                ComponentName(service.packageName, service.name) == ComponentName(context, PhoneAccessibilityService::class.java)
            }
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        val background = granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        val locationEnabled = context.getSystemService(LocationManager::class.java).isLocationEnabled
        val notifications = context.getSystemService(NotificationManager::class.java)
        val notificationPermission = Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)
        val notificationsEnabled = notificationPermission && notifications.areNotificationsEnabled()
        val channelEnabled = notifications.getNotificationChannel(BridgeNotificationChannels.TOOL_CALLS)?.importance != NotificationManager.IMPORTANCE_NONE
        val batteryExempt = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
        val backgroundRestricted = context.getSystemService(ActivityManager::class.java).isBackgroundRestricted
        val shizuku = ShizukuSupport(context).status()
        return mapOf(
            "accessibility" to PermissionState(when {
                PhoneAccessibilityService.instance != null -> context.getString(R.string.permission_allowed_connected)
                enabledService -> context.getString(R.string.permission_allowed_disconnected)
                else -> context.getString(R.string.permission_denied)
            }, enabledService && PhoneAccessibilityService.instance != null),
            "location" to PermissionState(when {
                fine -> context.getString(R.string.location_precise)
                coarse -> context.getString(R.string.location_approximate)
                else -> context.getString(R.string.permission_denied)
            }, fine || coarse),
            "backgroundLocation" to PermissionState(when {
                background && (fine || coarse) -> context.getString(R.string.background_allowed)
                fine || coarse -> context.getString(R.string.background_limited)
                else -> context.getString(R.string.background_requires_location)
            }, background && (fine || coarse)),
            "locationService" to PermissionState(if (locationEnabled) context.getString(R.string.enabled) else context.getString(R.string.disabled), locationEnabled),
            "notifications" to PermissionState(when {
                !notificationsEnabled -> context.getString(R.string.permission_denied)
                !channelEnabled -> context.getString(R.string.notification_channel_disabled)
                else -> context.getString(R.string.permission_allowed)
            }, notificationsEnabled && channelEnabled),
            "battery" to PermissionState(if (batteryExempt) context.getString(R.string.battery_exempt) else context.getString(R.string.battery_not_exempt), batteryExempt),
            "background" to PermissionState(if (backgroundRestricted) context.getString(R.string.system_restricted) else context.getString(R.string.system_unrestricted), !backgroundRestricted),
            "autostart" to PermissionState(context.getString(R.string.autostart_manual)),
            "shizuku" to PermissionState(when {
                !shizuku.optBoolean("running") -> context.getString(R.string.service_not_started)
                !shizuku.optBoolean("permissionGranted") -> context.getString(R.string.service_not_allowed)
                !shizuku.optBoolean("available") -> context.getString(R.string.shizuku_unsupported)
                else -> context.getString(R.string.shizuku_available)
            }, shizuku.optBoolean("available")),
            "adb" to PermissionState(when {
                shizuku.optBoolean("available") -> context.getString(R.string.adb_available)
                else -> context.getString(R.string.adb_unavailable)
            }, shizuku.optBoolean("available")),
            "notificationAccess" to PermissionState(
                if (PhoneNotificationService.instance != null) context.getString(R.string.notification_access_available)
                else context.getString(R.string.notification_access_unavailable), PhoneNotificationService.instance != null),
            "mediaImages" to PermissionState(
                if (if (Build.VERSION.SDK_INT >= 33) granted(Manifest.permission.READ_MEDIA_IMAGES) else granted(Manifest.permission.READ_EXTERNAL_STORAGE)) context.getString(R.string.permission_allowed)
                else context.getString(R.string.media_permission_unavailable), if (Build.VERSION.SDK_INT >= 33) granted(Manifest.permission.READ_MEDIA_IMAGES) else granted(Manifest.permission.READ_EXTERNAL_STORAGE)),
            "mediaVideos" to PermissionState(
                if (if (Build.VERSION.SDK_INT >= 33) granted(Manifest.permission.READ_MEDIA_VIDEO) else granted(Manifest.permission.READ_EXTERNAL_STORAGE)) context.getString(R.string.permission_allowed)
                else context.getString(R.string.media_permission_unavailable), if (Build.VERSION.SDK_INT >= 33) granted(Manifest.permission.READ_MEDIA_VIDEO) else granted(Manifest.permission.READ_EXTERNAL_STORAGE)),
        )
    }
}
