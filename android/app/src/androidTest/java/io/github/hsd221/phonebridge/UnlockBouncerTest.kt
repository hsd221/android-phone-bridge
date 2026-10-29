package io.github.hsd221.phonebridge

import android.app.KeyguardManager
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.SecureRandom
import java.util.UUID

/** Local-only fallback checks. No credential, node tree, or screenshot goes to Bridge. */
@RunWith(AndroidJUnit4::class)
class UnlockBouncerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefs = BridgePrefs(context)
    private val store = LocalUnlockStore(context)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val accessibilityComponent get() = "${context.packageName}/.PhoneAccessibilityService"
    private val overlayComponent get() = "${instrumentation.context.packageName}/${UnlockOverlayFixture::class.java.name}"
    private var activeCredential: UnlockCredential? = null

    private fun note(value: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$value\n") })

    private fun shell(command: String): String = instrumentation
        .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        .executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes().toString(Charsets.UTF_8).trim()
        }

    private suspend fun eventually(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (withContext(Dispatchers.Main) { condition() }) return
            delay(100)
        }
        fail(description)
    }

    private fun key(code: Int) = instrumentation.sendKeyDownUpSync(code)

    private suspend fun sleepAndLock() {
        key(KeyEvent.KEYCODE_SLEEP)
        eventually("Synthetic device did not lock with screen off") { keyguard.isKeyguardLocked && !power.isInteractive }
    }

    private fun setSystemCredential(credential: UnlockCredential) {
        val command = when (credential.kind) {
            UnlockKind.PIN -> "set-pin"
            UnlockKind.PASSWORD -> "set-password"
            UnlockKind.PATTERN -> "set-pattern"
        }
        check(credential.value.matches(Regex("[A-Za-z0-9]+")))
        val old = activeCredential?.let { " --old ${it.value}" }.orEmpty()
        assertTrue("Cannot configure synthetic emulator credential", shell("cmd lock_settings $command$old ${credential.value}").contains("set to"))
        activeCredential = credential
        store.save(credential)
    }

    private fun clearSystemCredential() {
        activeCredential?.let { credential ->
            assertTrue("Cannot clear synthetic emulator credential", shell("cmd lock_settings clear --old ${credential.value}").contains("cleared"))
            activeCredential = null
        }
    }

    private fun visibleEntries(): List<AccessibilityNodeInfo> = PhoneAccessibilityService.instance?.windows.orEmpty()
        .filter { it.displayId == Display.DEFAULT_DISPLAY }
        .flatMap { window ->
            val root = window.root ?: return@flatMap emptyList()
            if (root.packageName?.toString() != "com.android.systemui") return@flatMap emptyList()
            listOf("pinEntry", "passwordEntry", "lockPatternView").flatMap { id ->
                root.findAccessibilityNodeInfosByViewId("com.android.systemui:id/$id").filter { it.isVisibleToUser }
            }
        }

    private fun assertNoCredentialInput() {
        assertTrue("Aborted fallback unexpectedly unlocked the phone", keyguard.isKeyguardLocked)
        assertFalse("Aborted fallback started credential input", LocalUnlockStore(context).retryBlocked)
        assertFalse("Aborted fallback left credential text in a system field", visibleEntries().any { it.isPassword && !it.text.isNullOrEmpty() })
    }

    private fun topology(): String = "dismiss=${UnlockActivity.dismissStatus}, locked=${keyguard.isKeyguardLocked}, deviceLocked=${keyguard.isDeviceLocked}, interactive=${power.isInteractive}, display=${context.resources.displayMetrics.widthPixels}x${context.resources.displayMetrics.heightPixels}; " +
        PhoneAccessibilityService.instance?.windows?.joinToString { window ->
            val root = window.root
            val markers = listOf("keyguard_root_view", "keyguard_status_view", "pinEntry", "passwordEntry", "lockPatternView").joinToString { id ->
                "$id=${root?.findAccessibilityNodeInfosByViewId("com.android.systemui:id/$id")?.count { it.isVisibleToUser } ?: 0}"
            }
            "${root?.packageName}:type=${window.type},layer=${window.layer},active=${window.isActive},focused=${window.isFocused},visible=${root?.isVisibleToUser},bounds=${Rect().also(window::getBoundsInScreen)}, $markers"
        }

    private suspend fun localUnlock(
        requestSystemDismiss: Boolean = true,
        onReveal: (RevealPhase) -> Unit = {},
    ): JSONObject = withContext(Dispatchers.Main) {
        try {
            LocalUnlocker(context, prefs, requestSystemDismiss, onReveal).unlock {}
        } catch (failure: ToolFailure) {
            note("Local failure ${failure.code}: ${failure.message}; " + topology())
            throw failure
        }
    }

    private suspend fun denied(expectedCode: String, action: suspend () -> Unit) {
        var failure: ToolFailure? = null
        try { action() } catch (caught: ToolFailure) { failure = caught }
        assertNotNull("Expected local refusal $expectedCode", failure)
        assertEquals(expectedCode, failure!!.code)
        withContext(Dispatchers.Main) { note("Expected local refusal ${failure.code}: ${failure.message}; " + topology()) }
        assertFalse("No credential was dispatched, so refusal is certain", failure.outcomeUnknown)
        withContext(Dispatchers.Main) { assertNoCredentialInput() }
    }

    private fun unknownWindow(close: Boolean) {
        context.startActivity(Intent().setComponent(ComponentName(instrumentation.context, UnlockUnknownWindowFixture::class.java))
            .putExtra("close", close).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    @Test fun missingBouncerRevealsOnceAndFailsClosed() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("unlockTest") == "true")
        assertTrue("Fallback tests require the dedicated emulator", Build.HARDWARE in setOf("ranchu", "goldfish"))
        assertNull("Fallback tests refuse a paired identity", prefs.identity)
        assertNull(prefs.pendingRevocation)
        assertFalse("Fallback tests refuse an existing secure lock", keyguard.isDeviceSecure)
        val previousAccessibility = shell("settings get secure enabled_accessibility_services")
        val previousEnabled = shell("settings get secure accessibility_enabled")
        val previousDisabled = shell("cmd lock_settings get-disabled")
        val previousTimeout = shell("settings get secure lock_screen_lock_after_timeout")
        val previousInstant = shell("settings get secure lockscreen.power_button_instantly_locks")
        val previousPreferences = prefs.preferences.all
        val random = SecureRandom()
        val pin = UnlockCredential(UnlockKind.PIN, (0 until 6).joinToString("") { random.nextInt(10).toString() })
        val password = UnlockCredential(UnlockKind.PASSWORD, "Test" + UUID.randomUUID().toString().replace("-", "").take(12))
        val pattern = UnlockCredential(UnlockKind.PATTERN, "12589")
        try {
            assertTrue(prefs.preferences.edit().clear().commit())
            store.clear()
            shell("settings put secure lock_screen_lock_after_timeout 0")
            shell("settings put secure lockscreen.power_button_instantly_locks 1")
            key(KeyEvent.KEYCODE_WAKEUP)
            key(KeyEvent.KEYCODE_MENU)
            ActivityScenario.launch(MainActivity::class.java).close()
            shell("settings delete secure enabled_accessibility_services")
            delay(700)
            shell("settings put secure enabled_accessibility_services $accessibilityComponent")
            shell("settings put secure accessibility_enabled 1")
            eventually("Accessibility did not bind for fallback test") { PhoneAccessibilityService.instance != null }
            prefs.setCapabilities(true)
            prefs.unlockEnabled = true

            setSystemCredential(pin)
            eventually("Synthetic keyguard security did not settle") { keyguard.isDeviceSecure }
            delay(500)
            sleepAndLock()
            var reproduced = 0
            denied("UNLOCK_UNSUPPORTED") {
                localUnlock(requestSystemDismiss = false) { phase ->
                    if (phase == RevealPhase.BEFORE_SWIPE) {
                        assertTrue("Missing-bouncer reproduction found an input field", visibleEntries().isEmpty())
                        reproduced++
                        throw ToolFailure("UNLOCK_UNSUPPORTED", "Synthetic pre-fix missing-bouncer control")
                    }
                }
            }
            assertEquals("Could not reproduce the wallpaper-only state", 1, reproduced)
            assertTrue(localUnlock().getBoolean("unlocked"))
            note("PASS: suppressed system dismissal reproduces wallpaper-only keyguard with no credential input.")

            for (credential in listOf(pin, password, pattern)) {
                note("Checking wallpaper-only ${credential.kind} fallback")
                setSystemCredential(credential)
                delay(500)
                sleepAndLock()
                val phases = mutableListOf<RevealPhase>()
                val result = localUnlock(requestSystemDismiss = false) { phases.add(it) }
                assertEquals("local_credential", result.getString("path"))
                assertTrue(result.getBoolean("unlocked"))
                assertFalse(keyguard.isKeyguardLocked)
                assertFalse(store.retryBlocked)
                assertEquals("Missing bouncer was not revealed exactly once", 1, phases.count { it == RevealPhase.BEFORE_SWIPE })
                assertEquals(1, phases.count { it == RevealPhase.AFTER_SWIPE })
                assertTrue("Known bouncer was never recognized after reveal", RevealPhase.FOUND_BOUNCER in phases)
                note("PASS: wallpaper-only ${credential.kind} reveals once and unlocks locally.")
            }

            setSystemCredential(pin)
            delay(500)
            for (phase in listOf(RevealPhase.BEFORE_SWIPE, RevealPhase.AFTER_SWIPE)) {
                for (pause in listOf(false, true)) {
                    sleepAndLock()
                    var invoked = 0
                    var cancelled = false
                    try {
                        if (pause) {
                            denied("OPERATIONS_PAUSED") {
                                localUnlock(requestSystemDismiss = false) { actual ->
                                    if (actual == phase) { invoked++; prefs.setFlag("pausedOperations", true) }
                                }
                            }
                        } else {
                            try {
                                localUnlock(requestSystemDismiss = false) { actual ->
                                    if (actual == phase) { invoked++; throw CancellationException("Synthetic reveal cancellation") }
                                }
                            } catch (_: CancellationException) { cancelled = true }
                            assertTrue("Cancellation did not stop fallback", cancelled)
                            withContext(Dispatchers.Main) { assertNoCredentialInput() }
                        }
                        assertEquals("Expected phase hook did not run once", 1, invoked)
                    } finally { prefs.setFlag("pausedOperations", false) }
                    var extraSwipes = 0
                    assertTrue(localUnlock { if (it == RevealPhase.BEFORE_SWIPE) extraSwipes++ }.getBoolean("unlocked"))
                    if (phase == RevealPhase.AFTER_SWIPE) assertEquals("An existing bouncer triggered another swipe", 0, extraSwipes)
                    note("PASS: ${if (pause) "pause" else "cancel"} at $phase stops before credentials; recovery leaves an existing keypad unswiped.")
                }
            }

            // A full-screen foreign accessibility overlay must block the reveal gesture.
            shell("settings put secure enabled_accessibility_services $accessibilityComponent:$overlayComponent")
            eventually("Synthetic intersecting overlay was not visible") {
                PhoneAccessibilityService.instance?.windows?.any { it.root?.packageName?.toString() == instrumentation.context.packageName } == true
            }
            sleepAndLock()
            var overlaySwipes = 0
            denied("UNLOCK_UNSUPPORTED") {
                localUnlock(requestSystemDismiss = false) { if (it == RevealPhase.BEFORE_SWIPE) overlaySwipes++ }
            }
            assertEquals("A third-party overlay allowed a reveal swipe", 0, overlaySwipes)
            shell("settings put secure enabled_accessibility_services $accessibilityComponent")
            eventually("Synthetic intersecting overlay remained visible") {
                PhoneAccessibilityService.instance?.windows?.none { it.root?.packageName?.toString() == instrumentation.context.packageName } == true
            }
            assertTrue(localUnlock().getBoolean("unlocked"))
            note("PASS: intersecting third-party overlay prevents both swipe and credential input.")

            sleepAndLock()
            unknownWindow(close = false)
            eventually("Synthetic unknown lock window did not become active") {
                PhoneAccessibilityService.instance?.rootInActiveWindow?.packageName?.toString() == instrumentation.context.packageName
            }
            var unknownSwipes = 0
            denied("UNLOCK_UNSUPPORTED") {
                localUnlock(requestSystemDismiss = false) { if (it == RevealPhase.BEFORE_SWIPE) unknownSwipes++ }
            }
            assertEquals("An unknown foreground window allowed a reveal swipe", 0, unknownSwipes)
            unknownWindow(close = true)
            eventually("Synthetic unknown window did not close") {
                PhoneAccessibilityService.instance?.windows?.none { it.root?.packageName?.toString() == instrumentation.context.packageName } == true
            }
            assertTrue(localUnlock().getBoolean("unlocked"))
            note("PASS: unknown foreground lock window fails closed; no fallback swipe or credential input.")
        } finally {
            unknownWindow(close = true)
            shell("settings put secure enabled_accessibility_services $accessibilityComponent")
            clearSystemCredential()
            shell("cmd lock_settings set-disabled $previousDisabled")
            key(KeyEvent.KEYCODE_SLEEP)
            delay(300)
            key(KeyEvent.KEYCODE_WAKEUP)
            key(KeyEvent.KEYCODE_MENU)
            if (previousTimeout == "null") shell("settings delete secure lock_screen_lock_after_timeout")
            else shell("settings put secure lock_screen_lock_after_timeout $previousTimeout")
            if (previousInstant == "null") shell("settings delete secure lockscreen.power_button_instantly_locks")
            else shell("settings put secure lockscreen.power_button_instantly_locks $previousInstant")
            store.clear()
            assertTrue(prefs.preferences.edit().clear().apply {
                previousPreferences.forEach { (name, value) ->
                    when (value) {
                        is String -> putString(name, value)
                        is Boolean -> putBoolean(name, value)
                        is Int -> putInt(name, value)
                        is Long -> putLong(name, value)
                        is Float -> putFloat(name, value)
                    }
                }
            }.commit())
            if (previousAccessibility == "null" || previousAccessibility.isEmpty()) shell("settings delete secure enabled_accessibility_services")
            else shell("settings put secure enabled_accessibility_services $previousAccessibility")
            shell("settings put secure accessibility_enabled ${if (previousEnabled == "null") "0" else previousEnabled}")
        }
    }
}
