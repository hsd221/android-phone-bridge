package io.github.hsd221.phonebridge

import android.Manifest
import android.app.UiAutomation
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Dedicated device/emulator only. Run using scripts/android-smoke.ts, never a personal phone implicitly. */
@RunWith(AndroidJUnit4::class)
class DeviceSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val arguments = InstrumentationRegistry.getArguments()
    private val base = arguments.getString("bridgeUrl")!!
    private val token = arguments.getString("harnessToken")!!
    private val prefs = BridgePrefs(context)
    private val demo = "io.github.hsd221.phonebridge.demo"
    private val requestId = AtomicInteger()
    private fun mcp(tool: String, args: JSONObject = JSONObject()): JSONObject {
        val request = JSONObject().put("jsonrpc", "2.0").put("id", requestId.incrementAndGet()).put("method", "tools/call")
            .put("params", JSONObject().put("name", tool).put("arguments", args))
        BridgeNetwork.client.newCall(Request.Builder().url("$base/mcp").header("Authorization", "Bearer $token")
            .header("Accept", "application/json, text/event-stream").post(request.toString().toRequestBody("application/json".toMediaType())).build()).execute().use {
            assertEquals(200, it.code)
            val envelope = JSONObject(it.body!!.string())
            assertFalse(envelope.toString(), envelope.has("error"))
            val result = envelope.getJSONObject("result")
            val content = result.getJSONArray("content").getJSONObject(0)
            if (content.getString("type") == "image") return JSONObject().put("ok", true).put("data", content)
            return JSONObject(content.getString("text"))
        }
    }
    private fun ok(tool: String, args: JSONObject = JSONObject()): JSONObject {
        val result = mcp(tool, args); assertTrue(result.toString(), result.getBoolean("ok")); return result.getJSONObject("data")
    }
    private fun denied(code: String, tool: String, args: JSONObject = JSONObject()) {
        val result = mcp(tool, args); assertFalse(result.toString(), result.getBoolean("ok")); assertEquals(code, result.getJSONObject("error").getString("code"))
    }
    private fun app() = JSONObject().put("packageName", demo)
    private fun awaitUi(): JSONObject {
        var last: JSONObject? = null
        repeat(30) {
            last = mcp("phone_ui")
            if (last!!.optBoolean("ok") && last!!.getJSONObject("data").getString("packageName") == demo) return last!!.getJSONObject("data")
            Thread.sleep(300)
        }
        val windows = PhoneAccessibilityService.instance?.windows?.joinToString { window ->
            val bounds = android.graphics.Rect().also(window::getBoundsInScreen)
            "${window.root?.packageName}:type=${window.type},layer=${window.layer},active=${window.isActive},focused=${window.isFocused},visible=${window.root?.isVisibleToUser},bounds=$bounds"
        }
        fail("Demo UI unavailable: $last; windows=$windows"); throw IllegalStateException()
    }
    private fun node(ui: JSONObject, key: String, value: String): JSONObject {
        val nodes = ui.getJSONArray("nodes")
        return (0 until nodes.length()).map(nodes::getJSONObject).first { it.optString(key) == value }
    }
    private fun actOnFreshNode(tool: String, key: String, value: String, args: JSONObject) {
        repeat(8) {
            val target = node(awaitUi(), key, value)
            val result = mcp(tool, JSONObject(args.toString()).put("nodeId", target.getString("nodeId")))
            if (result.optBoolean("ok")) return
            val error = result.getJSONObject("error")
            // Only a confirmed pre-dispatch stale-node refusal permits a newly observed action.
            assertEquals(result.toString(), "STALE_NODE", error.getString("code"))
            assertFalse(error.optBoolean("outcomeUnknown"))
            Thread.sleep(300)
        }
        fail("UI never stabilized for $tool")
    }
    private fun shell(command: String) { instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).executeShellCommand(command).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() } }
    private suspend fun authorizeShizuku() {
        val support = ShizukuSupport(context)
        assertTrue("Start non-root Shizuku before the enhanced smoke test", support.status().optBoolean("running"))
        if (!support.status().optBoolean("available")) {
            instrumentation.runOnMainSync { support.requestPermission() }
            repeat(40) {
                if (support.status().optBoolean("available")) return
                val root = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).rootInActiveWindow
                val allow = root?.findAccessibilityNodeInfosByText("Allow all the time")?.firstOrNull()
                if (allow != null) {
                    var button = allow
                    while (button != null && !button.isClickable) button = button.parent
                    button?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                }
                delay(200)
            }
        }
        assertTrue("Shizuku was not authorized as UID 2000", support.status().optBoolean("available"))
    }

    @Test fun endToEnd() = runBlocking {
        assertNull("Smoke test refuses to replace an existing phone identity", prefs.identity)
        try {
            val identity = BridgeNetwork.pair(base, arguments.getString("pairCode")!!, prefs, true)
            prefs.identity = identity
            instrumentation.runOnMainSync {
                context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            instrumentation.waitForIdleSync()
            // Instrumentation restarts the target process; rebind the test service after that restart.
            shell("settings delete secure enabled_accessibility_services")
            delay(700) // Let AccessibilityManager observe disable before enabling again.
            shell("settings put secure enabled_accessibility_services ${context.packageName}/.PhoneAccessibilityService")
            shell("settings put secure accessibility_enabled 1")
            repeat(40) { if (PhoneAccessibilityService.instance == null) delay(100) }
            instrumentation.runOnMainSync { BridgeService.start(context) }
            repeat(60) { if (!BridgeService.connected) delay(250) }
            assertTrue("Phone did not connect", BridgeService.connected)
            denied("CAPABILITY_DENIED", "phone_ui")
            prefs.setFlag("pausedAll", false)
            Capability.entries.forEach { prefs.setFlag("cap_${it.name}", true) }
            prefs.setApp(demo, AppAccess(read = true, operate = true))
            // API 35 exposes navigation and status bars as real, visible windows.
            // Explicitly authorize those test-system packages, rather than bypassing the policy.
            prefs.setApp("com.android.launcher3", AppAccess(read = true, operate = true))
            prefs.setApp("com.android.systemui", AppAccess(read = true, operate = true))
            delay(200)
            val initialStatus = ok("phone_status")
            assertTrue(initialStatus.toString(), initialStatus.getJSONObject("permissions").getBoolean("accessibility"))
            assertEquals("accessibility", ok("phone_open_app", app()).getString("path"))
            var ui = awaitUi()
            actOnFreshNode("phone_input", "description", "测试输入框", app().put("text", "bridge smoke"))
            ui = awaitUi()
            val button = node(ui, "text", "确认测试")
            actOnFreshNode("phone_tap", "text", "确认测试", app())
            ui = awaitUi()
            assertTrue(ui.toString().contains("已确认：bridge smoke"))
            val image = ok("phone_screenshot", JSONObject().put("maxDimension", 720))
            assertEquals("image/png", image.getString("mimeType"))
            assertTrue(image.getString("data").length > 100)
            val bounds = button.getJSONArray("bounds")
            ok("phone_tap", app().put("x", (bounds.getInt(0) + bounds.getInt(2)) / 2).put("y", (bounds.getInt(1) + bounds.getInt(3)) / 2))
            val display = context.resources.displayMetrics
            fun swipe() = app().put("fromX", display.widthPixels / 2).put("toX", display.widthPixels / 2)
                .put("fromY", display.heightPixels * 3 / 4).put("toY", display.heightPixels / 3).put("durationMs", 400)
            ok("phone_swipe", swipe())
            awaitUi()
            ok("phone_location", JSONObject().put("timeoutMs", 20000))
            denied("APP_DENIED", "phone_open_app", JSONObject().put("packageName", "com.android.settings"))
            shell("am start -a android.settings.SETTINGS")
            delay(1000)
            denied("APP_DENIED", "phone_ui")
            denied("APP_DENIED", "phone_screenshot")
            ok("phone_open_app", app()); awaitUi()
            prefs.setFlag("pausedOperations", true); delay(200)
            denied("OPERATIONS_PAUSED", "phone_back", app())
            ok("phone_ui")
            prefs.setFlag("pausedOperations", false); delay(200)
            val longSwipe = async(Dispatchers.IO) { mcp("phone_swipe", swipe().put("durationMs", 2000)) }
            delay(300)
            val queuedInput = async(Dispatchers.IO) { mcp("phone_input", app().put("text", "must never execute")) }
            delay(100); prefs.setFlag("pausedOperations", true)
            assertFalse(longSwipe.await().getBoolean("ok"))
            assertFalse(queuedInput.await().getBoolean("ok"))
            prefs.setFlag("pausedAll", true); delay(200)
            denied("PAUSED", "phone_ui")
            ok("phone_status")
            prefs.setFlag("pausedAll", false); prefs.setFlag("pausedOperations", false); delay(200)
            shell("input keyevent KEYCODE_HOME") // Activity is closed; service remains available.
            assertTrue(ok("phone_status").getBoolean("connected"))
            instrumentation.runOnMainSync { BridgeService.start(context, "reconnect") }
            delay(5000)
            assertTrue(ok("phone_status").getBoolean("connected"))
            ok("phone_open_app", app()); awaitUi()
            ok("phone_back", app())
            if (arguments.getString("shizuku") == "true") {
                authorizeShizuku()
                prefs.shizukuEnabled = true; delay(200)
                val enhanced = ok("phone_status").getJSONObject("shizuku")
                assertTrue(enhanced.getBoolean("available")); assertEquals(2000, enhanced.getInt("uid"))
                assertEquals("shizuku", ok("phone_open_app", app()).getString("path"))
                awaitUi()
                prefs.shizukuEnabled = false
            }
            assertTrue(BridgeNetwork.revoke(identity))
            prefs.identity = null
            delay(200)
        } finally {
            prefs.identity?.let { BridgeNetwork.revoke(it) }
            prefs.identity = null
            prefs.setFlag("pausedAll", true)
        }
    }
}
