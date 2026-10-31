package io.github.hsd221.phonebridge

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.UiAutomation
import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith

/** Dedicated emulator only: scripts/android-smoke.ts explicitly opts into changing test grants. */
@RunWith(AndroidJUnit4::class)
class PermissionManagementTest {
    @get:Rule val appUpdates = AppUpdateFixture()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefs = BridgePrefs(context)
    private val alpha = "com.example.alpha"
    private val bravo = "com.example.bravo"
    private val hidden = "com.example.hiddenkeyboard"
    private var prepared = false

    @Before fun prepare() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("permissionUiTest") == "true")
        assertTrue("Permission tests require a dedicated emulator", Build.HARDWARE in setOf("ranchu", "goldfish"))
        assertNull("Permission tests refuse to replace a paired identity", prefs.identity)
        assertNull(prefs.pendingRevocation)
        assertTrue(prefs.preferences.edit().clear().commit())
        prepared = true
    }
    @After fun cleanup() {
        if (prepared) {
            assertTrue(prefs.preferences.edit().clear().commit())
        }
    }
    private fun eventually(description: String, diagnostics: () -> String = { "" }, condition: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 10000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        fail("$description ${diagnostics()}")
    }
    private fun shell(command: String) = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        .executeShellCommand(command).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes().toString(Charsets.UTF_8) }
    private fun appCount(list: ListView) = list.adapter.count - list.headerViewsCount
    private fun chooseDialogItem(label: String) {
        eventually("Missing batch option: $label") {
            var node = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).rootInActiveWindow
                ?.findAccessibilityNodeInfosByText(label)?.firstOrNull { it.text?.toString() == label }
            while (node != null && !node.isClickable) node = node.parent
            node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }
        instrumentation.waitForIdleSync()
    }

    @Test fun batchCommitsAreCompleteAndPreserveUnchangedSettings() {
        prefs.setFlag("pausedAll", true); prefs.setFlag("pausedOperations", true)
        prefs.setApps(mapOf(alpha to AppAccess(read = true), hidden to AppAccess(operate = true)))
        val observed = mutableListOf<AccessPolicy>()
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> observed.add(prefs.policy()) }
        prefs.preferences.registerOnSharedPreferenceChangeListener(listener)
        try {
            instrumentation.runOnMainSync {
                prefs.setPermissions(mapOf(alpha to AppAccess(true, true), bravo to AppAccess(true, true)), Capability.entries.toSet())
            }
            assertTrue(observed.isNotEmpty())
            observed.forEach { policy ->
                assertEquals(AppAccess(true, true), policy.apps[alpha])
                assertEquals(AppAccess(true, true), policy.apps[bravo])
                assertEquals(AppAccess(operate = true), policy.apps[hidden])
                assertEquals(Capability.entries.toSet(), policy.capabilities)
                assertTrue(policy.pausedAll); assertTrue(policy.pausedOperations)
            }
            val reloaded = BridgePrefs(context).policy()
            assertEquals(Capability.entries.toSet(), reloaded.capabilities)
            assertFalse(reloaded.apps.containsKey("com.example.future"))
        } finally { prefs.preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    @Test fun searchSelectionBatchActionsAndGlobalEnable() {
        prefs.setApps(mapOf(alpha to AppAccess(), bravo to AppAccess(operate = true), hidden to AppAccess()))
        prefs.setFlag("pausedAll", true); prefs.setFlag("pausedOperations", true)
        ActivityScenario.launch(AppPermissionsActivity::class.java).use { scenario ->
            eventually("Application catalog did not load") {
                var ready = false
                scenario.onActivity { ready = it.findViewById<Button>(R.id.grant_everything).isEnabled }
                ready
            }
            scenario.onActivity { activity ->
                assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                assertFalse(context.packageManager.getActivityInfo(ComponentName(context, AppPermissionsActivity::class.java), 0).exported)
                val search = activity.findViewById<EditText>(R.id.app_permission_search)
                search.setText(alpha.uppercase())
                assertEquals(1, appCount(activity.findViewById(R.id.app_permission_list)))
                activity.findViewById<Button>(R.id.select_filtered_apps).performClick()
                search.setText(bravo)
                activity.findViewById<Button>(R.id.select_filtered_apps).performClick()
                search.setText("no matching application")
                assertTrue(activity.findViewById<TextView>(R.id.app_permission_summary).text.contains("已选 2 个（筛选外 2 个）"))
            }
            scenario.recreate()
            eventually("Selections were lost after recreation") {
                var restored = false
                scenario.onActivity {
                    restored = it.findViewById<TextView>(R.id.app_permission_summary).text.contains("已选 2 个（筛选外 2 个）") &&
                        it.findViewById<EditText>(R.id.app_permission_search).text.toString() == "no matching application"
                }
                restored
            }
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            var landscapeDetails = ""
            eventually("Landscape layout has no usable application list or lost its selection", { landscapeDetails }) {
                var usable = false
                scenario.onActivity {
                    val list = it.findViewById<ListView>(R.id.app_permission_list)
                    landscapeDetails = "orientation=${it.resources.configuration.orientation}, screenHeightDp=${it.resources.configuration.screenHeightDp}, " +
                        "viewportHeightDp=${list.height / it.resources.displayMetrics.density}, applicationCount=${appCount(list)}, " +
                        "summary=${it.findViewById<TextView>(R.id.app_permission_summary).text}"
                    usable = it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                        list.height >= 80 * it.resources.displayMetrics.density && appCount(list) == 0 &&
                        it.findViewById<TextView>(R.id.app_permission_summary).text.contains("已选 2 个")
                }
                usable
            }
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            eventually("Portrait layout did not restore") {
                var restored = false
                scenario.onActivity {
                    restored = it.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT &&
                        it.findViewById<Button>(R.id.batch_app_permissions).isEnabled
                }
                restored
            }
            fun batch(label: String) {
                scenario.onActivity { it.findViewById<Button>(R.id.batch_app_permissions).performClick() }
                chooseDialogItem(label)
            }
            batch("允许读取（保留操作设置）")
            assertEquals(AppAccess(read = true), prefs.policy().apps[alpha])
            assertEquals(AppAccess(true, true), prefs.policy().apps[bravo])
            assertEquals(AppAccess(), prefs.policy().apps[hidden])
            batch("允许操作（保留读取设置）")
            assertEquals(AppAccess(true, true), prefs.policy().apps[alpha])
            batch("读取与操作全部禁止")
            assertEquals(AppAccess(), prefs.policy().apps[alpha])
            assertEquals(AppAccess(), prefs.policy().apps[bravo])
            batch("读取与操作全部允许")
            assertEquals(AppAccess(true, true), prefs.policy().apps[alpha])
            assertEquals(AppAccess(true, true), prefs.policy().apps[bravo])
            var catalogCount = 0
            scenario.onActivity { activity ->
                activity.findViewById<Button>(R.id.clear_selected_apps).performClick()
                assertFalse(activity.findViewById<Button>(R.id.batch_app_permissions).isEnabled)
                val search = activity.findViewById<EditText>(R.id.app_permission_search)
                search.setText("")
                catalogCount = appCount(activity.findViewById(R.id.app_permission_list))
                search.setText(alpha)
                activity.findViewById<Button>(R.id.select_filtered_apps).performClick()
                // This button must operate on the whole catalog, even with only one result selected.
                activity.findViewById<Button>(R.id.grant_everything).performClick()
            }
            val policy = prefs.policy()
            assertEquals(catalogCount, policy.apps.size)
            assertTrue(policy.apps.values.all { it == AppAccess(true, true) })
            assertEquals(AppAccess(true, true), policy.apps[hidden])
            assertFalse(policy.apps.containsKey(context.packageName))
            assertFalse(policy.apps.containsKey("com.example.future"))
            assertEquals(Capability.entries.toSet(), policy.capabilities)
            assertTrue(policy.pausedAll); assertTrue(policy.pausedOperations)
        }
    }

    @Test fun systemGrantsRefreshOnReturnAndNotificationChannelIsChecked() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                assertTrue(it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                it.findViewById<android.view.View>(R.id.nav_settings).performClick()
                assertTrue(it.findViewById<TextView>(R.id.system_autostart_status).text.contains("需手动确认"))
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION, Manifest.permission.POST_NOTIFICATIONS).forEach { permission ->
                assertEquals("", shell("pm grant ${context.packageName} $permission").trim())
            }
            scenario.moveToState(Lifecycle.State.RESUMED)
            eventually("System grants were not refreshed after returning") {
                var refreshed = false
                scenario.onActivity {
                    refreshed = it.findViewById<TextView>(R.id.system_location_status).text.toString() == "已允许精确定位" &&
                        it.findViewById<TextView>(R.id.system_background_location_status).text.toString() == "已允许（始终允许）" &&
                        it.findViewById<TextView>(R.id.system_notification_status).text.toString() == "已允许"
                }
                refreshed
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("bridge", "Test connection", NotificationManager.IMPORTANCE_NONE))
            eventually("Disabled connection channel was incorrectly reported as allowed") {
                var blocked = false
                scenario.onActivity { blocked = it.findViewById<TextView>(R.id.system_notification_status).text.contains("连接通知类别已关闭") }
                blocked
            }
            assertEquals(emptySet<Capability>(), prefs.policy().capabilities)
            assertTrue(prefs.policy().apps.isEmpty())
        }
    }
}
