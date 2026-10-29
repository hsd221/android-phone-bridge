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
            if (name == packageName) throw ToolFailure("APP_DENIED", "手机工具桥设置仅允许用户在本机操作")
            prefs.policy().requireApp(name, operation)
        } catch (failure: ToolFailure) {
            if (source == null) throw failure
            throw ToolFailure(failure.code, "${failure.message}（$source）", failure.outcomeUnknown)
        }
    }
    /** No event-package filtering: inspect every interactive window and fail closed. */
    fun authorizedRoot(tool: String, expectedPackage: String? = null): AccessibilityNodeInfo {
        val policy = prefs.policy()
        policy.requireTool(tool)
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) throw ToolFailure("DEVICE_LOCKED", "设备已锁屏；可调用单独授权的 phone_unlock，或在手机上解锁")
        val operation = tool in setOf("phone_tap", "phone_swipe", "phone_input", "phone_back", "phone_open_app")
        val root = rootInActiveWindow ?: throw ToolFailure("UI_UNAVAILABLE", "当前应用未提供可读取的界面")
        val target = root.packageName?.toString() ?: throw ToolFailure("UI_UNAVAILABLE", "无法确认前台应用")
        if (expectedPackage != null && expectedPackage != target) throw ToolFailure("APP_CHANGED", "当前前台应用与请求不匹配")
        authorizePackage(target, operation, "活动窗口")
        val visible = windows.filter { it.displayId == Display.DEFAULT_DISPLAY && Rect().also(it::getBoundsInScreen).let { bounds -> !bounds.isEmpty } }
        if (visible.isEmpty()) throw ToolFailure("UI_UNAVAILABLE", "无法核对可见窗口")
        for (window in visible) {
            val windowRoot = window.root ?: throw ToolFailure("WINDOW_UNAVAILABLE", "活动应用 $target 存在无法核对的窗口，已拒绝访问（窗口类型 ${window.type}）")
            val owner = windowRoot.packageName?.toString() ?: throw ToolFailure("WINDOW_UNAVAILABLE", "无法确认窗口归属")
            authorizePackage(owner, operation, "活动应用 $target，附加窗口类型 ${window.type}，active=${window.isActive}，focused=${window.isFocused}，visible=${windowRoot.isVisibleToUser}")
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
            if (!node.isVisibleToUser) continue
            val owner = node.packageName?.toString() ?: throw ToolFailure("UI_UNAVAILABLE", "控件归属不明")
            authorizePackage(owner, false, "活动应用 ${root.packageName}，控件归属")
            val bounds = Rect().also(node::getBoundsInScreen)
            val id = "$snapshot:${nodes.length()}"
            paths[id] = path
            nodes.put(JSONObject().put("nodeId", id).put("packageName", owner)
                .put("className", node.className?.toString()).put("viewId", node.viewIdResourceName)
                .put("text", if (node.isPassword) "[redacted]" else node.text?.take(2000)?.toString())
                .put("description", if (node.isPassword) "[redacted]" else node.contentDescription?.take(2000)?.toString())
                .put("password", node.isPassword).put("clickable", node.isClickable).put("editable", node.isEditable)
                .put("enabled", node.isEnabled).put("focused", node.isFocused).put("scrollable", node.isScrollable)
                .put("bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom))))
            for (index in 0 until node.childCount.coerceAtMost(500)) node.getChild(index)?.let { queue.add(it to (path + index)) }
        }
        if (epoch != uiEpoch) throw ToolFailure("UI_CHANGED", "读取期间界面变化，请重新读取")
        snapshotEpoch = epoch
        snapshotWindow = root.windowId
        return JSONObject().put("packageName", root.packageName).put("snapshotId", snapshot)
            .put("capturedAt", System.currentTimeMillis()).put("nodes", nodes).put("truncated", queue.isNotEmpty())
    }

    private fun resolveNode(root: AccessibilityNodeInfo, nodeId: String): AccessibilityNodeInfo {
        if (snapshotEpoch != uiEpoch || snapshotWindow != root.windowId) throw ToolFailure("STALE_NODE", "界面已变化，请重新读取控件")
        val path = paths[nodeId] ?: throw ToolFailure("STALE_NODE", "控件 ID 已失效，请重新读取")
        var node = root
        for (index in path) node = node.getChild(index) ?: throw ToolFailure("STALE_NODE", "控件已消失")
        if (!node.refresh() || !node.isVisibleToUser || !node.isEnabled || node.packageName != root.packageName) throw ToolFailure("STALE_NODE", "控件不再可用")
        return node
    }

    suspend fun tap(args: JSONObject, ensure: () -> Unit): JSONObject {
        ensure()
        val root = authorizedRoot("phone_tap", args.getString("packageName"))
        if (args.has("nodeId")) {
            val node = resolveNode(root, args.getString("nodeId"))
            if (!node.isClickable) throw ToolFailure("ACTION_UNAVAILABLE", "此控件不可点击")
            ensure()
            val accepted = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            invalidate()
            if (!accepted) throw ToolFailure("ACTION_REJECTED", "Android 拒绝了点击")
            return JSONObject().put("accepted", true)
        }
        val x = args.getInt("x"); val y = args.getInt("y")
        checkPoint(root, x, y)
        return gesture(Path().apply { moveTo(x.toFloat(), y.toFloat()) }, 80, ensure)
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
        if (!Rect().also(root::getBoundsInScreen).contains(x, y)) throw ToolFailure("INVALID_ARGUMENT", "坐标不在当前应用窗口中")
    }
    private suspend fun gesture(path: Path, duration: Long, ensure: () -> Unit): JSONObject = suspendCancellableCoroutine { continuation ->
        ensure()
        val accepted = dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { if (continuation.isActive) continuation.resume(JSONObject().put("accepted", true)) }
                override fun onCancelled(gestureDescription: GestureDescription?) { if (continuation.isActive) continuation.resumeWithException(ToolFailure("GESTURE_CANCELLED", "手势被系统或用户输入打断", outcomeUnknown = true)) }
            }, null)
        invalidate()
        if (!accepted && continuation.isActive) continuation.resumeWithException(ToolFailure("ACTION_REJECTED", "Android 拒绝了手势"))
    }
    fun input(args: JSONObject, ensure: () -> Unit): JSONObject {
        ensure()
        val root = authorizedRoot("phone_input", args.getString("packageName"))
        val node = if (args.has("nodeId")) resolveNode(root, args.getString("nodeId")) else root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (node == null || !node.isEditable || node.packageName != root.packageName) throw ToolFailure("ACTION_UNAVAILABLE", "未找到当前应用的可编辑控件")
        if (node.isPassword) throw ToolFailure("ACTION_UNAVAILABLE", "首版不操作密码框")
        ensure()
        val accepted = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, args.getString("text")) })
        invalidate()
        if (!accepted) throw ToolFailure("ACTION_REJECTED", "Android 拒绝了文字输入")
        return JSONObject().put("accepted", true)
    }
    fun back(args: JSONObject, ensure: () -> Unit): JSONObject {
        ensure(); authorizedRoot("phone_back", args.getString("packageName")); ensure()
        val accepted = performGlobalAction(GLOBAL_ACTION_BACK)
        invalidate()
        if (!accepted) throw ToolFailure("ACTION_REJECTED", "Android 拒绝了返回操作")
        return JSONObject().put("accepted", true)
    }
    suspend fun screenshot(maxDimension: Int, ensure: () -> Unit): JSONObject {
        ensure(); authorizedRoot("phone_screenshot")
        val epoch = windowEpoch
        return suspendCancellableCoroutine { continuation ->
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onFailure(errorCode: Int) {
                    if (continuation.isActive) continuation.resumeWithException(ToolFailure("SCREENSHOT_UNAVAILABLE", "系统拒绝截图，错误码 $errorCode；安全窗口、锁屏或调用频率均可能影响"))
                }
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val buffer = screenshot.hardwareBuffer
                    var bitmap: Bitmap? = null
                    try {
                        if (!continuation.isActive) return
                        ensure(); authorizedRoot("phone_screenshot")
                        if (epoch != windowEpoch) throw ToolFailure("UI_CHANGED", "截图期间窗口变化，画面已丢弃")
                        val hardware = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace) ?: throw ToolFailure("SCREENSHOT_UNAVAILABLE", "无法读取系统截图")
                        bitmap = hardware.copy(Bitmap.Config.ARGB_8888, false)
                        hardware.recycle()
                        val source = bitmap ?: throw ToolFailure("SCREENSHOT_UNAVAILABLE", "无法转换截图")
                        val displayWidth = source.width
                        val displayHeight = source.height
                        val ratio = (maxDimension.toFloat() / maxOf(source.width, source.height)).coerceAtMost(1f)
                        if (ratio < 1f) {
                            bitmap = Bitmap.createScaledBitmap(source, (source.width * ratio).roundToInt().coerceAtLeast(1), (source.height * ratio).roundToInt().coerceAtLeast(1), true)
                            source.recycle()
                        }
                        val output = ByteArrayOutputStream()
                        bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, output)
                        if (output.size() > 6 * 1024 * 1024) throw ToolFailure("SCREENSHOT_TOO_LARGE", "请降低 maxDimension 后重新截图")
                        ensure()
                        continuation.resume(JSONObject().put("mimeType", "image/png").put("width", bitmap!!.width).put("height", bitmap!!.height)
                            .put("displayWidth", displayWidth).put("displayHeight", displayHeight)
                            .put("capturedAt", System.currentTimeMillis()).put("base64", Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)))
                    } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
                    finally { bitmap?.recycle(); buffer.close() }
                }
            })
        }
    }
}
