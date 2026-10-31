package io.github.hsd221.phonebridge

import android.app.KeyguardManager
import android.app.UiAutomation
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ConnectionPool
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit

/** Explicitly opt-in, dedicated emulator only; never run on a personal phone. */
@RunWith(AndroidJUnit4::class)
class UnlockTest {
    @get:Rule val appUpdates = AppUpdateFixture()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val arguments = InstrumentationRegistry.getArguments()
    private val prefs = BridgePrefs(context)
    private val store = LocalUnlockStore(context)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val requestId = AtomicInteger()
    // The control harness has its own sockets; phone reconnects and a stale pairing
    // keep-alive must not close/reuse a harness request or cause action replay.
    private val harnessClient = BridgeNetwork.client.newBuilder().connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS)).build()
    private val secrets = mutableListOf<String>()
    private var activeCredential: UnlockCredential? = null
    private val demo = "io.github.hsd221.phonebridge.demo"
    private val accessibilityComponent get() = "${context.packageName}/.PhoneAccessibilityService"
    private val overlayComponent get() = "${instrumentation.context.packageName}/${UnlockOverlayFixture::class.java.name}"

    private fun note(value: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$value\n") })

    private fun shell(command: String): String = instrumentation
        .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        .executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes().toString(Charsets.UTF_8).trim()
        }

    private suspend fun eventually(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            delay(100)
        }
        fail(description)
    }

    private fun mcp(tool: String, args: JSONObject = JSONObject()): JSONObject {
        val request = JSONObject().put("jsonrpc", "2.0").put("id", requestId.incrementAndGet()).put("method", "tools/call")
            .put("params", JSONObject().put("name", tool).put("arguments", args))
        // Assert before network serialization; real synthetic lock secrets must stay local.
        assertFalse("Tool arguments contained a local lock credential", secrets.any { request.toString().contains(it) })
        val base = arguments.getString("bridgeUrl")!!
        val token = arguments.getString("harnessToken")!!
        harnessClient.newCall(Request.Builder().url("$base/mcp").header("Authorization", "Bearer $token")
            .header("Accept", "application/json, text/event-stream")
            .post(request.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { response ->
            assertEquals(200, response.code)
            val raw = response.body!!.string()
            assertFalse("Bridge response contained a local lock credential", secrets.any { raw.contains(it) })
            val envelope = JSONObject(raw)
            assertFalse("MCP returned a protocol error", envelope.has("error"))
            return JSONObject(envelope.getJSONObject("result").getJSONArray("content").getJSONObject(0).getString("text"))
        }
    }

    private fun ok(tool: String, args: JSONObject = JSONObject()): JSONObject {
        val result = mcp(tool, args)
        assertTrue(result.toString(), result.getBoolean("ok"))
        return result.getJSONObject("data")
    }

    private suspend fun unlockOnce(): JSONObject = coroutineScope {
        val topology = mutableListOf<String>()
        val pending = async(Dispatchers.IO) { mcp("phone_unlock") }
        while (!pending.isCompleted) {
            val sample = withContext(Dispatchers.Main) {
                val service = PhoneAccessibilityService.instance
                val root = service?.rootInActiveWindow
                val entries = listOf("pinEntry", "passwordEntry", "lockPatternView").joinToString { id ->
                    val matches = root?.findAccessibilityNodeInfosByViewId("com.android.systemui:id/$id").orEmpty()
                    "$id=${matches.size}/${matches.count { it.isVisibleToUser }}"
                }
                "dismiss=${UnlockActivity.dismissStatus}, locked=${keyguard.isKeyguardLocked}, interactive=${power.isInteractive}, root=${root?.packageName}, $entries; " +
                    service?.windows?.joinToString { window ->
                        val windowRoot = window.root
                        val bouncers = listOf("pinEntry", "passwordEntry", "lockPatternView").joinToString { id ->
                            "$id=${windowRoot?.findAccessibilityNodeInfosByViewId("com.android.systemui:id/$id")?.count { it.isVisibleToUser } ?: 0}"
                        }
                        "${windowRoot?.packageName}:type=${window.type},layer=${window.layer},active=${window.isActive},focused=${window.isFocused},bounds=${Rect().also(window::getBoundsInScreen)}, $bouncers"
                    }
            }
            if (topology.lastOrNull() != sample) topology.add(sample)
            delay(100)
        }
        val result = pending.await()
        if (!result.getBoolean("ok")) note("Redacted unlock topology: " + topology.joinToString("\n"))
        assertTrue(result.toString(), result.getBoolean("ok"))
        result.getJSONObject("data")
    }

    private fun denied(code: String, tool: String, args: JSONObject = JSONObject()): JSONObject {
        val result = mcp(tool, args)
        assertFalse(result.toString(), result.getBoolean("ok"))
        assertEquals(result.toString(), code, result.getJSONObject("error").getString("code"))
        return result.getJSONObject("error")
    }

    private fun setSystemCredential(credential: UnlockCredential) {
        val command = when (credential.kind) {
            UnlockKind.PIN -> "set-pin"
            UnlockKind.PASSWORD -> "set-password"
            UnlockKind.PATTERN -> "set-pattern"
        }
        // Generated test credentials contain only ASCII letters and digits. No shell
        // interpolation or bridge transport is involved in configuring the test device.
        check(credential.value.matches(Regex("[A-Za-z0-9]+")))
        val old = activeCredential?.let { " --old ${it.value}" }.orEmpty()
        val output = shell("cmd lock_settings $command$old ${credential.value}")
        assertTrue("Unable to set synthetic test credential", output.contains("set to"))
        activeCredential = credential
    }

    private fun clearSystemCredential() {
        activeCredential?.let { credential ->
            assertTrue("Unable to remove synthetic test credential", shell("cmd lock_settings clear --old ${credential.value}").contains("cleared"))
            activeCredential = null
        }
    }

    private suspend fun sleepAndLock() {
        shell("input keyevent KEYCODE_SLEEP")
        eventually("Test device did not lock with the screen off") { keyguard.isKeyguardLocked && !power.isInteractive }
    }

    private suspend fun recoverLocally(credential: UnlockCredential) {
        assertEquals(UnlockKind.PIN, credential.kind)
        shell("input keyevent KEYCODE_SLEEP")
        delay(300)
        shell("input keyevent KEYCODE_WAKEUP")
        delay(300)
        shell("wm dismiss-keyguard")
        delay(500)
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        fun keyNode(id: String): AccessibilityNodeInfo? = automation.windows.asSequence().mapNotNull { it.root }
            .filter { it.packageName?.toString() == "com.android.systemui" }
            .flatMap { it.findAccessibilityNodeInfosByViewId("com.android.systemui:id/$id").asSequence() }
            .firstOrNull { it.isVisibleToUser && it.isEnabled }
        eventually("Synthetic local recovery did not expose a PIN keypad") { keyNode("key_enter") != null }
        for (digit in credential.value) {
            assertTrue("Synthetic local recovery click was rejected", keyNode("key$digit")?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
            delay(80)
        }
        assertTrue("Synthetic local recovery confirmation was rejected", keyNode("key_enter")?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        eventually("Synthetic local PIN recovery did not unlock") { !keyguard.isKeyguardLocked }
    }

    private fun assertCiphertext(credential: UnlockCredential) {
        val values = context.getSharedPreferences("local_unlock", Context.MODE_PRIVATE).all.values.filterIsInstance<String>()
        assertTrue("Local credential was not stored", values.isNotEmpty())
        assertFalse("Local credential was stored in plaintext", values.any { it.contains(credential.value) })
        assertTrue("Local encrypted credential did not round-trip", store.read()?.value == credential.value)
        assertFalse("Diagnostic string leaked a local credential", credential.toString().contains(credential.value))
    }

    private fun assertLastAttempt(mode: String, outcome: String, credentialAttempted: Boolean) {
        val value = ok("phone_status").getJSONObject("unlock").getJSONObject("lastAttempt")
        assertEquals(setOf("stage", "outcome", "systemDismiss", "revealAttempted", "credentialAttempted", "reason"), value.keys().asSequence().toSet())
        assertTrue("Unexpected free-form diagnostic stage", value.getString("stage") in setOf("checking", "waking", "waiting_for_bouncer", "waiting_for_keyguard", "revealing", "preflight", "entering_credential", "verifying"))
        assertEquals(mode, value.getString("systemDismiss"))
        assertEquals(outcome, value.getString("outcome"))
        assertTrue("Unexpected free-form diagnostic reason", value.getString("reason") in UnlockDiagnostics.Reason.entries.map { it.name.lowercase() })
        assertEquals(credentialAttempted, value.getBoolean("credentialAttempted"))
        assertTrue(value.get("revealAttempted") is Boolean)
        assertFalse("Fixed diagnostics exposed a synthetic local credential", secrets.any { value.toString().contains(it) })
    }

    @Test fun localUnlockEndToEnd() = runBlocking {
        assumeTrue(arguments.getString("unlockTest") == "true")
        assertTrue("Unlock tests require a dedicated emulator", Build.HARDWARE in setOf("ranchu", "goldfish"))
        assertNull("Unlock tests refuse to replace a paired identity", prefs.identity)
        assertNull(prefs.pendingRevocation)
        assertFalse("Unlock tests refuse a pre-existing secure lock", keyguard.isDeviceSecure)
        val previousDisabled = shell("cmd lock_settings get-disabled")
        val previousTimeout = shell("settings get secure lock_screen_lock_after_timeout")
        val previousInstant = shell("settings get secure lockscreen.power_button_instantly_locks")
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        eventually("Initial unsecured keyguard did not dismiss") { !keyguard.isKeyguardLocked && power.isInteractive }
        val random = SecureRandom()
        val pin = UnlockCredential(UnlockKind.PIN, (0 until 6).joinToString("") { random.nextInt(10).toString() })
        val password = UnlockCredential(UnlockKind.PASSWORD, "Test" + UUID.randomUUID().toString().replace("-", "").take(12))
        val pattern = UnlockCredential(UnlockKind.PATTERN, listOf("12589", "14789", "25896")[random.nextInt(3)])
        secrets.addAll(listOf(pin.value, password.value, pattern.value))
        try {
            assertTrue(prefs.preferences.edit().clear().commit())
            store.clear()
            shell("settings put secure lock_screen_lock_after_timeout 0")
            shell("settings put secure lockscreen.power_button_instantly_locks 1")
            prefs.identity = BridgeNetwork.pair(arguments.getString("bridgeUrl")!!, arguments.getString("pairCode")!!, prefs, true)
            ActivityScenario.launch(MainActivity::class.java).close()
            shell("settings delete secure enabled_accessibility_services")
            delay(700)
            shell("settings put secure enabled_accessibility_services ${context.packageName}/.PhoneAccessibilityService")
            shell("settings put secure accessibility_enabled 1")
            eventually("Accessibility service did not bind") { PhoneAccessibilityService.instance != null }
            eventually("Phone bridge did not connect") { BridgeService.connected }
            val version = ok("phone_status")
            assertEquals(BuildConfig.VERSION_NAME, version.getString("appVersion"))
            assertEquals(BuildConfig.VERSION_CODE, version.getInt("appVersionCode"))
            denied("CAPABILITY_DENIED", "phone_unlock")
            prefs.setCapabilities(true)
            assertFalse("Ordinary capability grant enabled unlock", prefs.unlockEnabled)
            denied("CAPABILITY_DENIED", "phone_unlock")
            ActivityScenario.launch(AppPermissionsActivity::class.java).use { scenario ->
                scenario.onActivity { it.findViewById<Button>(R.id.grant_everything).performClick() }
            }
            assertFalse("Grant everything enabled separate unlock permission", prefs.unlockEnabled)
            prefs.unlockEnabled = true
            prefs.setFlag("pausedOperations", true)
            denied("OPERATIONS_PAUSED", "phone_unlock")
            prefs.setFlag("pausedOperations", false)
            prefs.setFlag("pausedAll", true)
            denied("PAUSED", "phone_unlock")
            prefs.setFlag("pausedAll", false)

            // Existing secrets never refill the local configuration; drafts do not survive recreation.
            store.save(pin)
            assertCiphertext(pin)
            ActivityScenario.launch(UnlockSettingsActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                    assertFalse(context.packageManager.getActivityInfo(ComponentName(context, UnlockSettingsActivity::class.java), 0).exported)
                    assertFalse(context.packageManager.getActivityInfo(ComponentName(context, UnlockActivity::class.java), 0).exported)
                    val field = activity.findViewById<EditText>(R.id.unlock_credential)
                    assertTrue(field.text.isEmpty())
                    assertFalse(field.isSaveEnabled)
                    assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, field.importantForAutofill)
                    field.setText(pin.value)
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    val field = activity.findViewById<EditText>(R.id.unlock_credential)
                    assertTrue("Credential draft survived activity recreation", field.text.isEmpty())
                    field.setText(pin.value)
                    activity.findViewById<Button>(R.id.unlock_save).performClick()
                    assertTrue("Saving a credential turned off the visible unlock permission", activity.findViewById<Switch>(R.id.unlock_enabled).isChecked)
                    assertTrue("Saved credential remained in the input field", field.text.isEmpty())
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertTrue("Recreation lost the saved unlock permission", activity.findViewById<Switch>(R.id.unlock_enabled).isChecked)
                    activity.findViewById<EditText>(R.id.unlock_credential).setText("12")
                    activity.findViewById<Button>(R.id.unlock_save).performClick()
                    assertTrue("Invalid credential changed the unlock permission", activity.findViewById<Switch>(R.id.unlock_enabled).isChecked)
                }
            }
            assertTrue("Saving a credential disabled remote unlock", prefs.unlockEnabled)
            assertCiphertext(pin)
            prefs.unlockEnabled = false
            ActivityScenario.launch(UnlockSettingsActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.findViewById<EditText>(R.id.unlock_credential).setText(pin.value)
                    activity.findViewById<Button>(R.id.unlock_save).performClick()
                    assertFalse("Saving a credential granted remote unlock", activity.findViewById<Switch>(R.id.unlock_enabled).isChecked)
                }
            }
            assertFalse("Saving a credential granted remote unlock", prefs.unlockEnabled)
            assertCiphertext(pin)
            prefs.unlockEnabled = true
            store.clear()
            prefs.setApp(demo, AppAccess(read = true, operate = true))
            prefs.setApp("com.android.systemui", AppAccess(read = true, operate = true))
            prefs.setApp("com.android.launcher3", AppAccess(read = true, operate = true))
            ok("phone_open_app", JSONObject().put("packageName", demo))

            shell("cmd lock_settings set-disabled false")
            sleepAndLock()
            denied("DEVICE_LOCKED", "phone_ui")
            denied("DEVICE_LOCKED", "phone_screenshot")
            val unsecured = unlockOnce()
            assertTrue(unsecured.getBoolean("unlocked"))
            assertEquals("system", unsecured.getString("path"))
            assertLastAttempt("system", "SUCCESS", credentialAttempted = false)
            assertFalse(keyguard.isKeyguardLocked)
            assertTrue(power.isInteractive)

            for (credential in listOf(pin, password, pattern)) {
                note("Checking local ${credential.kind} unlock")
                setSystemCredential(credential)
                eventually("Synthetic secure keyguard was not ready") { keyguard.isDeviceSecure }
                delay(500)
                store.save(credential)
                assertCiphertext(credential)
                sleepAndLock()
                denied("DEVICE_LOCKED", "phone_ui")
                denied("DEVICE_LOCKED", "phone_screenshot")
                denied("DEVICE_LOCKED", "phone_back", JSONObject().put("packageName", demo))
                val result = unlockOnce()
                assertEquals("local_credential", result.getString("path"))
                assertTrue(result.getBoolean("unlocked"))
                assertFalse(keyguard.isKeyguardLocked)
                assertFalse("Confirmed success kept retry latch set", store.retryBlocked)
                assertTrue(power.isInteractive)
                assertLastAttempt("wake_only", "SUCCESS", credentialAttempted = true)
            }

            setSystemCredential(pin)
            store.save(pin)
            shell("settings put secure enabled_accessibility_services $accessibilityComponent:$overlayComponent")
            eventually("Third-party accessibility obstruction did not appear") {
                PhoneAccessibilityService.instance?.windows?.any { it.root?.packageName?.toString() == instrumentation.context.packageName } == true
            }
            sleepAndLock()
            denied("UNLOCK_UNSUPPORTED", "phone_unlock")
            assertFalse("An obstructed lockscreen started credential input", store.retryBlocked)
            assertTrue(keyguard.isKeyguardLocked)
            shell("settings put secure enabled_accessibility_services $accessibilityComponent")
            eventually("Third-party obstruction did not disappear") {
                PhoneAccessibilityService.instance?.windows?.none {
                    it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY || it.root?.packageName?.toString() == instrumentation.context.packageName
                } == true
            }
            assertTrue(unlockOnce().getBoolean("unlocked"))
            note("PASS: overlapping third-party accessibility overlay refused before credential input; unlock succeeds once it is removed.")

            val wrong = UnlockCredential(UnlockKind.PIN, ((pin.value[0] - '0' + 1) % 10).toString() + pin.value.drop(1))
            secrets.add(wrong.value)
            store.save(wrong)
            sleepAndLock()
            val failed = denied("UNLOCK_FAILED", "phone_unlock")
            assertTrue("Failed attempt did not report an uncertain operation", failed.getBoolean("outcomeUnknown"))
            assertTrue("Failed attempt did not persist its retry latch", LocalUnlockStore(context).retryBlocked)
            assertLastAttempt("wake_only", "UNLOCK_FAILED", credentialAttempted = true)
            denied("UNLOCK_RETRY_BLOCKED", "phone_unlock")
            assertTrue(keyguard.isKeyguardLocked)
            recoverLocally(pin)
            assertTrue("Manual unlock implicitly allowed another automatic attempt", store.retryBlocked)
            ActivityScenario.launch(UnlockSettingsActivity::class.java).use { scenario ->
                scenario.onActivity { it.findViewById<Button>(R.id.unlock_reset_attempt).performClick() }
            }
            assertFalse(store.retryBlocked)
            store.save(pin)
            sleepAndLock()
            // Cancel immediately after the durable latch, before the first credential action.
            var cancelled = false
            withContext(Dispatchers.Main) {
                try {
                    LocalUnlocker(context, prefs).unlock {
                        if (store.retryBlocked) throw CancellationException("synthetic cancellation")
                    }
                } catch (_: CancellationException) { cancelled = true }
            }
            assertTrue("Cancellation did not interrupt local input", cancelled)
            assertTrue("Cancellation lost the durable retry latch", LocalUnlockStore(context).retryBlocked)
            denied("UNLOCK_RETRY_BLOCKED", "phone_unlock")
            recoverLocally(pin)
            store.save(pin)
            sleepAndLock()
            assertTrue(ok("phone_unlock").getBoolean("unlocked"))
            assertFalse(store.retryBlocked)

            // Unpairing must erase the local secret and its independent opt-in.
            prefs.identity = null
            assertFalse(store.configured)
            assertFalse(prefs.unlockEnabled)
            assertNull("Unpairing retained in-memory unlock diagnostics", UnlockDiagnostics.snapshot())
            note("PASS: swipe/PIN/password/pattern unlock, local ciphertext, secure settings, grants/pauses, locked-screen isolation, durable failure/cancellation latch, and unpair cleanup.")
        } finally {
            shell("settings put secure enabled_accessibility_services $accessibilityComponent")
            clearSystemCredential()
            shell("cmd lock_settings set-disabled $previousDisabled")
            shell("input keyevent KEYCODE_SLEEP")
            delay(300)
            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
            if (previousTimeout == "null") shell("settings delete secure lock_screen_lock_after_timeout")
            else shell("settings put secure lock_screen_lock_after_timeout $previousTimeout")
            if (previousInstant == "null") shell("settings delete secure lockscreen.power_button_instantly_locks")
            else shell("settings put secure lockscreen.power_button_instantly_locks $previousInstant")
            prefs.identity = null
            store.clear()
            prefs.unlockEnabled = false
        }
    }
}
