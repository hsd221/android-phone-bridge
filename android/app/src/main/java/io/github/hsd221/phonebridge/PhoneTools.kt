package io.github.hsd221.phonebridge

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class PhoneTools(private val service: BridgeService, private val prefs: BridgePrefs) {
    private fun accessibility() = PhoneAccessibilityService.instance ?: throw ToolFailure("ACCESSIBILITY_UNAVAILABLE", "请在系统设置中启用手机工具桥无障碍服务")
    private fun granted(permission: String) = service.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    private val shizuku = ShizukuSupport(service)
    suspend fun execute(tool: String, args: JSONObject, ensure: () -> Unit): JSONObject {
        validate(tool, args)
        ensure(); prefs.policy().requireTool(tool)
        return when (tool) {
            "phone_status" -> status()
            "phone_unlock" -> LocalUnlocker(service, prefs).unlock(ensure)
            "phone_location" -> withTimeout(args.optLong("timeoutMs", 15000)) { location(ensure) }
            "phone_ui" -> accessibility().readUi(args.optInt("maxNodes", 300))
            "phone_screenshot" -> accessibility().screenshot(args.optInt("maxDimension", 1080), ensure)
            "phone_tap" -> accessibility().tap(args, ensure)
            "phone_swipe" -> accessibility().swipe(args, ensure)
            "phone_input" -> accessibility().input(args, ensure)
            "phone_back" -> accessibility().back(args, ensure)
            "phone_open_app" -> openApp(args.getString("packageName"), ensure)
            else -> throw ToolFailure("UNKNOWN_TOOL", "手机不支持此工具")
        }
    }
    fun status(): JSONObject {
        val policy = prefs.policy()
        val apps = JSONObject()
        policy.apps.filterKeys { it != service.packageName }.forEach { (name, access) -> apps.put(name, JSONObject().put("read", access.read).put("operate", access.operate)) }
        return JSONObject().put("deviceId", prefs.deviceId).put("connected", BridgeService.connected)
            .put("appVersion", BuildConfig.VERSION_NAME).put("appVersionCode", BuildConfig.VERSION_CODE)
            .put("androidApi", Build.VERSION.SDK_INT).put("pausedAll", policy.pausedAll).put("pausedOperations", policy.pausedOperations)
            .put("capabilities", JSONObject(Capability.entries.associate { it.name.lowercase() to (it in policy.capabilities) }))
            .put("permissions", JSONObject().put("accessibility", PhoneAccessibilityService.instance != null)
                .put("fineLocation", granted(Manifest.permission.ACCESS_FINE_LOCATION)).put("coarseLocation", granted(Manifest.permission.ACCESS_COARSE_LOCATION))
                .put("backgroundLocation", granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                .put("locationForegroundService", service.locationForeground).put("notifications", Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)))
            .put("apps", apps).put("shizuku", shizuku.status().put("enabledByUser", prefs.shizukuEnabled))
            .put("screen", LocalUnlocker.screenState(service))
            .put("unlock", JSONObject().put("enabledByUser", prefs.unlockEnabled)
                .put("credentialConfigured", LocalUnlockStore(service).configured)
                .put("retryBlocked", LocalUnlockStore(service).retryBlocked))
    }
    private suspend fun location(ensure: () -> Unit): JSONObject {
        if (!granted(Manifest.permission.ACCESS_COARSE_LOCATION) && !granted(Manifest.permission.ACCESS_FINE_LOCATION)) throw ToolFailure("PERMISSION_REQUIRED", "未授予系统定位权限")
        if (!service.locationForeground && !granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) throw ToolFailure("BACKGROUND_LOCATION_REQUIRED", "请打开 APK 启动定位前台服务，或在设置中允许始终定位")
        val manager = service.getSystemService(LocationManager::class.java)
        if (!manager.isLocationEnabled) throw ToolFailure("LOCATION_DISABLED", "系统定位开关已关闭")
        val candidates = manager.getProviders(true).filter { it in setOf("fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER) }
            .filter { it != LocationManager.GPS_PROVIDER || granted(Manifest.permission.ACCESS_FINE_LOCATION) }
        if (candidates.isEmpty()) throw ToolFailure("LOCATION_UNAVAILABLE", "没有可用的定位来源")
        val location = suspendCancellableCoroutine<Location> { continuation ->
            val signals = candidates.map { CancellationSignal() }
            var remaining = candidates.size
            continuation.invokeOnCancellation { signals.forEach(CancellationSignal::cancel) }
            for ((index, provider) in candidates.withIndex()) {
                try { manager.getCurrentLocation(provider, signals[index], service.mainExecutor) { value ->
                    if (!continuation.isActive) return@getCurrentLocation
                    if (value != null && SystemClock.elapsedRealtimeNanos() - value.elapsedRealtimeNanos <= 30_000_000_000L) {
                        signals.forEach(CancellationSignal::cancel)
                        continuation.resume(value)
                    } else if (--remaining == 0) continuation.resumeWithException(ToolFailure("LOCATION_UNAVAILABLE", "未能获得 30 秒内的当前位置"))
                } } catch (_: SecurityException) {
                    if (--remaining == 0 && continuation.isActive) {
                        signals.forEach(CancellationSignal::cancel)
                        continuation.resumeWithException(ToolFailure("PERMISSION_REQUIRED", "定位权限已被系统或用户撤回"))
                    }
                }
            }
        }
        ensure()
        return JSONObject().put("latitude", location.latitude).put("longitude", location.longitude)
            .put("accuracyMeters", location.accuracy).put("timestamp", location.time)
            .put("ageMs", (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000)
            .put("approximate", !granted(Manifest.permission.ACCESS_FINE_LOCATION))
            .put("mock", if (Build.VERSION.SDK_INT >= 31) location.isMock else @Suppress("DEPRECATION") location.isFromMockProvider)
    }
    private suspend fun openApp(packageName: String, ensure: () -> Unit): JSONObject {
        if (packageName == service.packageName) throw ToolFailure("APP_DENIED", "手机工具桥设置不能被远程打开或修改")
        prefs.policy().requireApp(packageName, operation = true)
        if (service.getSystemService(KeyguardManager::class.java).isKeyguardLocked) throw ToolFailure("DEVICE_LOCKED", "设备已锁屏；可调用单独授权的 phone_unlock，或在手机上解锁")
        val launcher = service.packageManager.getLaunchIntentForPackage(packageName) ?: throw ToolFailure("APP_UNAVAILABLE", "未安装此应用或没有可见启动入口")
        if (prefs.shizukuEnabled && shizuku.status().optBoolean("available")) {
            return shizuku.launch(launcher.component ?: throw ToolFailure("APP_UNAVAILABLE", "应用入口无效"), ensure)
        }
        val accessibility = accessibility()
        ensure()
        accessibility.startActivity(launcher.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        accessibility.invalidate()
        return JSONObject().put("accepted", true).put("path", "accessibility")
    }

    /** Independently validate on the phone, even when the Bridge already validated. */
    private fun validate(tool: String, args: JSONObject) {
        fun range(key: String, min: Int, max: Int, required: Boolean = false) {
            if (!args.has(key) && !required) return
            val value = args.opt(key)
            if (value !is Number || value.toDouble() % 1.0 != 0.0 || value.toLong() !in min.toLong()..max.toLong()) throw ToolFailure("INVALID_ARGUMENT", "$key 参数无效")
        }
        val keys = when (tool) {
            "phone_status", "phone_unlock" -> emptySet()
            "phone_location" -> setOf("timeoutMs").also { range("timeoutMs", 1000, 20000) }
            "phone_ui" -> setOf("maxNodes").also { range("maxNodes", 1, 500) }
            "phone_screenshot" -> setOf("maxDimension").also { range("maxDimension", 256, 1440) }
            "phone_open_app", "phone_back" -> setOf("packageName")
            "phone_tap" -> setOf("packageName", "nodeId", "x", "y").also {
                if (args.has("nodeId")) {
                    if (args.has("x") || args.has("y")) throw ToolFailure("INVALID_ARGUMENT", "控件和坐标不能同时提供")
                } else { range("x", 0, 16384, true); range("y", 0, 16384, true) }
            }
            "phone_swipe" -> setOf("packageName", "fromX", "fromY", "toX", "toY", "durationMs").also {
                listOf("fromX", "fromY", "toX", "toY").forEach { range(it, 0, 16384, true) }; range("durationMs", 100, 2000)
            }
            "phone_input" -> setOf("packageName", "text", "nodeId").also {
                if (args.opt("text") !is String || args.getString("text").length > 10000) throw ToolFailure("INVALID_ARGUMENT", "文字无效或过长")
            }
            else -> throw ToolFailure("UNKNOWN_TOOL", "手机不支持此工具")
        }
        if (args.keys().asSequence().any { it !in keys }) throw ToolFailure("INVALID_ARGUMENT", "存在未知参数")
        if ("packageName" in keys && (args.opt("packageName") !is String || !args.getString("packageName").matches(Regex("[a-zA-Z][\\w]*(\\.[\\w]+)+")) || args.getString("packageName").length > 255)) throw ToolFailure("INVALID_ARGUMENT", "应用包名无效")
        if (args.has("nodeId") && (args.opt("nodeId") !is String || args.getString("nodeId").length > 100)) throw ToolFailure("INVALID_ARGUMENT", "控件 ID 无效")
    }
}
