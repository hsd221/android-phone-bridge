package io.github.hsd221.phonebridge

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Switch
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Permission is revoked before instrumentation starts, since revocation can kill the app process. */
@RunWith(AndroidJUnit4::class)
class NotificationPermissionTest {
    @get:Rule val appUpdates = AppUpdateFixture()
    @Test fun missingNotificationPermissionDisablesLiveUpdates() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("deniedNotificationPermissionTest") == "true")
        assertTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val context = instrumentation.targetContext
        val prefs = BridgePrefs(context)
        assertNull(prefs.identity); assertNull(prefs.pendingRevocation)
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        try {
            prefs.liveUpdatesEnabled = true
            val expected = if (Build.VERSION.SDK_INT >= 36) LiveUpdateAvailability.NOTIFICATIONS_DISABLED else LiveUpdateAvailability.UNSUPPORTED
            assertEquals(expected, BridgeNotificationChannels.liveUpdateAvailability(context))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val toggle = activity.findViewById<Switch>(R.id.live_updates_enabled)
                    assertFalse(toggle.isEnabled); assertFalse(toggle.isChecked)
                    assertEquals(activity.getString(expected.messageResource), activity.findViewById<TextView>(R.id.live_updates_status).text.toString())
                }
            }
        } finally { prefs.liveUpdatesEnabled = false }
    }
}
