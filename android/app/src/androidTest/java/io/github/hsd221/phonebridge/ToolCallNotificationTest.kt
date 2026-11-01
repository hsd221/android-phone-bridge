package io.github.hsd221.phonebridge

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.app.Notification
import android.app.NotificationManager
import android.app.UiAutomation
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.View
import android.widget.Switch
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

/** Explicitly opted-in, unpaired emulator only; never uses a user's phone identity or GitHub. */
@RunWith(AndroidJUnit4::class)
class ToolCallNotificationTest {
    @get:Rule val appUpdates = AppUpdateFixture()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefs = BridgePrefs(context)
    private val manager = context.getSystemService(NotificationManager::class.java)
    private lateinit var notifications: ToolCallNotifications
    private var prepared = false

    @Before fun prepare() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("permissionUiTest") == "true")
        assertTrue("Notification tests require a dedicated emulator", Build.HARDWARE in setOf("ranchu", "goldfish"))
        assertNull("Notification tests refuse to replace a paired identity", prefs.identity)
        assertNull(prefs.pendingRevocation)
        assertTrue(prefs.preferences.edit().clear().commit())
        prepared = true
        if (Build.VERSION.SDK_INT >= 33) shell("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
        BridgeNotificationChannels.ensure(context)
        notifications = ToolCallNotifications(context, prefs)
        notifications.clear()
    }

    @After fun cleanup() {
        if (!prepared) return
        instrumentation.runOnMainSync { notifications.clear() }
        manager.cancel(BridgeNotificationChannels.CONNECTION_ID)
        assertTrue(prefs.preferences.edit().clear().commit())
    }

    private fun shell(command: String) = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        .executeShellCommand(command).use { ParcelFileDescriptor.AutoCloseInputStream(it).readBytes().toString(Charsets.UTF_8) }

    private fun eventually(description: String, condition: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 10000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        fail(description)
    }

    private fun task() = manager.activeNotifications.firstOrNull { it.id == BridgeNotificationChannels.TOOL_CALL_ID }?.notification
    private fun taskText() = task()?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
    private fun connectionPresent() = manager.activeNotifications.any { it.id == BridgeNotificationChannels.CONNECTION_ID }

    @Test fun optInDefaultsOffAndSurvivesPreferenceReload() {
        assertFalse(prefs.liveUpdatesEnabled)
        prefs.liveUpdatesEnabled = true
        assertTrue(BridgePrefs(context).liveUpdatesEnabled)
        prefs.liveUpdatesEnabled = false
        assertFalse(BridgePrefs(context).liveUpdatesEnabled)
        instrumentation.runOnMainSync { notifications.refresh() }
        assertNull("Changing an idle display preference must not create a task notification", task())
    }

    @Test fun acceptedConcurrentCallsRemainVisibleUntilTheLastCompletion() {
        manager.notify(BridgeNotificationChannels.CONNECTION_ID,
            Notification.Builder(context, BridgeNotificationChannels.CONNECTION).setSmallIcon(R.drawable.ic_bridge)
                .setContentTitle("Synthetic connection fixture").build())
        eventually("Connection fixture missing") { connectionPresent() }
        instrumentation.runOnMainSync { notifications.begin("notification-fixture-first", "phone_location") }
        eventually("First call was not shown") { taskText()?.contains("phone_location") == true }
        instrumentation.runOnMainSync { notifications.begin("notification-fixture-second", "phone_ui") }
        eventually("Concurrent count was not shown") {
            task()?.extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() == context.resources.getQuantityString(R.plurals.tool_call_count, 2, 2)
        }
        assertTrue(connectionPresent())
        instrumentation.runOnMainSync { notifications.finish("notification-fixture-first") }
        eventually("Completing one call hid the other") { taskText()?.contains("phone_ui") == true }
        instrumentation.runOnMainSync { notifications.finish("not-a-running-call") }
        assertNotNull(task())
        instrumentation.runOnMainSync { notifications.finish("notification-fixture-second") }
        eventually("Idle task notification was not removed") { task() == null }
        assertTrue("Task completion must not cancel the connection notification", connectionPresent())
    }

    @Test fun clearAndCancellationRemoveStatusWithoutLeakingIds() {
        val privateRequestId = "fixture-private-request-id-do-not-display"
        instrumentation.runOnMainSync { notifications.begin(privateRequestId, "phone_input") }
        eventually("Call notification missing") { task() != null }
        val notification = requireNotNull(task())
        assertEquals(BridgeNotificationChannels.TOOL_CALLS, notification.channelId)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertEquals(2, notification.actions.size)
        assertFalse(notification.extras.toString().contains(privateRequestId))
        assertFalse("Live Updates must remain opt-in", notification.extras.getBoolean("android.requestPromotedOngoing"))
        instrumentation.runOnMainSync {
            notifications.begin("cancelled-queued-call", "phone_back")
            notifications.finish("cancelled-queued-call")
        }
        assertNotNull(task())
        instrumentation.runOnMainSync { notifications.clear(); notifications.clear(); notifications.finish(privateRequestId) }
        eventually("Disconnect/stop cleanup left a task notification") { task() == null }
    }

    @Test fun eligibilityControlsTheSettingsSwitchAndRefreshesAfterReturn() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.nav_settings).performClick()
                val availability = BridgeNotificationChannels.liveUpdateAvailability(activity)
                val toggle = activity.findViewById<Switch>(R.id.live_updates_enabled)
                assertFalse(toggle.isChecked)
                assertEquals(availability == LiveUpdateAvailability.AVAILABLE, toggle.isEnabled)
                assertEquals(activity.getString(availability.messageResource), activity.findViewById<TextView>(R.id.live_updates_status).text.toString())
                if (Build.VERSION.SDK_INT < 36) {
                    assertEquals(LiveUpdateAvailability.UNSUPPORTED, availability)
                    toggle.performClick()
                    assertFalse(prefs.liveUpdatesEnabled)
                } else if (availability == LiveUpdateAvailability.AVAILABLE) {
                    toggle.performClick()
                    assertTrue(prefs.liveUpdatesEnabled)
                }
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            prefs.liveUpdatesEnabled = true
            scenario.moveToState(Lifecycle.State.RESUMED)
            eventually("Settings did not reflect current availability after returning") {
                var correct = false
                scenario.onActivity { activity ->
                    val available = BridgeNotificationChannels.liveUpdateAvailability(activity) == LiveUpdateAvailability.AVAILABLE
                    val toggle = activity.findViewById<Switch>(R.id.live_updates_enabled)
                    correct = toggle.isEnabled == available && toggle.isChecked == available
                }
                correct
            }
        }
    }

    @Test fun standardNotificationIsUsedWhenOptInOrSystemEligibilityIsMissing() {
        val eligible = BridgeNotificationChannels.liveUpdateAvailability(context) == LiveUpdateAvailability.AVAILABLE
        prefs.liveUpdatesEnabled = true
        instrumentation.runOnMainSync { notifications.begin("live-update-protocol-fixture", "phone_ui") }
        eventually("Active task notification missing") { task() != null }
        assertEquals(eligible, requireNotNull(task()).extras.getBoolean("android.requestPromotedOngoing"))
        if (Build.VERSION.SDK_INT >= 36) {
            val promoted = requireNotNull(task()).flags and Notification.FLAG_PROMOTED_ONGOING != 0
            android.util.Log.i("ToolCallNotificationTest", "api=${Build.VERSION.SDK_INT},eligible=$eligible,requested=${task()?.extras?.getBoolean("android.requestPromotedOngoing")},promoted=$promoted")
        }
        instrumentation.runOnMainSync { prefs.liveUpdatesEnabled = false; notifications.refresh() }
        eventually("Disabling opt-in left a promotion request") { task()?.extras?.getBoolean("android.requestPromotedOngoing") == false }
    }

    @Test fun settingsEntrypointsTargetTheCorrectChannelAndPromotionPage() {
        val opened = AtomicReference<Intent>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (!intent.action.orEmpty().startsWith("android.settings.")) return null
                opened.set(Intent(intent))
                return Instrumentation.ActivityResult(Activity.RESULT_OK, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.findViewById<View>(R.id.nav_settings).performClick()
                    listOf(R.id.connection_notification_settings to BridgeNotificationChannels.CONNECTION,
                        R.id.tool_notification_settings to BridgeNotificationChannels.TOOL_CALLS).forEach { (id, channel) ->
                        activity.findViewById<View>(id).performClick()
                        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, opened.get().action)
                        assertEquals(context.packageName, opened.get().getStringExtra(Settings.EXTRA_APP_PACKAGE))
                        assertEquals(channel, opened.get().getStringExtra(Settings.EXTRA_CHANNEL_ID))
                    }
                    activity.findViewById<View>(R.id.live_updates_system_settings).performClick()
                    val promotionAvailable = Build.VERSION.SDK_INT >= 36 && Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
                        .resolveActivity(activity.packageManager) != null
                    assertEquals(if (promotionAvailable) Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS else Settings.ACTION_APP_NOTIFICATION_SETTINGS,
                        opened.get().action)
                    assertEquals(context.packageName, opened.get().getStringExtra(Settings.EXTRA_APP_PACKAGE))
                }
            }
        } finally { instrumentation.removeMonitor(monitor) }
    }
}
