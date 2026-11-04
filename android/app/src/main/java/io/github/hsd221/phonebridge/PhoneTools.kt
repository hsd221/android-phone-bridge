package io.github.hsd221.phonebridge

import android.Manifest
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.BatteryManager
import android.os.CancellationSignal
import android.os.SystemClock
import android.provider.MediaStore
import android.view.WindowManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class PhoneTools(private val service: BridgeService, private val prefs: BridgePrefs) {
    private fun accessibility() = PhoneAccessibilityService.instance ?: throw ToolFailure("ACCESSIBILITY_UNAVAILABLE", text(R.string.err_accessibility_required))
    private fun granted(permission: String) = service.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    private val shizuku = ShizukuSupport(service)
    suspend fun execute(tool: String, args: JSONObject, ensure: () -> Unit): JSONObject {
        validate(tool, args)
        ensure(); prefs.policy().requireTool(tool)
        return when (tool) {
            "phone_status" -> status()
            "phone_device_info" -> deviceInfo()
            "phone_apps" -> apps(args.optBoolean("includeSystem", false))
            "phone_notifications" -> notifications(args.optInt("maxItems", 50))
            "phone_clipboard" -> clipboard()
            "phone_set_clipboard" -> setClipboard(args.getString("text"), ensure)
            "phone_media" -> media(args.optString("kind", "all"), args.optInt("limit", 50))
            "phone_unlock" -> LocalUnlocker(service, prefs).unlock(ensure)
            "phone_location" -> withTimeout(args.optLong("timeoutMs", 15000)) { location(ensure) }
            "phone_ui" -> accessibility().readUi(args.optInt("maxNodes", 300))
            "phone_screenshot" -> accessibility().screenshot(args.optInt("maxDimension", 1080), ensure)
            "phone_tap" -> tap(args, ensure)
            "phone_swipe" -> swipe(args, ensure)
            "phone_input" -> input(args, ensure)
            "phone_back" -> back(args, ensure)
            "phone_key" -> key(args, ensure)
            "phone_open_app" -> openApp(args.getString("packageName"), ensure)
            else -> throw ToolFailure("UNKNOWN_TOOL", text(R.string.err_unknown_tool))
        }
    }
    fun status(): JSONObject {
        val policy = prefs.policy()
        val apps = JSONObject()
        policy.apps.filterKeys { it != service.packageName }.forEach { (name, access) -> apps.put(name, JSONObject().put("read", access.read).put("operate", access.operate)) }
        return JSONObject().put("deviceId", prefs.deviceId).put("connected", BridgeService.connected)
            .put("appVersion", BuildConfig.VERSION_NAME).put("appVersionCode", BuildConfig.VERSION_CODE)
            .put("androidApi", Build.VERSION.SDK_INT).put("pausedAll", policy.pausedAll).put("pausedOperations", policy.pausedOperations)
            .put("tools", JSONObject(BridgeTools.all.associateWith { it in policy.tools }))
            .put("capabilities", JSONObject(Capability.entries.associate { it.name.lowercase() to (it in policy.capabilities) }))
            .put("permissions", JSONObject().put("accessibility", PhoneAccessibilityService.instance != null)
                .put("fineLocation", granted(Manifest.permission.ACCESS_FINE_LOCATION)).put("coarseLocation", granted(Manifest.permission.ACCESS_COARSE_LOCATION))
                .put("backgroundLocation", granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                .put("locationForegroundService", service.locationForeground).put("notifications", Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS))
                .put("notificationAccess", PhoneNotificationService.instance != null)
                .put("mediaImages", mediaPermission("images")).put("mediaVideos", mediaPermission("videos"))
                .put("adbInput", shizuku.status().optBoolean("available")))
            .put("apps", apps).put("shizuku", shizuku.status().put("enabledByUser", prefs.shizukuEnabled))
            .put("screen", LocalUnlocker.screenState(service))
            .put("unlock", JSONObject().put("enabledByUser", prefs.unlockEnabled)
                .put("credentialConfigured", LocalUnlockStore(service).configured)
                .put("retryBlocked", LocalUnlockStore(service).retryBlocked)
                .put("lastAttempt", UnlockDiagnostics.snapshot() ?: JSONObject.NULL))
    }

    private fun deviceInfo(): JSONObject {
        val battery = service.getSystemService(BatteryManager::class.java)
        val batteryIntent = service.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it >= 0 }
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val display = service.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        val configuration = service.resources.configuration
        return JSONObject().put("manufacturer", Build.MANUFACTURER).put("brand", Build.BRAND)
            .put("model", Build.MODEL).put("device", Build.DEVICE).put("product", Build.PRODUCT)
            .put("androidRelease", Build.VERSION.RELEASE).put("androidApi", Build.VERSION.SDK_INT)
            .put("securityPatch", if (Build.VERSION.SDK_INT >= 23) Build.VERSION.SECURITY_PATCH else JSONObject.NULL)
            .put("batteryPercent", level ?: JSONObject.NULL).put("charging", charging)
            .put("display", JSONObject().put("width", display.width()).put("height", display.height())
                .put("density", service.resources.displayMetrics.density).put("densityDpi", service.resources.displayMetrics.densityDpi))
            .put("locale", configuration.locales[0].toLanguageTag()).put("timeZone", java.util.TimeZone.getDefault().id)
            .put("capturedAt", System.currentTimeMillis())
    }

    private fun apps(includeSystem: Boolean): JSONObject {
        val pm = service.packageManager
        val result = JSONArray()
        @Suppress("DEPRECATION")
        val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        installed.asSequence().filter { includeSystem || (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 }
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }
            .forEach { app ->
                @Suppress("DEPRECATION")
                val packageInfo = runCatching { pm.getPackageInfo(app.packageName, 0) }.getOrNull()
                result.put(JSONObject().put("packageName", app.packageName).put("label", pm.getApplicationLabel(app).toString())
                    .put("enabled", app.enabled).put("system", (app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0)
                    .put("versionName", packageInfo?.versionName ?: JSONObject.NULL)
                    .put("versionCode", packageInfo?.longVersionCode ?: JSONObject.NULL)
                    .put("launchable", pm.getLaunchIntentForPackage(app.packageName) != null))
            }
        return JSONObject().put("apps", result).put("count", result.length()).put("includeSystem", includeSystem)
    }

    private fun notifications(maxItems: Int): JSONObject {
        val listener = PhoneNotificationService.instance ?: throw ToolFailure("NOTIFICATION_ACCESS_REQUIRED", text(R.string.err_notification_access_required))
        val allowed = prefs.policy().apps.filterValues { it.read }.keys
        return JSONObject().put("notifications", listener.snapshot(maxItems, allowed)).put("capturedAt", System.currentTimeMillis())
    }

    private fun clipboard(): JSONObject {
        return try {
            val manager = service.getSystemService(ClipboardManager::class.java)
            val clip = manager.primaryClip
            val value = clip?.getItemAt(0)?.coerceToText(service)?.toString()?.take(10000)
            JSONObject().put("hasText", value != null).put("text", value ?: JSONObject.NULL)
                .put("label", manager.primaryClipDescription?.label ?: JSONObject.NULL).put("capturedAt", System.currentTimeMillis())
        } catch (_: SecurityException) { throw ToolFailure("CLIPBOARD_UNAVAILABLE", text(R.string.err_clipboard_unavailable)) }
    }

    private fun setClipboard(value: String, ensure: () -> Unit): JSONObject {
        ensure()
        try { service.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("phone-bridge", value)) }
        catch (_: SecurityException) { throw ToolFailure("CLIPBOARD_UNAVAILABLE", text(R.string.err_clipboard_unavailable)) }
        return JSONObject().put("accepted", true).put("path", "android")
    }

    private fun media(kind: String, limit: Int): JSONObject {
        val kinds = when (kind) { "images" -> listOf("images"); "videos" -> listOf("videos"); else -> listOf("images", "videos") }
        kinds.forEach { if (!mediaPermission(it)) throw ToolFailure("MEDIA_PERMISSION_REQUIRED", text(R.string.err_media_permission_required, it)) }
        val result = JSONArray(); val resolver = service.contentResolver
        for (entry in kinds) {
            if (result.length() >= limit) break
            val collection = if (entry == "images") MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.RELATIVE_PATH)
            resolver.query(collection, projection, null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { cursor ->
                val id = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val name = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val mime = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val size = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val added = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val path = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                while (cursor.moveToNext() && result.length() < limit) {
                    val contentUri = ContentUris.withAppendedId(collection, cursor.getLong(id))
                    result.put(JSONObject().put("type", entry).put("uri", contentUri.toString()).put("name", cursor.getString(name))
                        .put("mimeType", cursor.getString(mime)).put("sizeBytes", cursor.getLong(size))
                        .put("dateAdded", cursor.getLong(added) * 1000).put("relativePath", cursor.getString(path) ?: JSONObject.NULL))
                }
            }
        }
        return JSONObject().put("items", result).put("count", result.length()).put("kind", kind)
    }

    private fun mediaPermission(kind: String): Boolean = if (kind == "images") {
        if (Build.VERSION.SDK_INT >= 33) granted(Manifest.permission.READ_MEDIA_IMAGES) else granted(Manifest.permission.READ_EXTERNAL_STORAGE)
    } else {
        if (Build.VERSION.SDK_INT >= 33) granted(Manifest.permission.READ_MEDIA_VIDEO) else granted(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private suspend fun tap(args: JSONObject, ensure: () -> Unit): JSONObject {
        val accessibility = PhoneAccessibilityService.instance
        if (args.has("nodeId")) return (accessibility ?: throw ToolFailure("ACCESSIBILITY_UNAVAILABLE", text(R.string.err_accessibility_required))).tap(args, ensure)
        authorizeAdbOrAccessibility(accessibility, "phone_tap", args.getString("packageName"))
        checkDisplayPoint(args.getInt("x"), args.getInt("y"))
        val x = args.getInt("x"); val y = args.getInt("y"); val duration = args.optInt("durationMs", 50)
        return shizukuInput(ensure) { if (duration == 50) it.tap(x, y) else it.swipe(x, y, x, y, duration) }
            ?: (accessibility ?: throw ToolFailure("ACCESSIBILITY_UNAVAILABLE", text(R.string.err_accessibility_required))).tap(args, ensure)
    }

    private suspend fun swipe(args: JSONObject, ensure: () -> Unit): JSONObject {
        val accessibility = PhoneAccessibilityService.instance
        authorizeAdbOrAccessibility(accessibility, "phone_swipe", args.getString("packageName"))
        checkDisplayPoint(args.getInt("fromX"), args.getInt("fromY")); checkDisplayPoint(args.getInt("toX"), args.getInt("toY"))
        return shizukuInput(ensure) { it.swipe(args.getInt("fromX"), args.getInt("fromY"), args.getInt("toX"), args.getInt("toY"), args.optInt("durationMs", 400)) }
            ?: (accessibility ?: throw ToolFailure("ACCESSIBILITY_UNAVAILABLE", text(R.string.err_accessibility_required))).swipe(args, ensure)
    }

    private suspend fun input(args: JSONObject, ensure: () -> Unit): JSONObject {
        val accessibility = PhoneAccessibilityService.instance
        if (args.has("nodeId")) return (accessibility ?: throw ToolFailure("ACCESSIBILITY_UNAVAILABLE", text(R.string.err_accessibility_required))).input(args, ensure)
        authorizeAdbOrAccessibility(accessibility, "phone_input", args.getString("packageName"))
        val textValue = args.getString("text")
        if (textValue.isEmpty() || !textValue.all { it in ' '..'~' }) return (accessibility ?: throw ToolFailure("ACCESSIBILITY_UNAVAILABLE", text(R.string.err_accessibility_required))).input(args, ensure)
        return shizukuInput(ensure) { it.inputText(args.getString("text")) }
            ?: (accessibility ?: throw ToolFailure("ACCESSIBILITY_UNAVAILABLE", text(R.string.err_accessibility_required))).input(args, ensure)
    }

    private suspend fun back(args: JSONObject, ensure: () -> Unit): JSONObject {
        val accessibility = PhoneAccessibilityService.instance
        authorizeAdbOrAccessibility(accessibility, "phone_back", args.getString("packageName"))
        return shizukuInput(ensure) { it.back() }
            ?: (accessibility ?: throw ToolFailure("ACCESSIBILITY_UNAVAILABLE", text(R.string.err_accessibility_required))).back(args, ensure)
    }

    private suspend fun key(args: JSONObject, ensure: () -> Unit): JSONObject {
        // Global keys must verify the actual foreground and every visible window,
        // including when no expected package was supplied or Shizuku is available.
        val accessibility = accessibility()
        val verify = {
            ensure()
            accessibility.authorizedRoot("phone_key", args.optString("packageName").takeIf { args.has("packageName") })
            Unit
        }
        verify()
        val key = args.getString("key")
        val result = shizukuInput(verify) { it.key(key) } ?: return accessibility.key(args, ensure)
        accessibility.invalidate()
        return result.put("key", key)
    }

    private suspend fun shizukuInput(ensure: () -> Unit, action: (IAdbTools) -> Int): JSONObject? =
        if (!prefs.shizukuEnabled) null else try { shizuku.input(action, ensure) } catch (failure: ToolFailure) {
            if (failure.code == "SHIZUKU_UNAVAILABLE") null else throw failure
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            // Only a pre-dispatch binding timeout can fall back. An expired or
            // cancelled parent request must never dispatch through another path.
            currentCoroutineContext().ensureActive()
            null
        }

    private fun authorizeAdbOrAccessibility(accessibility: PhoneAccessibilityService?, tool: String, packageName: String) {
        if (accessibility != null) accessibility.authorizedRoot(tool, packageName)
        else {
            if (packageName == service.packageName) throw ToolFailure("APP_DENIED", text(R.string.err_app_local_only))
            prefs.policy().requireApp(packageName, operation = true)
            if (service.getSystemService(KeyguardManager::class.java).isKeyguardLocked) throw ToolFailure("DEVICE_LOCKED", text(R.string.err_device_locked))
        }
    }

    private fun checkDisplayPoint(x: Int, y: Int) {
        val bounds = service.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        if (!bounds.contains(x, y)) throw ToolFailure("INVALID_ARGUMENT", text(R.string.err_coordinates_outside_window))
    }
    private suspend fun location(ensure: () -> Unit): JSONObject {
        if (!granted(Manifest.permission.ACCESS_COARSE_LOCATION) && !granted(Manifest.permission.ACCESS_FINE_LOCATION)) throw ToolFailure("PERMISSION_REQUIRED", text(R.string.err_location_permission_required))
        if (!service.locationForeground && !granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) throw ToolFailure("BACKGROUND_LOCATION_REQUIRED", text(R.string.err_background_location_required))
        val manager = service.getSystemService(LocationManager::class.java)
        if (!manager.isLocationEnabled) throw ToolFailure("LOCATION_DISABLED", text(R.string.err_location_disabled))
        val candidates = manager.getProviders(true).filter { it in setOf("fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER) }
            .filter { it != LocationManager.GPS_PROVIDER || granted(Manifest.permission.ACCESS_FINE_LOCATION) }
        if (candidates.isEmpty()) throw ToolFailure("LOCATION_UNAVAILABLE", text(R.string.err_location_unavailable))
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
                    } else if (--remaining == 0) continuation.resumeWithException(ToolFailure("LOCATION_UNAVAILABLE", text(R.string.err_location_timeout)))
                } } catch (_: SecurityException) {
                    if (--remaining == 0 && continuation.isActive) {
                        signals.forEach(CancellationSignal::cancel)
                        continuation.resumeWithException(ToolFailure("PERMISSION_REQUIRED", text(R.string.err_location_permission_revoked)))
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
        if (packageName == service.packageName) throw ToolFailure("APP_DENIED", text(R.string.err_bridge_app_local_only))
        prefs.policy().requireApp(packageName, operation = true)
        if (service.getSystemService(KeyguardManager::class.java).isKeyguardLocked) throw ToolFailure("DEVICE_LOCKED", text(R.string.err_device_locked))
        val launcher = service.packageManager.getLaunchIntentForPackage(packageName) ?: throw ToolFailure("APP_UNAVAILABLE", text(R.string.err_target_app_unavailable))
        if (prefs.shizukuEnabled && shizuku.status().optBoolean("available")) {
            try {
                return shizuku.launch(launcher.component ?: throw ToolFailure("APP_UNAVAILABLE", text(R.string.err_app_entry_invalid)), ensure)
            } catch (failure: ToolFailure) {
                if (failure.code != "SHIZUKU_UNAVAILABLE") throw failure
            }
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
            if (value !is Number || value.toDouble() % 1.0 != 0.0 || value.toLong() !in min.toLong()..max.toLong()) throw ToolFailure("INVALID_ARGUMENT", text(R.string.err_argument_invalid, key))
        }
        val keys = when (tool) {
            "phone_status", "phone_device_info", "phone_clipboard", "phone_unlock" -> emptySet()
            "phone_apps" -> setOf("includeSystem").also { if (args.has("includeSystem") && args.opt("includeSystem") !is Boolean) throw ToolFailure("INVALID_ARGUMENT", text(R.string.err_argument_invalid, "includeSystem")) }
            "phone_notifications" -> setOf("maxItems").also { range("maxItems", 1, 100) }
            "phone_set_clipboard" -> setOf("text").also { if (args.opt("text") !is String || args.getString("text").length > 10000) throw ToolFailure("INVALID_ARGUMENT", text(R.string.err_input_text_invalid)) }
            "phone_media" -> setOf("kind", "limit").also {
                if (args.has("kind") && args.optString("kind") !in setOf("images", "videos", "all")) throw ToolFailure("INVALID_ARGUMENT", text(R.string.err_argument_invalid, "kind"))
                range("limit", 1, 100)
            }
            "phone_location" -> setOf("timeoutMs").also { range("timeoutMs", 1000, 20000) }
            "phone_ui" -> setOf("maxNodes").also { range("maxNodes", 1, 500) }
            "phone_screenshot" -> setOf("maxDimension").also { range("maxDimension", 256, 1440) }
            "phone_open_app", "phone_back" -> setOf("packageName")
            "phone_key" -> setOf("key", "packageName").also {
                if (args.opt("key") !is String || args.getString("key") !in setOf("HOME", "BACK", "ENTER", "TAB")) throw ToolFailure("INVALID_ARGUMENT", text(R.string.err_argument_invalid, "key"))
            }
            "phone_tap" -> setOf("packageName", "nodeId", "x", "y", "durationMs").also {
                range("durationMs", 50, 2000)
                if (args.has("nodeId")) {
                    if (args.has("x") || args.has("y")) throw ToolFailure("INVALID_ARGUMENT", text(R.string.err_node_coordinate_conflict))
                } else { range("x", 0, 16384, true); range("y", 0, 16384, true) }
            }
            "phone_swipe" -> setOf("packageName", "fromX", "fromY", "toX", "toY", "durationMs").also {
                listOf("fromX", "fromY", "toX", "toY").forEach { range(it, 0, 16384, true) }; range("durationMs", 100, 2000)
            }
            "phone_input" -> setOf("packageName", "text", "nodeId").also {
                if (args.opt("text") !is String || args.getString("text").length > 10000) throw ToolFailure("INVALID_ARGUMENT", text(R.string.err_input_text_invalid))
            }
            else -> throw ToolFailure("UNKNOWN_TOOL", text(R.string.err_unknown_tool))
        }
        if (args.keys().asSequence().any { it !in keys }) throw ToolFailure("INVALID_ARGUMENT", text(R.string.err_unknown_parameter))
        if ("packageName" in keys && (tool != "phone_key" || args.has("packageName")) && (args.opt("packageName") !is String || !args.getString("packageName").matches(Regex("[a-zA-Z][\\w]*(\\.[\\w]+)+")) || args.getString("packageName").length > 255)) throw ToolFailure("INVALID_ARGUMENT", text(R.string.err_package_name_invalid))
        if (args.has("nodeId") && (args.opt("nodeId") !is String || args.getString("nodeId").length > 100)) throw ToolFailure("INVALID_ARGUMENT", text(R.string.err_node_id_invalid))
    }
}
