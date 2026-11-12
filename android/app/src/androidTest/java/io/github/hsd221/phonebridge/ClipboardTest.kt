package io.github.hsd221.phonebridge

import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real shell UID 2000 and a foreign foreground app; no fabricated clipboard Binder. */
@RunWith(AndroidJUnit4::class)
class ClipboardTest {
    @get:Rule val appUpdates = AppUpdateFixture()
    private val session = DataMcpSession()
    private fun fixture(action: String, value: String = "synthetic background clipboard", label: String = "synthetic-label") {
        session.context.startActivity(Intent().setClassName(session.instrumentation.context.packageName, DataControlsFixture::class.java.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("clipAction", action).putExtra("clipText", value).putExtra("clipLabel", label))
        session.eventually("Bridge did not leave the foreground") { !BridgeService.activityVisible }
        session.instrumentation.waitForIdleSync()
        Thread.sleep(250)
    }
    private fun verify(value: JSONObject, expected: String?, path: String, truncated: Boolean = false) {
        assertTrue(value.getBoolean("available"))
        assertEquals(expected != null, value.getBoolean("hasText"))
        if (expected == null) assertTrue(value.isNull("text")) else assertEquals(expected, value.getString("text"))
        assertEquals(path, value.getString("path"))
        assertEquals(truncated, value.getBoolean("truncated"))
        assertTrue(value.getLong("capturedAt") > 0)
    }
    @Test fun backgroundClipboardUsesRealShellAndNeverTurnsDeniedNullIntoEmptySuccess(): Unit = runBlocking {
        assumeTrue(session.arguments.getString("dataToolTest") == "true")
        val prefs = session.prefs
        val keyguard = session.context.getSystemService(KeyguardManager::class.java)
        val power = session.context.getSystemService(PowerManager::class.java)
        assertFalse("Clipboard test refuses secure emulator credentials", keyguard.isDeviceSecure)
        assertNull(prefs.identity)
        assertTrue(prefs.preferences.edit().clear().commit())
        session.start()
        val previousDisabled = session.shell("locksettings get-disabled")
        assertTrue(previousDisabled in setOf("true", "false"))
        try {
            session.denied("CAPABILITY_DENIED", "phone_clipboard")
            prefs.setPermissions(tools = setOf("phone_clipboard")); prefs.shizukuEnabled = false
            ActivityScenario.launch(MainActivity::class.java).use {
                session.eventually("MainActivity did not become visible") { BridgeService.activityVisible }
                session.instrumentation.runOnMainSync {
                    session.context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("synthetic-native", "synthetic native clipboard"))
                }
                verify(session.ok("phone_clipboard"), "synthetic native clipboard", "android")
            }
            fixture("text")
            session.denied("CLIPBOARD_UNAVAILABLE", "phone_clipboard")
            val support = ShizukuSupport(session.context)
            session.eventually("Real non-root Shizuku was not available") { support.status().optBoolean("available") }
            assertEquals(2000, support.status().getInt("uid"))
            prefs.shizukuEnabled = true
            verify(session.ok("phone_clipboard"), "synthetic background clipboard", "shizuku")
            val longText = "合成clip".repeat(2200)
            fixture("text", longText, "label".repeat(60))
            val bounded = session.ok("phone_clipboard")
            verify(bounded, longText.take(10000), "shizuku", truncated = true)
            assertEquals(200, bounded.getString("label").length)
            prefs.setFlag("pausedOperations", true)
            verify(session.ok("phone_clipboard"), longText.take(10000), "shizuku", truncated = true)
            prefs.setFlag("pausedAll", true)
            session.denied("PAUSED", "phone_clipboard")
            prefs.setFlag("pausedAll", false); prefs.setFlag("pausedOperations", false)
            fixture("uri")
            verify(session.ok("phone_clipboard"), null, "shizuku")
            session.context.contentResolver.query(Uri.parse("content://io.github.hsd221.phonebridge.test.data/state"), null, null, null, null)!!.use {
                assertTrue(it.moveToFirst()); assertEquals("Clipboard read coerced a URI through its provider", 0, it.getInt(0))
            }
            fixture("clear")
            session.denied("CLIPBOARD_UNAVAILABLE", "phone_clipboard")
            fixture("text")
            var checks = 0
            val cancelled = withContext(Dispatchers.Main) {
                try {
                    support.readClipboard { if (++checks == 3) throw ToolFailure("CANCELLED", "Synthetic cancellation after Binder read") }
                    null
                } catch (failure: ToolFailure) { failure }
            }
            assertEquals("CANCELLED", cancelled?.code)
            assertEquals("The access callback was not checked after reading Binder data", 3, checks)
            session.shell("locksettings set-disabled false")
            session.shell("input keyevent KEYCODE_SLEEP")
            session.eventually("Synthetic clipboard test did not reach locked state") { keyguard.isKeyguardLocked && !power.isInteractive }
            session.denied("DEVICE_LOCKED", "phone_clipboard")
            session.shell("input keyevent KEYCODE_WAKEUP"); session.shell("wm dismiss-keyguard")
            session.eventually("Synthetic keyguard did not dismiss") { !keyguard.isKeyguardLocked && power.isInteractive }
            fixture("text")
            val pid = session.arguments.getString("dataShizukuServerPid")!!
            assertTrue(pid.matches(Regex("[0-9]+")))
            assertEquals("Refusing to stop a different Shizuku server", pid, session.shell("pidof shizuku_server"))
            session.shell("kill $pid")
            session.eventually("Owned Shizuku Binder did not disconnect") { !support.status().optBoolean("available") }
            session.denied("CLIPBOARD_UNAVAILABLE", "phone_clipboard")
            ActivityScenario.launch(MainActivity::class.java).use {
                session.eventually("MainActivity did not regain foreground for native fallback") { BridgeService.activityVisible }
                session.instrumentation.runOnMainSync {
                    session.context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("synthetic-fallback", "synthetic native fallback"))
                }
                verify(session.ok("phone_clipboard"), "synthetic native fallback", "android")
            }
            session.note("PASS: native foreground and real UID2000 background clipboard, conservative denied/empty errors, bounded text/label, no URI coercion, cancellation after read, pauses, locked refusal, and Binder-loss fallback.")
        } finally {
            try {
                try { session.shell("input keyevent KEYCODE_WAKEUP"); session.shell("wm dismiss-keyguard") }
                finally { session.shell("locksettings set-disabled $previousDisabled") }
            } finally { session.close() }
        }
    }
}
