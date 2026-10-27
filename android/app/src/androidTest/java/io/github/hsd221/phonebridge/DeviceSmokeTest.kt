package io.github.hsd221.phonebridge

import android.Manifest
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
        fail("Demo UI unavailable: $last"); throw IllegalStateException()
    }
    private fun node(ui: JSONObject, key: String, value: String): JSONObject {
        val nodes = ui.getJSONArray("nodes")
        return (0 until nodes.length()).map(nodes::getJSONObject).first { it.optString(key) == value }
    }
    private fun shell(command: String) { instrumentation.uiAutomation.executeShellCommand(command).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() } }

    @Test fun endToEnd() = runBlocking {
        assertNull("Smoke test refuses to replace an existing phone identity", prefs.identity)
        try {
            val identity = BridgeNetwork.pair(base, arguments.getString("pairCode")!!, prefs, true)
            prefs.identity = identity
            instrumentation.runOnMainSync {
                context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { BridgeService.start(context) }
            repeat(60) { if (!BridgeService.connected) delay(250) }
            assertTrue("Phone did not connect", BridgeService.connected)
            denied("CAPABILITY_DENIED", "phone_ui")
            prefs.setFlag("pausedAll", false)
            Capability.entries.forEach { prefs.setFlag("cap_${it.name}", true) }
            prefs.setApp(demo, AppAccess(read = true, operate = true))
            delay(200)
            assertTrue(ok("phone_status").getJSONObject("permissions").getBoolean("accessibility"))
            ok("phone_open_app", app())
            var ui = awaitUi()
            val input = node(ui, "description", "测试输入框")
            ok("phone_input", app().put("text", "bridge smoke").put("nodeId", input.getString("nodeId")))
            ui = awaitUi()
            val button = node(ui, "text", "确认测试")
            ok("phone_tap", app().put("nodeId", button.getString("nodeId")))
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
