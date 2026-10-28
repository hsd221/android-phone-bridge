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
        val channelEnabled = notifications.getNotificationChannel("bridge")?.importance != NotificationManager.IMPORTANCE_NONE
        val batteryExempt = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
        val backgroundRestricted = context.getSystemService(ActivityManager::class.java).isBackgroundRestricted
        val shizuku = ShizukuSupport(context).status()
        return mapOf(
            "accessibility" to PermissionState(when {
                PhoneAccessibilityService.instance != null -> "已允许，服务已连接"
                enabledService -> "已允许，服务未连接"
                else -> "未允许"
            }, enabledService && PhoneAccessibilityService.instance != null),
            "location" to PermissionState(when {
                fine -> "已允许精确定位"
                coarse -> "已允许大概定位，未允许精确定位"
                else -> "未允许"
            }, fine || coarse),
            "backgroundLocation" to PermissionState(when {
                background && (fine || coarse) -> "已允许（始终允许）"
                fine || coarse -> "未允许（仅使用期间或本次允许）"
                else -> "未允许，请先授予定位权限"
            }, background && (fine || coarse)),
            "locationService" to PermissionState(if (locationEnabled) "已开启" else "已关闭", locationEnabled),
            "notifications" to PermissionState(when {
                !notificationsEnabled -> "未允许"
                !channelEnabled -> "应用已允许，连接通知类别已关闭"
                else -> "已允许"
            }, notificationsEnabled && channelEnabled),
            "battery" to PermissionState(if (batteryExempt) "已豁免电池优化" else "未豁免，仍受电池优化", batteryExempt),
            "background" to PermissionState(if (backgroundRestricted) "受系统限制" else "未受系统限制", !backgroundRestricted),
            "autostart" to PermissionState("需手动确认（厂商未提供统一查询接口）"),
            "shizuku" to PermissionState(when {
                !shizuku.optBoolean("running") -> "服务未启动"
                !shizuku.optBoolean("permissionGranted") -> "服务已启动，未允许本应用使用"
                !shizuku.optBoolean("available") -> "已授权，当前模式不支持；需非 Root 模式"
                else -> "已授权，可用（非 Root）"
            }, shizuku.optBoolean("available")),
        )
    }
}
