package io.github.hsd221.phonebridge

import android.app.UiAutomation
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.ConnectionPool
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.junit.Assert.*
import java.util.concurrent.TimeUnit

/** A fresh Bridge identity and synthetic data on an explicitly selected emulator only. */
internal class DataMcpSession {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val arguments = InstrumentationRegistry.getArguments()
    val prefs = BridgePrefs(context)
    // Keep the synthetic MCP harness separate from phone pairing/WebSocket sockets.
    // Zero idle sockets avoids stale pairing keep-alives without retrying a tool call.
    private val harnessClient = BridgeNetwork.client.newBuilder().connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS)).build()
    private var requestId = 0
    fun note(message: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$message\n") })
    fun shell(command: String): String = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        .executeShellCommand(command).use { ParcelFileDescriptor.AutoCloseInputStream(it).readBytes().toString(Charsets.UTF_8).trim() }
    fun eventually(description: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 15000
        while (SystemClock.elapsedRealtime() < end) {
            if (condition()) return
            Thread.sleep(100)
        }
        fail(description)
    }
    suspend fun start() {
        assertTrue("Dedicated emulator required", Build.HARDWARE in setOf("ranchu", "goldfish"))
        assertNull("Refusing to replace an existing paired identity", prefs.identity)
        assertNull(prefs.pendingRevocation)
        prefs.identity = BridgeNetwork.pair(arguments.getString("bridgeUrl")!!, arguments.getString("pairCode")!!, prefs, true)
        ActivityScenario.launch(MainActivity::class.java).use {
            instrumentation.runOnMainSync { BridgeService.start(context) }
        }
        eventually("Synthetic Bridge session did not connect") { BridgeService.connected }
    }
    suspend fun close() {
        try {
            prefs.identity?.let {
                harnessClient.newCall(Request.Builder().url("${it.url}/device/revoke")
                    .header("Authorization", "Bearer ${it.token}").delete().build()).execute().use { response ->
                    assertTrue("Synthetic identity revocation failed", response.code in setOf(200, 401))
                }
            }
        }
        finally { prefs.identity = null; prefs.setFlag("pausedAll", true) }
    }
    fun call(tool: String, args: JSONObject = JSONObject()): JSONObject {
        val request = JSONObject().put("jsonrpc", "2.0").put("id", ++requestId).put("method", "tools/call")
            .put("params", JSONObject().put("name", tool).put("arguments", args))
        return harnessClient.newCall(Request.Builder().url("${arguments.getString("bridgeUrl")!!}/mcp")
            .header("Authorization", "Bearer ${arguments.getString("harnessToken")!!}")
            .header("Accept", "application/json, text/event-stream")
            .post(request.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { response ->
            assertEquals(200, response.code)
            val envelope = JSONObject(response.body!!.string())
            assertFalse("MCP protocol error", envelope.has("error"))
            envelope.getJSONObject("result")
        }
    }
    fun metadata(result: JSONObject): JSONObject {
        val content = result.getJSONArray("content")
        val item = (0 until content.length()).map(content::getJSONObject).first { it.getString("type") == "text" }
        return JSONObject(item.getString("text"))
    }
    fun ok(tool: String, args: JSONObject = JSONObject()): JSONObject {
        val value = metadata(call(tool, args)); assertTrue(value.toString(), value.getBoolean("ok")); return value.getJSONObject("data")
    }
    fun denied(code: String, tool: String, args: JSONObject = JSONObject()) {
        val value = metadata(call(tool, args)); assertFalse(value.toString(), value.getBoolean("ok"))
        assertEquals(value.toString(), code, value.getJSONObject("error").getString("code"))
        assertFalse(value.getJSONObject("error").optBoolean("outcomeUnknown"))
    }
}
