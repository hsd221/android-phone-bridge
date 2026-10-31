package io.github.hsd221.phonebridge

import android.app.KeyguardManager
import android.app.UiAutomation
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.RemoteException
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.UUID

/** Dedicated emulator only. Real shell Binder, system lockscreen and synthetic local credentials. */
@RunWith(AndroidJUnit4::class)
class ShizukuUnlockTest {
    @get:Rule val appUpdates = AppUpdateFixture()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefs = BridgePrefs(context)
    private val store = LocalUnlockStore(context)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private var activeCredential: UnlockCredential? = null
    private val component get() = "${context.packageName}/.PhoneAccessibilityService"

    private fun note(value: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$value\n") })
    private fun shell(command: String): String {
        if (ShizukuSupport(context).status().optBoolean("available")) {
            // Test APK only: use this library's legacy process API to configure
            // and remove synthetic emulator credentials locally. No automation
            // connection exists in instrumentation while the product owns one.
            val method = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
                .apply { isAccessible = true }
            val process = method.invoke(null, arrayOf("/system/bin/sh", "-c", command), null, null) as ShizukuRemoteProcess
            check(process.waitForTimeout(10, TimeUnit.SECONDS)) { "Synthetic emulator shell command timed out" }
            val result = process.inputStream.use { it.readBytes().toString(Charsets.UTF_8).trim() }
            check(process.exitValue() == 0) { "Synthetic emulator shell command failed" }
            return result
        }
        // The test stops its own Shizuku server only after all product shell
        // sessions have closed. Ordinary UiAutomation is first created here,
        // for the subsequent accessibility-only fallback and local cleanup.
        return instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            .executeShellCommand(command).use {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes().toString(Charsets.UTF_8).trim()
            }
    }
    private suspend fun eventually(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (withContext(Dispatchers.Main) { condition() }) return
            delay(100)
        }
        fail(message)
    }
    private suspend fun authorizeShizuku() {
        val support = ShizukuSupport(context)
        eventually("A real non-root Shizuku server is required") { support.status().optBoolean("running") }
        eventually("Test harness did not grant the Shizuku runtime permission") { support.status().optBoolean("available") }
        assertTrue("Shizuku is not authorized as shell UID 2000", support.status().optBoolean("available"))
    }
    private fun setSystemCredential(credential: UnlockCredential) {
        val command = when (credential.kind) {
            UnlockKind.PIN -> "set-pin"
            UnlockKind.PASSWORD -> "set-password"
            UnlockKind.PATTERN -> "set-pattern"
        }
        check(credential.value.matches(Regex("[A-Za-z0-9]+")))
        val old = activeCredential?.let { " --old ${it.value}" }.orEmpty()
        assertTrue("Cannot configure the synthetic emulator credential", shell("cmd lock_settings $command$old ${credential.value}").contains("set to"))
        activeCredential = credential
        store.save(credential)
    }
    private fun clearSystemCredential() {
        activeCredential?.let {
            assertTrue("Cannot remove the synthetic emulator credential", shell("cmd lock_settings clear --old ${it.value}").contains("cleared"))
            activeCredential = null
        }
    }
    private suspend fun sleepAndLock() {
        shell("input keyevent KEYCODE_SLEEP")
        eventually("Synthetic device did not reach both locked states with screen off") {
            keyguard.isKeyguardLocked && keyguard.isDeviceLocked && !power.isInteractive
        }
    }
    private suspend fun revealPin() {
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        eventually("Synthetic PIN keyboard was not shown") { pinRoot() != null }
    }
    private fun pinRoot() = if (!keyguard.isKeyguardLocked || !keyguard.isDeviceLocked || !power.isInteractive) null
        else PhoneAccessibilityService.instance?.windows.orEmpty().asSequence().mapNotNull { it.root }
            .firstOrNull { it.refresh() && it.packageName?.toString() == "com.android.systemui" &&
                it.findAccessibilityNodeInfosByViewId("com.android.systemui:id/pinEntry").any { node -> node.refresh() && node.isVisibleToUser } }
    private fun pinField() = pinRoot()?.findAccessibilityNodeInfosByViewId("com.android.systemui:id/pinEntry")
        ?.singleOrNull { it.refresh() && it.isVisibleToUser }
    private suspend fun localUnlock(): JSONObject {
        val originalFlags = withContext(Dispatchers.Main) { PhoneAccessibilityService.instance!!.serviceInfo.flags }
        return withContext(Dispatchers.Main) {
            try { LocalUnlocker(context, prefs).unlock {} }
            finally {
                assertEquals("Unlock retained temporary accessibility flags", originalFlags, PhoneAccessibilityService.instance!!.serviceInfo.flags)
            }
        }.also { delay(300) } // The shell UserService exits after its unbind transaction.
    }
    private suspend fun recoverPinLocally(credential: UnlockCredential) {
        shell("input keyevent KEYCODE_SLEEP")
        delay(200)
        revealPin()
        // Test setup/recovery uses shell key events, so it does not depend on
        // the product's temporary INCLUDE_NOT_IMPORTANT_VIEWS flag being set.
        // This synthetic credential stays inside the dedicated emulator.
        repeat(20) {
            if (pinField()?.text?.isNotEmpty() == true) shell("input keyevent KEYCODE_DEL")
        }
        assertTrue("Synthetic recovery could not clear partial input", pinField()?.text.isNullOrEmpty())
        shell("input keyevent ${credential.value.map { "KEYCODE_$it" }.joinToString(" ")} KEYCODE_ENTER")
        eventually("Synthetic local recovery did not unlock") { !keyguard.isKeyguardLocked && !keyguard.isDeviceLocked }
    }

