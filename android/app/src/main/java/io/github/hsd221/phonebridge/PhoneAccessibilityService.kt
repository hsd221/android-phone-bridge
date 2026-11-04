package io.github.hsd221.phonebridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Base64
import android.view.Display
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

class PhoneAccessibilityService : AccessibilityService() {
    companion object { @Volatile var instance: PhoneAccessibilityService? = null; private set }
    private val prefs by lazy { BridgePrefs(this) }
    private var uiEpoch = 0L
    private var windowEpoch = 0L
    private var snapshotEpoch = -1L
    private var snapshotWindow = -1
    private val paths = mutableMapOf<String, List<Int>>()
    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) invalidate()
        else if (event.windowId == snapshotWindow) { uiEpoch++; paths.clear(); snapshotEpoch = -1 }
    }
    override fun onInterrupt() { invalidate() }
    override fun onDestroy() { instance = null; invalidate(); super.onDestroy() }
    fun invalidate() { uiEpoch++; windowEpoch++; paths.clear(); snapshotEpoch = -1 }

    private fun authorizePackage(name: String, operation: Boolean, source: String? = null) {
        try {
            if (name == packageName) throw ToolFailure("APP_DENIED", text(R.string.err_app_local_only))
            prefs.policy().requireApp(name, operation)
        } catch (failure: ToolFailure) {
            if (source == null) throw failure
            throw ToolFailure(failure.code, text(R.string.err_error_with_source, failure.message, source), failure.outcomeUnknown)
        }
    }
    /** No event-package filtering: inspect every interactive window and fail closed. */
    fun authorizedRoot(tool: String, expectedPackage: String? = null): AccessibilityNodeInfo {
        val policy = prefs.policy()
        policy.requireTool(tool)
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) throw ToolFailure("DEVICE_LOCKED", text(R.string.err_device_locked))
        val operation = tool in BridgeTools.operations
        val root = rootInActiveWindow ?: throw ToolFailure("UI_UNAVAILABLE", text(R.string.err_ui_unavailable))
        val target = root.packageName?.toString() ?: throw ToolFailure("UI_UNAVAILABLE", text(R.string.err_foreground_app_unknown))
        if (expectedPackage != null && expectedPackage != target) throw ToolFailure("APP_CHANGED", text(R.string.err_app_mismatch))
        authorizePackage(target, operation, text(R.string.err_active_window))
        val visible = windows.filter { it.displayId == Display.DEFAULT_DISPLAY && Rect().also(it::getBoundsInScreen).let { bounds -> !bounds.isEmpty } }
        if (visible.isEmpty()) throw ToolFailure("UI_UNAVAILABLE", text(R.string.err_windows_unverifiable))
        for (window in visible) {
            val windowRoot = window.root ?: throw ToolFailure("WINDOW_UNAVAILABLE", text(R.string.err_window_unverifiable, target, window.type))
            val owner = windowRoot.packageName?.toString() ?: throw ToolFailure("WINDOW_UNAVAILABLE", text(R.string.err_window_owner_unknown))
            authorizePackage(owner, operation, text(R.string.err_additional_window_context, target, window.type, window.isActive, window.isFocused, windowRoot.isVisibleToUser))
        }
        return root
    }

    fun readUi(maxNodes: Int): JSONObject {
        val root = authorizedRoot("phone_ui")
        val epoch = uiEpoch
        val snapshot = UUID.randomUUID().toString()
        paths.clear()
        val nodes = JSONArray()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, List<Int>>>()
        queue.add(root to emptyList())
        var visited = 0
        while (queue.isNotEmpty() && nodes.length() < maxNodes && visited < 2000) {
            val (node, path) = queue.removeFirst()
            visited++
            // Hidden layout containers can still own visible descendants.
            for (index in 0 until node.childCount.coerceAtMost(500)) node.getChild(index)?.let { queue.add(it to (path + index)) }
            if (!node.isVisibleToUser) continue
            val owner = node.packageName?.toString() ?: throw ToolFailure("UI_UNAVAILABLE", text(R.string.err_control_owner_unknown))
            authorizePackage(owner, false, text(R.string.err_control_context, root.packageName ?: ""))
            val bounds = Rect().also(node::getBoundsInScreen)
            val id = "$snapshot:${nodes.length()}"
            paths[id] = path
            nodes.put(JSONObject().put("nodeId", id).put("packageName", owner)
                .put("className", node.className?.toString()).put("viewId", node.viewIdResourceName)
                .put("text", if (node.isPassword) "[redacted]" else node.text?.take(2000)?.toString())
                .put("description", if (node.isPassword) "[redacted]" else node.contentDescription?.take(2000)?.toString())
                .put("password", node.isPassword).put("clickable", node.isClickable).put("longClickable", node.isLongClickable).put("editable", node.isEditable)
                .put("enabled", node.isEnabled).put("focused", node.isFocused).put("scrollable", node.isScrollable)
                .put("bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom))))
        }
        if (epoch != uiEpoch) throw ToolFailure("UI_CHANGED", text(R.string.err_ui_changed))
        snapshotEpoch = epoch
        snapshotWindow = root.windowId
        return JSONObject().put("packageName", root.packageName).put("snapshotId", snapshot)
            .put("capturedAt", System.currentTimeMillis()).put("nodes", nodes).put("truncated", queue.isNotEmpty())
    }

    private fun resolveNode(root: AccessibilityNodeInfo, nodeId: String): AccessibilityNodeInfo {
        if (snapshotEpoch != uiEpoch || snapshotWindow != root.windowId) throw ToolFailure("STALE_NODE", text(R.string.err_controls_changed))
        val path = paths[nodeId] ?: throw ToolFailure("STALE_NODE", text(R.string.err_control_id_invalid))
        var node = root
        for (index in path) node = node.getChild(index) ?: throw ToolFailure("STALE_NODE", text(R.string.err_control_disappeared))
        if (!node.refresh() || !node.isVisibleToUser || !node.isEnabled || node.packageName != root.packageName) throw ToolFailure("STALE_NODE", text(R.string.err_control_unavailable))
        return node
    }

    suspend fun tap(args: JSONObject, ensure: () -> Unit): JSONObject {
        ensure()
        val root = authorizedRoot("phone_tap", args.getString("packageName"))
        val duration = args.optLong("durationMs", 50)
        if (args.has("nodeId")) {
            val node = resolveNode(root, args.getString("nodeId"))
            val longPress = duration >= 500
            if (longPress && !node.isLongClickable) throw ToolFailure("ACTION_UNAVAILABLE", text(R.string.err_control_not_long_clickable))
            if (!longPress && !node.isClickable) throw ToolFailure("ACTION_UNAVAILABLE", text(R.string.err_control_not_clickable))
            ensure()
            val accepted = node.performAction(if (longPress) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK)
            invalidate()
            if (!accepted) throw ToolFailure("ACTION_REJECTED", text(if (longPress) R.string.err_long_click_rejected else R.string.err_click_rejected))
            return JSONObject().put("accepted", true)
        }
        val x = args.getInt("x"); val y = args.getInt("y")
        checkPoint(root, x, y)
        return gesture(Path().apply { moveTo(x.toFloat(), y.toFloat()) }, duration, ensure)
    }
    suspend fun swipe(args: JSONObject, ensure: () -> Unit): JSONObject {
        ensure()
        val root = authorizedRoot("phone_swipe", args.getString("packageName"))
        val fromX = args.getInt("fromX"); val fromY = args.getInt("fromY")
        val toX = args.getInt("toX"); val toY = args.getInt("toY")
        checkPoint(root, fromX, fromY); checkPoint(root, toX, toY)
        return gesture(Path().apply { moveTo(fromX.toFloat(), fromY.toFloat()); lineTo(toX.toFloat(), toY.toFloat()) }, args.optLong("durationMs", 400), ensure)
    }
    private fun checkPoint(root: AccessibilityNodeInfo, x: Int, y: Int) {
        if (!Rect().also(root::getBoundsInScreen).contains(x, y)) throw ToolFailure("INVALID_ARGUMENT", text(R.string.err_coordinates_outside_window))
    }
    private suspend fun gesture(path: Path, duration: Long, ensure: () -> Unit): JSONObject = suspendCancellableCoroutine { continuation ->
        ensure()
        val accepted = dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { if (continuation.isActive) continuation.resume(JSONObject().put("accepted", true)) }
                override fun onCancelled(gestureDescription: GestureDescription?) { if (continuation.isActive) continuation.resumeWithException(ToolFailure("GESTURE_CANCELLED", text(R.string.err_gesture_cancelled), outcomeUnknown = true)) }
            }, null)
        invalidate()
        if (!accepted && continuation.isActive) continuation.resumeWithException(ToolFailure("ACTION_REJECTED", text(R.string.err_gesture_rejected)))
    }
    fun input(args: JSONObject, ensure: () -> Unit): JSONObject {
        ensure()
        val root = authorizedRoot("phone_input", args.getString("packageName"))
        val node = if (args.has("nodeId")) resolveNode(root, args.getString("nodeId")) else root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (node == null || !node.isEditable || node.packageName != root.packageName) throw ToolFailure("ACTION_UNAVAILABLE", text(R.string.err_editable_control_missing))
        if (node.isPassword) throw ToolFailure("ACTION_UNAVAILABLE", text(R.string.err_password_input_unsupported))
        ensure()
        val accepted = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, args.getString("text")) })
        invalidate()
        if (!accepted) throw ToolFailure("ACTION_REJECTED", text(R.string.err_text_input_rejected))
        return JSONObject().put("accepted", true)
    }
    fun back(args: JSONObject, ensure: () -> Unit): JSONObject {
        ensure(); authorizedRoot("phone_back", args.getString("packageName")); ensure()
        val accepted = performGlobalAction(GLOBAL_ACTION_BACK)
        invalidate()
        if (!accepted) throw ToolFailure("ACTION_REJECTED", text(R.string.err_back_rejected))
        return JSONObject().put("accepted", true)
    }
    fun key(args: JSONObject, ensure: () -> Unit): JSONObject {
        ensure()
        val root = authorizedRoot("phone_key", args.optString("packageName").takeIf { args.has("packageName") })
        val key = args.getString("key")
        val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val target = when (key) {
            "ENTER" -> focus?.takeIf { it.isEditable && !it.isPassword && it.isVisibleToUser && it.isEnabled && it.packageName == root.packageName }
            "TAB" -> (focus ?: root).focusSearch(View.FOCUS_FORWARD)?.takeIf { it.isVisibleToUser && it.isEnabled && it.packageName == root.packageName }
            else -> null
        }
        if (key in setOf("ENTER", "TAB") && target == null) throw ToolFailure("ACTION_UNAVAILABLE", text(R.string.err_key_unavailable, key))
        ensure()
        val accepted = when (key) {
            "HOME" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "BACK" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "ENTER" -> target!!.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            "TAB" -> target!!.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            else -> throw ToolFailure("INVALID_ARGUMENT", text(R.string.err_argument_invalid, "key"))
        }
        invalidate()
        if (!accepted) throw ToolFailure("ACTION_REJECTED", text(R.string.err_key_rejected, key))
        return JSONObject().put("accepted", true).put("key", key).put("path", "accessibility")
    }
    suspend fun screenshot(maxDimension: Int, ensure: () -> Unit): JSONObject {
        ensure(); authorizedRoot("phone_screenshot")
        val epoch = windowEpoch
        return suspendCancellableCoroutine { continuation ->
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onFailure(errorCode: Int) {
                    if (continuation.isActive) continuation.resumeWithException(ToolFailure("SCREENSHOT_UNAVAILABLE", text(R.string.err_screenshot_rejected, errorCode)))
                }
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val buffer = screenshot.hardwareBuffer
                    var bitmap: Bitmap? = null
                    try {
                        if (!continuation.isActive) return
                        ensure(); authorizedRoot("phone_screenshot")
                        if (epoch != windowEpoch) throw ToolFailure("UI_CHANGED", text(R.string.err_screenshot_window_changed))
                        val hardware = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace) ?: throw ToolFailure("SCREENSHOT_UNAVAILABLE", text(R.string.err_screenshot_unavailable))
                        bitmap = hardware.copy(Bitmap.Config.ARGB_8888, false)
                        hardware.recycle()
                        val source = bitmap ?: throw ToolFailure("SCREENSHOT_UNAVAILABLE", text(R.string.err_screenshot_conversion_failed))
                        val displayWidth = source.width
                        val displayHeight = source.height
                        val ratio = (maxDimension.toFloat() / maxOf(source.width, source.height)).coerceAtMost(1f)
                        if (ratio < 1f) {
                            bitmap = Bitmap.createScaledBitmap(source, (source.width * ratio).roundToInt().coerceAtLeast(1), (source.height * ratio).roundToInt().coerceAtLeast(1), true)
                            source.recycle()
                        }
                        val output = ByteArrayOutputStream()
                        bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, output)
                        if (output.size() > 6 * 1024 * 1024) throw ToolFailure("SCREENSHOT_TOO_LARGE", text(R.string.err_screenshot_too_large))
                        ensure()
                        continuation.resume(JSONObject().put("mimeType", "image/png").put("width", bitmap!!.width).put("height", bitmap!!.height)
                            .put("displayWidth", displayWidth).put("displayHeight", displayHeight)
                            .put("scaleX", bitmap!!.width.toDouble() / displayWidth).put("scaleY", bitmap!!.height.toDouble() / displayHeight)
                            .put("capturedAt", System.currentTimeMillis()).put("base64", Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)))
                    } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
                    finally { bitmap?.recycle(); buffer.close() }
                }
            })
        }
    }
}
