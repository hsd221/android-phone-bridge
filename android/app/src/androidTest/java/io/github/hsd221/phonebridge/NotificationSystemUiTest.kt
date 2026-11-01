package io.github.hsd221.phonebridge

import android.Manifest
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.app.UiAutomation
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises real Android channel switches and a real foreground service on an opted-in emulator. */
@RunWith(AndroidJUnit4::class)
class NotificationSystemUiTest {
    @get:Rule val appUpdates = AppUpdateFixture()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val prefs = BridgePrefs(context)
    private fun shell(command: String) = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        .executeShellCommand(command).use { ParcelFileDescriptor.AutoCloseInputStream(it).readBytes().toString(Charsets.UTF_8) }
    private fun eventually(description: String, diagnostics: () -> String = { "" }, condition: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 15000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        fail("$description ${diagnostics()}")
    }
    private fun openChannel(channel: String) {
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.executeAndWaitForEvent({
            context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, channel))
        }, { it.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && it.packageName?.toString() == "com.android.settings" }, 15000)
        automation.waitForIdle(200, 5000)
    }
    private fun switchNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isCheckable && node.className?.toString()?.contains("Switch") == true) return node
        for (index in 0 until node.childCount) switchNode(node.getChild(index))?.let { return it }
        return null
    }
    private fun setSystemSwitch(channel: String, enabled: Boolean) {
        var diagnostics = "No active window"
        var clicked = false
        eventually("System notification switch was not available", { diagnostics }) {
            val importance = manager.getNotificationChannel(channel)?.importance
            val channelMatches = if (enabled) importance != null && importance > NotificationManager.IMPORTANCE_NONE
                else importance == NotificationManager.IMPORTANCE_NONE
            val root = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).rootInActiveWindow
            diagnostics = "channel=$channel, importance=$importance, clicked=$clicked, package=${root?.packageName}"
            if (root?.refresh() != true) return@eventually false
            if (root?.packageName?.toString() != "com.android.settings") return@eventually false
            val node = switchNode(root) ?: return@eventually false
            if (!node.refresh()) return@eventually false
            diagnostics += ", switch=${node.viewIdResourceName},enabled=${node.isEnabled},checked=${node.isChecked}"
            if (channelMatches && node.isChecked == enabled) return@eventually true
            if (channelMatches || node.isChecked == enabled || clicked) return@eventually false
            var target = node
            while (!target.isClickable) target = target.parent ?: return@eventually false
            clicked = node.isEnabled && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            false
        }
    }
    @Suppress("DEPRECATION")
    private fun foregroundConnection() = context.getSystemService(ActivityManager::class.java).getRunningServices(50)
        .any { it.service.className == BridgeService::class.java.name && it.foreground }

    @Test fun userCanHideIdleForegroundNotificationAndKeepToolNotifications() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("notificationSystemUiTest") == "true")
        assertTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        assertNull("Refuse to replace a paired phone identity", prefs.identity)
        assertNull(prefs.pendingRevocation)
        assertTrue(prefs.preferences.edit().clear().commit())
        if (Build.VERSION.SDK_INT >= 33) shell("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
        BridgeNotificationChannels.ensure(context)
        val calls = ToolCallNotifications(context, prefs)
        // A synthetic, unreachable loopback revocation keeps the real connection service alive.
        prefs.pendingRevocation = PhoneIdentity("http://127.0.0.1:9", "notification-fixture", "notification-fixture", true)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                eventually("Foreground connection did not start") { foregroundConnection() }
                eventually("Foreground service notification missing") {
                    manager.activeNotifications.any { it.id == BridgeNotificationChannels.CONNECTION_ID &&
                        it.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0 }
                }
                openChannel(BridgeNotificationChannels.CONNECTION)
                setSystemSwitch(BridgeNotificationChannels.CONNECTION, false)
                eventually("User switch did not hide the connection category") {
                    manager.getNotificationChannel(BridgeNotificationChannels.CONNECTION).importance == NotificationManager.IMPORTANCE_NONE
                }
                BridgeNotificationChannels.ensure(context)
                assertEquals(NotificationManager.IMPORTANCE_NONE, manager.getNotificationChannel(BridgeNotificationChannels.CONNECTION).importance)
                eventually("Hidden connection notification remained visible") {
                    manager.activeNotifications.none { it.id == BridgeNotificationChannels.CONNECTION_ID }
                }
                assertTrue("Hiding the category stopped the foreground service", foregroundConnection())
                instrumentation.runOnMainSync { calls.begin("system-ui-fixture", "phone_ui") }
                eventually("Closing the connection category also hid the tool call") {
                    manager.activeNotifications.any { it.id == BridgeNotificationChannels.TOOL_CALL_ID }
                }
                instrumentation.runOnMainSync { calls.finish("system-ui-fixture") }
                eventually("Idle app still had a notification card") { manager.activeNotifications.isEmpty() }
                assertTrue(foregroundConnection())
                if (Build.VERSION.SDK_INT >= 36) {
                    openChannel(BridgeNotificationChannels.TOOL_CALLS)
                    setSystemSwitch(BridgeNotificationChannels.TOOL_CALLS, false)
                    eventually("Blocked tool channel did not disable Live Updates") {
                        BridgeNotificationChannels.liveUpdateAvailability(context) == LiveUpdateAvailability.CHANNEL_DISABLED
                    }
                    setSystemSwitch(BridgeNotificationChannels.TOOL_CALLS, true)
                    eventually("Tool channel was not restored") { manager.getNotificationChannel(BridgeNotificationChannels.TOOL_CALLS).importance > NotificationManager.IMPORTANCE_MIN }
                }
            }
        } finally {
            try {
                for (channel in listOf(BridgeNotificationChannels.CONNECTION, BridgeNotificationChannels.TOOL_CALLS)) {
                    openChannel(channel)
                    setSystemSwitch(channel, true)
                }
            } finally {
                instrumentation.runOnMainSync { calls.clear(); prefs.pendingRevocation = null }
                context.stopService(Intent(context, BridgeService::class.java))
                assertTrue(prefs.preferences.edit().clear().commit())
            }
        }
    }
}