    @Test fun recoverStoredSyntheticCredential() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("shizukuUnlockRecovery") == "true")
        assertTrue("Recovery requires a dedicated emulator", Build.HARDWARE in setOf("ranchu", "goldfish"))
        assertNull("Recovery refuses a paired app", prefs.identity)
        val credential = store.read() ?: return
        assertTrue("Recovery failed to clear the stored synthetic lock",
            shell("cmd lock_settings clear --old ${credential.value}").contains("cleared"))
        store.clear()
        prefs.unlockEnabled = false
        prefs.shizukuEnabled = false
        note("PASS: stored synthetic emulator credential cleared locally.")
    }

    @Test fun realShellInputAndDisconnection() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("shizukuUnlockTest") == "true")
        assertTrue("This test only runs on the dedicated emulator", Build.HARDWARE in setOf("ranchu", "goldfish"))
        assertNull("Refusing to replace a paired identity", prefs.identity)
        assertNull(prefs.pendingRevocation)
        assertFalse("Refusing an existing secure lock", keyguard.isDeviceSecure)
        authorizeShizuku() // No instrumentation UiAutomation may precede product shell sessions.
        val serverPid = InstrumentationRegistry.getArguments().getString("shizukuUnlockServerPid").orEmpty()
        assertTrue("The harness must identify its own Shizuku server", serverPid.matches(Regex("[1-9][0-9]*")))
        assertEquals("The harness PID does not match the running server", serverPid, shell("pidof shizuku_server"))
        // ART can rename the main thread independently of the process argv[0].
        assertEquals("shizuku_server", shell("cat /proc/$serverPid/cmdline").substringBefore('\u0000'))
        assertTrue("The harness server must retain non-root shell UID 2000",
            Regex("""(?m)^Uid:\s+2000\s+2000\s+2000\s+2000$""").containsMatchIn(shell("cat /proc/$serverPid/status")))
        val previousAccessibility = shell("settings get secure enabled_accessibility_services")
        val previousEnabled = shell("settings get secure accessibility_enabled")
        val previousDisabled = shell("cmd lock_settings get-disabled")
        val previousTimeout = shell("settings get secure lock_screen_lock_after_timeout")
        val previousInstant = shell("settings get secure lockscreen.power_button_instantly_locks")
        val random = SecureRandom()
        val pin = UnlockCredential(UnlockKind.PIN, (0 until 6).joinToString("") { random.nextInt(10).toString() })
        val password = UnlockCredential(UnlockKind.PASSWORD, "Test" + UUID.randomUUID().toString().replace("-", "").take(12))
        val pattern = UnlockCredential(UnlockKind.PATTERN, "12589")
        var primaryFailure: Throwable? = null
        try {
            assertTrue(prefs.preferences.edit().clear().commit())
            store.clear()
            shell("settings put secure lock_screen_lock_after_timeout 0")
            shell("settings put secure lockscreen.power_button_instantly_locks 1")
            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
            ActivityScenario.launch(MainActivity::class.java).close()
            shell("settings delete secure enabled_accessibility_services")
            delay(500)
            shell("settings put secure enabled_accessibility_services $component")
            shell("settings put secure accessibility_enabled 1")
            eventually("Accessibility did not bind") { PhoneAccessibilityService.instance != null }
            prefs.setCapabilities(true)
            prefs.unlockEnabled = true
            prefs.shizukuEnabled = true
            authorizeShizuku()

            for (credential in listOf(pin, password, pattern)) {
                setSystemCredential(credential)
                eventually("Synthetic credential has not settled") { keyguard.isDeviceSecure }
                delay(400)
                sleepAndLock()
                val before = LocalUnlocker.screenState(context)
                assertTrue("Synthetic ${credential.kind} keyguard is not locked", before.getBoolean("keyguardLocked"))
                assertTrue("Synthetic ${credential.kind} device is not locked", before.getBoolean("deviceLocked"))
                val result = localUnlock()
                assertEquals("Expected a real Shizuku path, not accessibility fallback", "shizuku", result.getString("path"))
                assertTrue(result.getBoolean("unlocked"))
                assertFalse(keyguard.isKeyguardLocked)
                assertFalse(keyguard.isDeviceLocked)
                assertFalse("Confirmed shell success retained retry block", store.retryBlocked)
                assertTrue("Shell did not record credential attempt", UnlockDiagnostics.snapshot()!!.getBoolean("credentialAttempted"))
                note("PASS: real Shizuku ${credential.kind} unlocked the synthetic system keyguard.")
            }

            setSystemCredential(pin)
            sleepAndLock()
            revealPin()
            shell("input keyevent KEYCODE_1")
            eventually("Synthetic partial PIN did not settle") { pinField()?.text?.isNotEmpty() == true }
            val beforePartial = withContext(Dispatchers.Main) { pinField()?.text?.toString() }
            val failure = try { localUnlock(); null } catch (caught: ToolFailure) { caught }
            assertNotNull("Shell appended to existing input", failure)
            assertEquals("USER_UNLOCK_REQUIRED", failure!!.code)
            assertFalse(failure.outcomeUnknown)
            assertFalse("Partial input refusal persisted the attempt latch", store.retryBlocked)
            assertTrue("Shell changed existing input", beforePartial == withContext(Dispatchers.Main) { pinField()?.text?.toString() })
            delay(300)
            recoverPinLocally(pin)
            note("PASS: real Shizuku refuses an existing partial PIN before the durable latch.")

            sleepAndLock()
            revealPin()
            val input = ShizukuCredentialInput.open(context) {}!!
            try {
                val submission = input.preflight(pin) {}
                store.beginAttempt()
                val field = input.javaClass.getDeclaredField("remote").apply { isAccessible = true }
                val remote = field.get(input) as ILocalKeyguard
                var destroyed = false
                val disconnected = try {
                    input.enter(pin, submission, ensure = {
                        if (!destroyed && pinField()?.text?.isNotEmpty() == true) {
                            destroyed = true
                            try { remote.destroy() } catch (_: RemoteException) { }
                            val deadline = SystemClock.elapsedRealtime() + 2000
                            while (remote.asBinder().isBinderAlive && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
                            assertFalse("Real shell Binder did not die", remote.asBinder().isBinderAlive)
                        }
                    }, isUnlocked = { !keyguard.isKeyguardLocked && !keyguard.isDeviceLocked })
                    null
                } catch (caught: ToolFailure) { caught }
                assertTrue("The test never interrupted actual PIN input", destroyed)
                assertNotNull("Shell disconnection was not reported", disconnected)
                assertEquals("UNLOCK_INTERRUPTED", disconnected!!.code)
                assertTrue("In-flight shell disconnection is an unknown outcome", disconnected.outcomeUnknown)
                assertTrue("Shell disconnection cleared the durable retry latch", LocalUnlockStore(context).retryBlocked)
                assertTrue("Shell disconnection unexpectedly unlocked", keyguard.isKeyguardLocked)
                val retry = try { localUnlock(); null } catch (caught: ToolFailure) { caught }
                assertEquals("UNLOCK_RETRY_BLOCKED", retry?.code)
            } finally { input.close() }
            delay(300)
            recoverPinLocally(pin)
            store.save(pin)
            note("PASS: a real Binder death after input stops the sequence and blocks automatic retry.")

            // This server was started by the emulator-only harness. Stop it to
            // validate actual unavailable-service fallback with the enhancement on.
            // Shizuku.exit() is manager-only. Return this test's remote shell
            // process before its detached child stops the harness-owned server.
            assertFalse("Synthetic recovery must unlock before stopping Shizuku", keyguard.isDeviceLocked)
            shell("(sleep 1; kill $serverPid) </dev/null >/dev/null 2>&1 &")
            eventually("Shizuku server did not stop") { !ShizukuSupport(context).status().optBoolean("running") }
            sleepAndLock()
            val fallback = localUnlock()
            assertEquals("Disconnected Shizuku did not select accessibility", "local_credential", fallback.getString("path"))
            assertTrue(fallback.getBoolean("unlocked"))
            assertTrue("Fallback changed the user's enhancement setting", prefs.shizukuEnabled)
            assertFalse(store.retryBlocked)
            note("PASS: a disconnected Shizuku server falls back to accessibility before credential input.")
        } catch (failure: Throwable) {
            primaryFailure = failure
            note("FAIL: shell suite stopped before cleanup (${failure.javaClass.simpleName}).")
            throw failure
        } finally {
            // Preserve the original test failure if environment cleanup also fails.
            try {
            delay(300)
            clearSystemCredential()
            shell("cmd lock_settings set-disabled $previousDisabled")
            if (previousAccessibility == "null" || previousAccessibility.isEmpty()) shell("settings delete secure enabled_accessibility_services")
            else shell("settings put secure enabled_accessibility_services $previousAccessibility")
            shell("settings put secure accessibility_enabled ${if (previousEnabled == "null") "0" else previousEnabled}")
            if (previousTimeout == "null") shell("settings delete secure lock_screen_lock_after_timeout")
            else shell("settings put secure lock_screen_lock_after_timeout $previousTimeout")
            if (previousInstant == "null") shell("settings delete secure lockscreen.power_button_instantly_locks")
            else shell("settings put secure lockscreen.power_button_instantly_locks $previousInstant")
            store.clear()
            prefs.unlockEnabled = false
            prefs.shizukuEnabled = false
            shell("input keyevent KEYCODE_SLEEP")
            delay(200)
            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
            } catch (cleanup: Throwable) {
                if (primaryFailure != null) primaryFailure.addSuppressed(cleanup) else throw cleanup
            }
        }
    }
}
