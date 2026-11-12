package io.github.hsd221.phonebridge

import android.app.KeyguardManager
import android.app.UiAutomation
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.ConnectionPool
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Real MCP calls against an isolated, foreign-package accessibility fixture on an emulator. */
@RunWith(AndroidJUnit4::class)
class DesignImprovementsTest {
    @get:Rule val appUpdates = AppUpdateFixture()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val arguments = InstrumentationRegistry.getArguments()
    private val prefs = BridgePrefs(context)
    private val fixturePackage = instrumentation.context.packageName
    private val homePackage get() = context.packageManager.resolveActivity(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        ?.activityInfo?.packageName ?: error("Dedicated emulator has no resolvable HOME activity")
    private var requestId = 0
    // The harness must not reuse a pairing keep-alive that the phone WebSocket can replace.
    // Preserve retryOnConnectionFailure=false; no MCP request is replayed.
    private val harnessClient = BridgeNetwork.client.newBuilder().connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS)).build()
    private fun note(value: String) = instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "$value\n") })
    private fun shell(command: String) = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        .executeShellCommand(command).use { ParcelFileDescriptor.AutoCloseInputStream(it).readBytes().toString(Charsets.UTF_8) }
    private fun mcp(tool: String, args: JSONObject = JSONObject()): JSONObject {
        val request = JSONObject().put("jsonrpc", "2.0").put("id", ++requestId).put("method", "tools/call")
            .put("params", JSONObject().put("name", tool).put("arguments", args))
        return harnessClient.newCall(Request.Builder().url("${arguments.getString("bridgeUrl")!!}/mcp")
            .header("Authorization", "Bearer ${arguments.getString("harnessToken")!!}")
            .header("Accept", "application/json, text/event-stream")
            .post(request.toString().toRequestBody("application/json".toMediaType())).build()).execute().use {
            assertEquals(200, it.code)
            val envelope = JSONObject(it.body!!.string())
            assertFalse(envelope.toString(), envelope.has("error"))
            val content = envelope.getJSONObject("result").getJSONArray("content")
            val metadata = if (content.getJSONObject(0).getString("type") == "image") content.getJSONObject(1) else content.getJSONObject(0)
            JSONObject(metadata.getString("text"))
        }
    }
    private fun ok(tool: String, args: JSONObject = JSONObject()): JSONObject = mcp(tool, args).let {
        assertTrue(it.toString(), it.getBoolean("ok")); it.getJSONObject("data")
    }
    private fun denied(code: String, tool: String, args: JSONObject = JSONObject()) {
        val result = mcp(tool, args)
        assertFalse(result.toString(), result.getBoolean("ok"))
        assertEquals(result.toString(), code, result.getJSONObject("error").getString("code"))
        assertFalse(result.toString(), result.getJSONObject("error").optBoolean("outcomeUnknown"))
    }
    private fun eventually(description: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 15000
        while (SystemClock.elapsedRealtime() < end) {
            if (condition()) return
            Thread.sleep(100)
        }
        fail(description)
    }
    private fun nodes(ui: JSONObject) = ui.getJSONArray("nodes").let { array -> (0 until array.length()).map(array::getJSONObject) }
    private fun fixtureStatus(expected: String) {
        eventually("Fixture callback did not report $expected") {
            context.contentResolver.query(android.net.Uri.parse("content://io.github.hsd221.phonebridge.test.design-state/state"), null, null, null, null)
                ?.use { it.moveToFirst() && it.getString(0) == expected } == true
        }
    }
    private fun navigationReady(waitForImeDismissal: Boolean = false) {
        eventually("Navigation windows did not settle after the previous action") {
            if (waitForImeDismissal && PhoneAccessibilityService.instance!!.windows.any {
                    it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
                }) return@eventually false
            try {
                PhoneAccessibilityService.instance!!.authorizedRoot("phone_key")
                true
            } catch (failure: ToolFailure) {
                if (failure.code !in setOf("WINDOW_UNAVAILABLE", "UI_UNAVAILABLE")) throw failure
                false
            }
        }
    }
    private fun homeAfterImeDismissal() {
        repeat(10) {
            navigationReady(waitForImeDismissal = true)
            val result = mcp("phone_key", JSONObject().put("key", "HOME"))
            if (result.optBoolean("ok")) return
            val failure = result.getJSONObject("error")
            assertFalse(result.toString(), failure.optBoolean("outcomeUnknown"))
            assertTrue(result.toString(), failure.getString("code") in setOf("WINDOW_UNAVAILABLE", "UI_UNAVAILABLE"))
            // A disappearing system window refused this request before dispatch.
            // Observe fresh windows before issuing the still-unperformed HOME action.
            Thread.sleep(150)
        }
        fail("System windows never stabilized for HOME after IME dismissal")
    }
    private fun tabReady() {
        eventually("Fixture input focus and forward target did not settle") {
            val root = PhoneAccessibilityService.instance!!.rootInActiveWindow ?: return@eventually false
            val focus = root.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT) ?: return@eventually false
            if (focus.contentDescription?.toString() != "fixture first input") return@eventually false
            val next = focus.focusSearch(android.view.View.FOCUS_FORWARD) ?: return@eventually false
            val ready = next.contentDescription?.toString() == "fixture second input" && next.isVisibleToUser && next.isEnabled
            if (ready) note("Synthetic TAB focus bounds: first=${android.graphics.Rect().also(focus::getBoundsInScreen)}, next=${android.graphics.Rect().also(next::getBoundsInScreen)}")
            ready
        }
    }
    private fun ui(expected: String? = null): JSONObject {
        var last = JSONObject()
        try {
            eventually("Fixture did not expose expected UI: $expected") {
                last = mcp("phone_ui")
                last.optBoolean("ok") && last.getJSONObject("data").getString("packageName") == fixturePackage &&
                    (expected == null || nodes(last.getJSONObject("data")).any { it.optString("text") == expected })
            }
        } catch (failure: AssertionError) {
            if (last.optBoolean("ok") && last.getJSONObject("data").optString("packageName") == fixturePackage) {
                note("Synthetic fixture diagnostics: ${last.getJSONObject("data")}")
            }
            throw failure
        }
        return last.getJSONObject("data")
    }
    private fun tapNode(key: String, value: String, duration: Int = 50) {
        repeat(10) {
            val target = nodes(ui()).first { it.optString(key) == value }
            val result = mcp("phone_tap", JSONObject().put("packageName", fixturePackage)
                .put("nodeId", target.getString("nodeId")).put("durationMs", duration))
            if (result.optBoolean("ok")) return
            assertEquals(result.toString(), "STALE_NODE", result.getJSONObject("error").getString("code"))
            assertFalse(result.toString(), result.getJSONObject("error").optBoolean("outcomeUnknown"))
            Thread.sleep(150)
        }
        fail("Fixture node never stabilized for $value")
    }
    private fun openFixture() {
        context.startActivity(Intent().setClassName(fixturePackage, DesignControlsFixture::class.java.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ui()
    }

    private suspend fun lockScreenAndPolicy() {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        assertFalse("Lock regression refuses an emulator with a secure credential", keyguard.isDeviceSecure)
        assertFalse("Lock must work without locally configured unlock credentials", LocalUnlockStore(context).configured)
        assertFalse(prefs.unlockEnabled)
        val previousDisabled = shell("locksettings get-disabled").trim()
        assertTrue("Cannot safely restore the emulator lock-screen setting: $previousDisabled", previousDisabled in setOf("true", "false"))
        val previousTools = prefs.policy().tools
        val accessibilityComponent = "${context.packageName}/.PhoneAccessibilityService"
        fun wakeAndDismiss() {
            shell("input keyevent KEYCODE_WAKEUP")
            eventually("Dedicated emulator did not wake") { power.isInteractive }
            shell("wm dismiss-keyguard")
            eventually("Dedicated emulator did not dismiss its credential-free keyguard") { !keyguard.isKeyguardLocked }
        }
        fun assertUnlocked() {
            assertTrue("A refused lock request turned the screen off", power.isInteractive)
            assertFalse("A refused lock request locked the screen", keyguard.isKeyguardLocked)
        }
        fun settlePolicy() = instrumentation.waitForIdleSync()
        fun lockReady() {
            eventually("Authorized windows did not settle before lock") {
                try {
                    PhoneAccessibilityService.instance!!.authorizedRoot("phone_lock")
                    true
                } catch (failure: ToolFailure) {
                    if (failure.code !in setOf("WINDOW_UNAVAILABLE", "UI_UNAVAILABLE")) throw failure
                    false
                }
            }
        }
        try {
            shell("locksettings set-disabled false")
            wakeAndDismiss()
            openFixture()
            prefs.setPermissions(tools = previousTools - "phone_lock" - "phone_unlock")
            settlePolicy()
            denied("CAPABILITY_DENIED", "phone_lock")
            assertUnlocked()
            prefs.setPermissions(tools = (previousTools - "phone_unlock") + "phone_lock")
            settlePolicy()
            lockReady()
            denied("CAPABILITY_DENIED", "phone_unlock")
            for (args in listOf(JSONObject().put("key", "POWER"), JSONObject().put("packageName", fixturePackage),
                JSONObject().put("force", true))) {
                // Exercise the phone validator directly; the Bridge must not be the only validation boundary.
                // An unbound Service is deliberately sufficient: invalid input must stop before any Android service use.
                try {
                    PhoneTools(BridgeService(), prefs).execute("phone_lock", args) {}
                    fail("The phone accepted an extra lock parameter")
                } catch (failure: ToolFailure) {
                    assertEquals("INVALID_ARGUMENT", failure.code)
                }
            }
            assertUnlocked()
            prefs.setFlag("pausedOperations", true); settlePolicy()
            denied("OPERATIONS_PAUSED", "phone_lock")
            assertUnlocked()
            prefs.setFlag("pausedOperations", false)
            prefs.setFlag("pausedAll", true); settlePolicy()
            denied("PAUSED", "phone_lock")
            assertUnlocked()
            prefs.setFlag("pausedAll", false); settlePolicy()
            prefs.setApp(fixturePackage, AppAccess(read = true)); settlePolicy()
            denied("APP_DENIED", "phone_lock")
            assertUnlocked()
            prefs.setApp(fixturePackage, AppAccess(read = true, operate = true)); settlePolicy()
            eventually("A real system window is needed to verify the additional-window lock boundary") {
                PhoneAccessibilityService.instance!!.windows.any {
                    it.root?.packageName?.toString() == "com.android.systemui" &&
                        !android.graphics.Rect().also(it::getBoundsInScreen).isEmpty
                }
            }
            prefs.setApp("com.android.systemui", AppAccess(read = true)); settlePolicy()
            denied("APP_DENIED", "phone_lock")
            assertUnlocked()
            prefs.setApp("com.android.systemui", AppAccess(read = true, operate = true)); settlePolicy()
            ActivityScenario.launch(MainActivity::class.java).use {
                eventually("Bridge settings did not become the actual foreground") {
                    PhoneAccessibilityService.instance?.rootInActiveWindow?.packageName?.toString() == context.packageName
                }
                // Even a stale or forged app-level grant may not remotely control the bridge's own settings.
                prefs.setApp(context.packageName, AppAccess(read = true, operate = true)); settlePolicy()
                denied("APP_DENIED", "phone_lock")
                assertUnlocked()
            }
            prefs.setApp(context.packageName, AppAccess()); settlePolicy()
            openFixture()
            shell("settings delete secure enabled_accessibility_services")
            eventually("Accessibility service did not unbind") { PhoneAccessibilityService.instance == null }
            denied("ACCESSIBILITY_UNAVAILABLE", "phone_lock")
            assertUnlocked()
            shell("settings put secure enabled_accessibility_services $accessibilityComponent")
            shell("settings put secure accessibility_enabled 1")
            eventually("Accessibility service did not return after the negative lock test") { PhoneAccessibilityService.instance != null }
            openFixture()
            // Only phone_lock remains enabled; neither global navigation nor local unlock is needed.
            prefs.setPermissions(tools = setOf("phone_lock")); settlePolicy()
            lockReady()
            val accepted = ok("phone_lock")
            assertEquals(setOf("accepted", "alreadyLocked", "path"), accepted.keys().asSequence().toSet())
            assertTrue(accepted.getBoolean("accepted"))
            assertFalse(accepted.getBoolean("alreadyLocked"))
            assertEquals("accessibility", accepted.getString("path"))
            eventually("The accepted lock request did not actually lock and turn off the display") {
                keyguard.isKeyguardLocked && !power.isInteractive
            }
            val screen = ok("phone_status").getJSONObject("screen")
            assertTrue(screen.getBoolean("keyguardLocked"))
            assertFalse(screen.getBoolean("interactive"))
            val repeated = ok("phone_lock")
            assertEquals(setOf("accepted", "alreadyLocked", "path"), repeated.keys().asSequence().toSet())
            assertTrue(repeated.getBoolean("accepted"))
            assertTrue(repeated.getBoolean("alreadyLocked"))
            assertEquals("none", repeated.getString("path"))
            prefs.setPermissions(tools = emptySet()); settlePolicy()
            denied("CAPABILITY_DENIED", "phone_lock")
            prefs.setPermissions(tools = setOf("phone_lock"))
            prefs.setFlag("pausedOperations", true); settlePolicy()
            denied("OPERATIONS_PAUSED", "phone_lock")
            prefs.setFlag("pausedOperations", false)
            prefs.setFlag("pausedAll", true); settlePolicy()
            denied("PAUSED", "phone_lock")
            // Observe rather than resending: repeat/refused requests must not wake an already locked phone.
            repeat(10) {
                assertTrue(keyguard.isKeyguardLocked)
                assertFalse("An already-locked request woke the phone", power.isInteractive)
                Thread.sleep(100)
            }
            note("PASS: independent lock authorization, foreground and additional-window policy, pause/default refusal, real lock and screen-off, and no-wake repeated calls.")
        } finally {
            try {
                prefs.setFlag("pausedAll", false); prefs.setFlag("pausedOperations", false)
                prefs.setPermissions(tools = previousTools)
            } finally {
                try {
                    shell("settings put secure enabled_accessibility_services $accessibilityComponent")
                    shell("settings put secure accessibility_enabled 1")
                    eventually("Accessibility did not recover for subsequent tests") { PhoneAccessibilityService.instance != null }
                } finally {
                    try { wakeAndDismiss() }
                    finally { shell("locksettings set-disabled $previousDisabled") }
                }
            }
        }
    }

    @Test fun hiddenDescendantsLongPressNavigationScalingAndPolicy(): Unit = runBlocking {
        assumeTrue(arguments.getString("designImprovementsTest") == "true")
        assertTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        assertNull("Refuse to replace a paired phone identity", prefs.identity)
        assertNull(prefs.pendingRevocation)
        assertTrue(prefs.preferences.edit().clear().putBoolean("cap_OPERATE", true).commit())
        assertTrue(prefs.policy().tools.contains("phone_back"))
        assertFalse("Legacy operation authorization must not silently enable global keys", prefs.policy().tools.contains("phone_key"))
        assertFalse("Legacy operation authorization must not silently enable locking", prefs.policy().tools.contains("phone_lock"))
        assertTrue(prefs.preferences.edit().putBoolean("tool_permissions_configured", true).putBoolean("tool_phone_back", true).commit())
        assertFalse("Existing per-tool grants must not silently enable locking", prefs.policy().tools.contains("phone_lock"))
        prefs.identity = BridgeNetwork.pair(arguments.getString("bridgeUrl")!!, arguments.getString("pairCode")!!, prefs, true)
        var completed = false
        try {
            prefs.setTools(true)
            prefs.setFlag("pausedAll", false); prefs.setFlag("pausedOperations", false)
            prefs.shizukuEnabled = false
            for (name in listOf(fixturePackage, "com.android.systemui")) {
                prefs.setApp(name, AppAccess(read = true, operate = true))
            }
            prefs.setApp(homePackage, AppAccess(read = true, operate = true))
            android.content.ComponentName.unflattenFromString(android.provider.Settings.Secure.getString(
                context.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty())?.packageName?.let {
                prefs.setApp(it, AppAccess(read = true, operate = true))
            }
            ActivityScenario.launch(MainActivity::class.java).use {
                instrumentation.runOnMainSync { BridgeService.start(context) }
            }
            shell("settings delete secure enabled_accessibility_services")
            Thread.sleep(700)
            shell("settings put secure enabled_accessibility_services ${context.packageName}/.PhoneAccessibilityService")
            shell("settings put secure accessibility_enabled 1")
            eventually("Accessibility service did not bind") { PhoneAccessibilityService.instance != null }
            eventually("Bridge did not connect") { BridgeService.connected }
            openFixture()
            val initial = ui("Visible virtual descendant")
            assertFalse(nodes(initial).any { it.optString("text") == "Hidden virtual container" })
            val child = nodes(initial).first { it.optString("text") == "Visible virtual descendant" }
            assertTrue(child.getBoolean("longClickable"))
            tapNode("text", "Visible virtual descendant")
            ui("Virtual descendant clicked")
            tapNode("text", "Virtual descendant clicked", 700)
            ui("Virtual descendant long pressed")
            note("PASS: visible descendants of an invisible parent support node click and long click.")
            val button = nodes(ui()).first { it.optString("description") == "fixture coordinate control" }.getJSONArray("bounds")
            val point = JSONObject().put("packageName", fixturePackage)
                .put("x", (button.getInt(0) + button.getInt(2)) / 2).put("y", (button.getInt(1) + button.getInt(3)) / 2)
            note("Synthetic coordinate long press: x=${point.getInt("x")}, y=${point.getInt("y")}, duration=700ms")
            ok("phone_tap", JSONObject(point.toString()).put("durationMs", 700))
            fixtureStatus("coordinate long pressed")
            ok("phone_tap", point)
            fixtureStatus("coordinate clicked")
            note("PASS: coordinate duration distinguishes long press from ordinary click.")
            val screenshot = ok("phone_screenshot", JSONObject().put("maxDimension", 720))
            assertEquals(screenshot.getInt("width").toDouble() / screenshot.getInt("displayWidth"), screenshot.getDouble("scaleX"), 1e-12)
            assertEquals(screenshot.getInt("height").toDouble() / screenshot.getInt("displayHeight"), screenshot.getDouble("scaleY"), 1e-12)
            assertTrue(screenshot.getDouble("scaleX") in 0.0..1.0)
            assertFalse(screenshot.has("base64"))
            note("PASS: screenshot metadata maps scaled pixels to display pixels.")
            tapNode("description", "fixture first input")
            ok("phone_input", JSONObject().put("packageName", fixturePackage).put("text", "synthetic form input"))
            assertEquals("accessibility", ok("phone_key", JSONObject().put("key", "ENTER")).getString("path"))
            fixtureStatus("editor submitted")
            tapNode("description", "fixture first input")
            tabReady()
            ok("phone_key", JSONObject().put("key", "TAB"))
            eventually("TAB did not move input focus forward") {
                nodes(ui()).any { it.optString("description") == "fixture second input" && it.optBoolean("focused") }
            }
            denied("APP_CHANGED", "phone_key", JSONObject().put("key", "BACK").put("packageName", "com.example.wrong"))
            prefs.setApp(fixturePackage, AppAccess(read = true))
            denied("APP_DENIED", "phone_key", JSONObject().put("key", "BACK"))
            prefs.setApp(fixturePackage, AppAccess(read = true, operate = true))
            prefs.setFlag("pausedOperations", true)
            denied("OPERATIONS_PAUSED", "phone_key", JSONObject().put("key", "BACK"))
            prefs.setFlag("pausedOperations", false)
            ok("phone_key", JSONObject().put("key", "BACK"))
            homeAfterImeDismissal()
            eventually("HOME did not leave the fixture") {
                PhoneAccessibilityService.instance?.rootInActiveWindow?.packageName?.toString() == homePackage
            }
            openFixture()
            shell("am start -a android.settings.SETTINGS")
            eventually("Settings did not become active") {
                PhoneAccessibilityService.instance?.rootInActiveWindow?.packageName?.toString() == "com.android.settings"
            }
            prefs.setApp("com.android.settings", AppAccess(read = true))
            denied("APP_DENIED", "phone_key", JSONObject().put("key", "BACK"))
            prefs.setApp("com.android.settings", AppAccess(read = true, operate = true))
            denied("APP_CHANGED", "phone_back", JSONObject().put("packageName", fixturePackage))
            navigationReady()
            ok("phone_key", JSONObject().put("key", "BACK"))
            note("PASS: HOME/BACK/ENTER/TAB and app/pause boundaries remain enforced.")
            lockScreenAndPolicy()
            completed = true
        } finally {
            prefs.identity?.let { BridgeNetwork.revoke(it) }
            prefs.identity = null
            prefs.setFlag("pausedAll", true)
            if (completed) context.startActivity(Intent().setClassName(fixturePackage, DesignControlsFixture::class.java.name)
                .putExtra("close", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
