package io.github.hsd221.phonebridge

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import android.view.accessibility.AccessibilityNodeInfo
import android.accessibilityservice.AccessibilityServiceInfo
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** Frontend behavior, using a dedicated emulator with no saved connection identity. */
@RunWith(AndroidJUnit4::class)
class FrontendInteractionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefs = BridgePrefs(context)
    private var prepared = false

    @Before fun prepare() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("permissionUiTest") == "true")
        assertTrue("Frontend tests require a dedicated emulator", Build.HARDWARE in setOf("ranchu", "goldfish"))
        assertNull("Frontend tests refuse to replace a paired identity", prefs.identity)
        assertNull(prefs.pendingRevocation)
        assertTrue(prefs.preferences.edit().clear().commit())
        prepared = true
    }
    @After fun cleanup() {
        if (prepared) assertTrue(prefs.preferences.edit().clear().commit())
    }
    private fun eventually(description: String, condition: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 10000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        fail(description)
    }
    private fun assertChrome(activity: MainActivity, selected: Int) {
        val density = activity.resources.displayMetrics.density
        val decor = activity.window.decorView
        listOf(R.id.nav_connection, R.id.nav_access, R.id.nav_settings).forEach { id ->
            val nav = activity.findViewById<Button>(id)
            assertTrue("Navigation is hidden", nav.isShown)
            assertEquals(id == selected, nav.isSelected)
            assertTrue("Navigation touch target is smaller than 48dp", nav.height >= 48 * density)
            val location = IntArray(2).also(nav::getLocationInWindow)
            assertTrue("Navigation overflows the window", location[0] >= 0 && location[1] >= 0 &&
                location[0] + nav.width <= decor.width && location[1] + nav.height <= decor.height)
        }
    }
    private fun inlineError(input: EditText): String? = (input.parent as ViewGroup).let { field ->
        (0 until field.childCount).map(field::getChildAt).filterIsInstance<TextView>()
            .firstOrNull { it !== input && it.visibility == View.VISIBLE && it.accessibilityLiveRegion == View.ACCESSIBILITY_LIVE_REGION_POLITE }
            ?.text?.toString()
    }
    private fun drawFixture(activity: android.app.Activity, name: String) {
        assertTrue("Fixture must preserve FLAG_SECURE", activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        val decor = activity.window.decorView
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bitmap))
        val output = File(context.cacheDir, "frontend-screens/$name.png").apply { parentFile!!.mkdirs() }
        output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        println("Frontend view rendering: ${output.name}")
    }
    private fun nodeById(id: String): AccessibilityNodeInfo? {
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
        return automation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId("${context.packageName}:id/$id")?.firstOrNull()
    }
    private fun dialogAction(label: String) {
        eventually("Dialog action was unavailable: $label") {
            var node = instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(label)
                ?.firstOrNull { it.text?.toString() == label }
            while (node != null && !node.isClickable) node = node.parent
            node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }
        instrumentation.waitForIdleSync()
    }
    private fun enterPackage(value: String) {
        eventually("Package field was unavailable") {
            nodeById("manual_package")?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            }) == true
        }
        instrumentation.waitForIdleSync()
    }
    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes().toString(Charsets.UTF_8).trim()
    }

    @Test fun pairingValidationIsInlineAndDoesNotCreateAConnection() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val address = activity.findViewById<EditText>(R.id.pair_address)
                val code = activity.findViewById<EditText>(R.id.pair_code)
                val submit = activity.findViewById<Button>(R.id.pair_submit)
                submit.performClick()
                assertNotNull(inlineError(address)); assertNotNull(inlineError(code))
                address.createAccessibilityNodeInfo().let { info ->
                    assertTrue("Address validation is missing from accessibility semantics", info.isContentInvalid)
                    assertEquals(inlineError(address), info.error?.toString())
                }
                assertTrue(address.hasFocus()); assertTrue(submit.isEnabled)
                assertNull(prefs.identity)
                assertFalse("Invalid form reached network pairing", prefs.preferences.contains("deviceId"))
                address.setText("http://127.0.0.1:1")
                code.setText("LOCAL-TEST")
                submit.performClick()
                assertTrue(inlineError(address).toString().contains("HTTPS"))
                assertNull(prefs.identity)
                assertFalse(prefs.preferences.contains("deviceId"))
                code.setText("")
                activity.findViewById<Switch>(R.id.pair_allow_http).performClick()
                submit.performClick()
                assertNull(inlineError(address)); assertNotNull(inlineError(code))
                assertFalse("Corrected address remains invalid for accessibility", address.createAccessibilityNodeInfo().isContentInvalid)
                assertTrue(code.hasFocus()); assertTrue(submit.isEnabled)
                assertFalse(prefs.preferences.contains("deviceId"))
            }
        }
    }

    @Test fun navigationAndDraftAddressSurviveRecreationAndRotation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertChrome(activity, R.id.nav_connection)
                activity.findViewById<EditText>(R.id.pair_address).setText("http://10.0.2.2:8787")
                activity.findViewById<EditText>(R.id.pair_code).setText("DO-NOT-PERSIST")
                activity.findViewById<Switch>(R.id.pair_allow_http).performClick()
                activity.findViewById<Button>(R.id.nav_settings).performClick()
                assertTrue(activity.findViewById<TextView>(R.id.system_accessibility_status).isShown)
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertChrome(activity, R.id.nav_settings)
                activity.findViewById<Button>(R.id.nav_connection).performClick()
                assertEquals("http://10.0.2.2:8787", activity.findViewById<EditText>(R.id.pair_address).text.toString())
                assertEquals("", activity.findViewById<EditText>(R.id.pair_code).text.toString())
                assertTrue(activity.findViewById<Switch>(R.id.pair_allow_http).isChecked)
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            eventually("Landscape navigation or pairing draft was lost") {
                var valid = false
                scenario.onActivity { activity ->
                    if (activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                        assertChrome(activity, R.id.nav_connection)
                        valid = activity.findViewById<EditText>(R.id.pair_address).text.toString() == "http://10.0.2.2:8787"
                    }
                }
                valid
            }
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        }
    }

    @Test fun pairingInFlightSurvivesRotationAndFailureRemainsRetryable() {
        val received = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        val serverFailure = AtomicReference<Throwable?>()
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 10000 }
        val worker = thread(name = "frontend-pairing-fixture", isDaemon = true) {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 10000
                    val input = socket.getInputStream().buffered()
                    fun line(): String = buildString {
                        while (true) {
                            val next = input.read()
                            check(next >= 0) { "Pair request ended before its headers" }
                            if (next == 10) break
                            if (next != 13) append(next.toChar())
                        }
                    }
                    check(line().startsWith("POST /pair "))
                    var bodyLength = 0
                    while (true) {
                        val header = line()
                        if (header.isEmpty()) break
                        if (header.startsWith("Content-Length:", true)) bodyLength = header.substringAfter(':').trim().toInt()
                    }
                    repeat(bodyLength) { check(input.read() >= 0) }
                    received.countDown()
                    check(releaseResponse.await(10, TimeUnit.SECONDS))
                    val body = "{\"error\":\"test-expired\"}"
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 401 Unauthorized\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body".toByteArray())
                        flush()
                    }
                }
            } catch (failure: Throwable) { serverFailure.set(failure); received.countDown() }
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.findViewById<EditText>(R.id.pair_address).setText("http://127.0.0.1:${server.localPort}")
                    activity.findViewById<EditText>(R.id.pair_code).setText("TEST-EXPIRED")
                    activity.findViewById<Switch>(R.id.pair_allow_http).performClick()
                    activity.findViewById<Button>(R.id.pair_submit).performClick()
                }
                assertTrue("Pair request did not reach the local fixture", received.await(10, TimeUnit.SECONDS))
                assertNull(serverFailure.get())
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertFalse("Rotation made an in-flight request submit again", activity.findViewById<Button>(R.id.pair_submit).isEnabled)
                    assertFalse(activity.findViewById<EditText>(R.id.pair_address).isEnabled)
                    assertEquals("", activity.findViewById<EditText>(R.id.pair_code).text.toString())
                }
                releaseResponse.countDown()
                eventually("Pairing error did not reach the recreated page") {
                    var retryable = false
                    scenario.onActivity { activity ->
                        val error = activity.findViewById<TextView>(R.id.pair_error)
                        retryable = error.isShown && error.text.contains("HTTP 401") &&
                            activity.findViewById<Button>(R.id.pair_submit).isEnabled &&
                            activity.findViewById<EditText>(R.id.pair_address).isEnabled
                    }
                    retryable
                }
                assertNull(prefs.identity)
            }
        } finally {
            releaseResponse.countDown(); server.close(); worker.join(2000)
        }
        assertNull(serverFailure.get())
    }

    @Test fun accessControlsPersistImmediatelyAndReflectChangesFromOutsideThePage() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<Button>(R.id.nav_access).performClick()
                activity.findViewById<Switch>(R.id.pause_all).performClick()
                assertTrue(prefs.policy().pausedAll)
                assertTrue(activity.findViewById<TextView>(R.id.access_state).text.contains("全部访问已暂停"))
                activity.findViewById<Switch>(R.id.pause_operations).performClick()
                assertTrue(prefs.policy().pausedOperations)
                activity.findViewById<Switch>(R.id.cap_ui).performClick()
                assertEquals(setOf(Capability.UI), prefs.policy().capabilities)
                assertTrue(prefs.policy().pausedAll)
                activity.findViewById<Button>(R.id.capabilities_enable_all).performClick()
                assertEquals(Capability.entries.toSet(), prefs.policy().capabilities)
                assertTrue(prefs.policy().pausedAll); assertTrue(prefs.policy().pausedOperations)
                assertTrue("Capability toggle changed system or app grants", prefs.policy().apps.isEmpty())
            }
            // Notification actions and another page can update the same settings while visible.
            prefs.setFlag("pausedAll", false)
            prefs.setFlag("pausedOperations", false)
            prefs.setCapabilities(false)
            eventually("The page did not refresh externally changed permission state") {
                var synced = false
                scenario.onActivity { activity ->
                    synced = !activity.findViewById<Switch>(R.id.pause_all).isChecked &&
                        !activity.findViewById<Switch>(R.id.pause_operations).isChecked &&
                        !activity.findViewById<Switch>(R.id.cap_ui).isChecked &&
                        !activity.findViewById<TextView>(R.id.access_state).text.contains("已暂停")
                }
                synced
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertChrome(activity, R.id.nav_access)
                assertFalse(activity.findViewById<Switch>(R.id.pause_all).isChecked)
                assertFalse(activity.findViewById<Switch>(R.id.cap_ui).isChecked)
            }
        }
    }

    @Test fun manualPackageValidationAndSingleAppSettingsRespectSaveAndCancel() {
        val manual = "com.example.manual"
        ActivityScenario.launch(AppPermissionsActivity::class.java).use { scenario ->
            eventually("Application list did not load") {
                var loaded = false
                scenario.onActivity { loaded = it.findViewById<Button>(R.id.grant_everything).isEnabled }
                loaded
            }
            scenario.onActivity { it.findViewById<Button>(R.id.add_app_package).performClick() }
            enterPackage(context.packageName)
            dialogAction("配置")
            eventually("Self-package validation was not shown inline") {
                instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("请输入其他应用的有效包名")?.isNotEmpty() == true
            }
            assertFalse(prefs.policy().apps.containsKey(context.packageName))
            enterPackage(manual)
            dialogAction("配置")
            eventually("Single-app read control was unavailable") { nodeById("app_read") != null }
            assertFalse("Opening the editor must not save access", prefs.policy().apps.containsKey(manual))
            assertTrue(nodeById("app_read")!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            dialogAction("取消")
            assertFalse("Cancelled access was saved", prefs.policy().apps.containsKey(manual))
            scenario.onActivity { it.findViewById<Button>(R.id.add_app_package).performClick() }
            enterPackage(manual); dialogAction("配置")
            eventually("Single-app read control was unavailable") { nodeById("app_read") != null }
            assertTrue(nodeById("app_read")!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            dialogAction("保存")
            assertEquals(AppAccess(read = true), prefs.policy().apps[manual])
            assertEquals(emptySet<Capability>(), prefs.policy().capabilities)
            scenario.onActivity { activity ->
                assertEquals(manual, activity.findViewById<EditText>(R.id.app_permission_search).text.toString())
                val list = activity.findViewById<ListView>(R.id.app_permission_list)
                assertEquals(1, list.adapter.count - list.headerViewsCount)
            }
        }
    }

    @Test fun touchFeedbackCancelsWithoutCommittingAndHonorsRemovedAnimations() {
        val oldScale = shell("settings get global animator_duration_scale")
        fun motion(button: Button, action: Int) {
            val now = android.os.SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(now, now, action, button.width / 2f, button.height / 2f, 0)
            try { assertTrue(button.dispatchTouchEvent(event)) } finally { event.recycle() }
        }
        try {
            shell("settings put global animator_duration_scale 1")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                instrumentation.waitForIdleSync()
                val pressed = CountDownLatch(1)
                val pressedAt = AtomicLong()
                val pressSettled = CountDownLatch(1)
                val pressSettledAt = AtomicLong()
                val restored = CountDownLatch(1)
                val restoredAt = AtomicLong()
                fun observe(button: Button, started: Long, done: CountDownLatch, elapsed: AtomicLong, condition: () -> Boolean) {
                    button.postOnAnimation(object : Runnable {
                        override fun run() {
                            if (condition()) { elapsed.set(android.os.SystemClock.uptimeMillis() - started); done.countDown() }
                            else if (android.os.SystemClock.uptimeMillis() - started < 2000) button.postOnAnimation(this)
                        }
                    })
                }
                scenario.onActivity {
                    val button = it.findViewById<Button>(R.id.nav_access)
                    val down = android.os.SystemClock.uptimeMillis()
                    motion(button, MotionEvent.ACTION_DOWN)
                    assertEquals("Press feedback animation duration changed", 100L, button.animate().duration)
                    observe(button, down, pressed, pressedAt) { button.scaleX < .999f }
                    observe(button, down, pressSettled, pressSettledAt) { button.scaleX == .97f }
                }
                assertTrue("Touch-down never produced scale feedback", pressed.await(3, TimeUnit.SECONDS))
                assertTrue("Touch-down never reached its target scale", pressSettled.await(3, TimeUnit.SECONDS))
                scenario.onActivity { activity ->
                    val button = activity.findViewById<Button>(R.id.nav_access)
                    assertEquals(.97f, button.scaleX, .005f)
                    assertTrue("Touch-down committed navigation before release", activity.findViewById<Button>(R.id.nav_connection).isSelected)
                    val released = android.os.SystemClock.uptimeMillis()
                    motion(button, MotionEvent.ACTION_CANCEL)
                    assertEquals("Cancel feedback animation duration changed", 160L, button.animate().duration)
                    // Isolate the next click from the preceding return animation's final frames.
                    observe(button, released, restored, restoredAt) { button.scaleX == 1f }
                }
                assertTrue("Cancelled press never returned to its resting scale", restored.await(3, TimeUnit.SECONDS))
                scenario.onActivity { activity ->
                    assertTrue("Cancelled press committed navigation", activity.findViewById<Button>(R.id.nav_connection).isSelected)
                    activity.findViewById<Button>(R.id.nav_access).performClick()
                    assertEquals("Accessibility click should stay immediate", 1f, activity.findViewById<Button>(R.id.nav_access).scaleX, 0f)
                }
                shell("settings put global animator_duration_scale 0")
                scenario.onActivity { activity ->
                    motion(activity.findViewById(R.id.nav_settings), MotionEvent.ACTION_DOWN)
                    assertEquals("Remove animations still scaled on press", 1f, activity.findViewById<Button>(R.id.nav_settings).scaleX, 0f)
                    motion(activity.findViewById(R.id.nav_settings), MotionEvent.ACTION_CANCEL)
                    assertEquals(1f, activity.findViewById<Button>(R.id.nav_settings).scaleX, 0f)
                    assertTrue(activity.findViewById<Button>(R.id.nav_access).isSelected)
                }
                println("Touch feedback: first scale ${pressedAt.get()}ms; press settled ${pressSettledAt.get()}ms; cancel settled ${restoredAt.get()}ms")
            }
        } finally {
            shell(if (oldScale == "null") "settings delete global animator_duration_scale" else "settings put global animator_duration_scale $oldScale")
        }
    }

    /** Explicit opt-in only. Renders this app's own view tree while preserving FLAG_SECURE. */
    @Test fun captureUnpairedScreensForVisualReview() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("captureUi") == "true")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            listOf(R.id.nav_connection to "connection", R.id.nav_access to "access", R.id.nav_settings to "settings").forEach { (id, name) ->
                scenario.onActivity { activity ->
                    activity.findViewById<Button>(id).performClick()
                }
                instrumentation.waitForIdleSync()
                Thread.sleep(150)
                scenario.onActivity { assertChrome(it, id); drawFixture(it, name) }
            }
        }
        prefs.setApps(mapOf("com.example.alpha" to AppAccess(read = true), "com.example.bravo" to AppAccess(operate = true)))
        ActivityScenario.launch(AppPermissionsActivity::class.java).use { scenario ->
            eventually("Application list did not load for capture") {
                var loaded = false
                scenario.onActivity { loaded = it.findViewById<Button>(R.id.grant_everything).isEnabled }
                loaded
            }
            scenario.onActivity { activity ->
                activity.findViewById<EditText>(R.id.app_permission_search).setText("com.example")
                activity.findViewById<Button>(R.id.select_filtered_apps).performClick()
                if (InstrumentationRegistry.getArguments().getString("keyboardUi") == "true") {
                    val search = activity.findViewById<EditText>(R.id.app_permission_search)
                    search.requestFocus()
                    activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                        .showSoftInput(search, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }
            }
            instrumentation.waitForIdleSync(); Thread.sleep(500)
            scenario.onActivity {
                drawFixture(it, "apps")
                val viewport = it.findViewById<ListView>(R.id.app_permission_list)
                val heightDp = viewport.height / it.resources.displayMetrics.density
                val configuration = it.resources.configuration
                val geometry = "${heightDp}dp; fontScale=${configuration.fontScale}; window=${configuration.screenWidthDp}x${configuration.screenHeightDp}dp"
                println("Application viewport: $geometry")
                assertTrue("Application list viewport is no longer usable: $geometry", viewport.height >= 80 * it.resources.displayMetrics.density)
            }
            scenario.onActivity { activity ->
                val list = activity.findViewById<ListView>(R.id.app_permission_list)
                list.setSelection(list.headerViewsCount)
            }
            instrumentation.waitForIdleSync(); Thread.sleep(150)
            scenario.onActivity { activity ->
                val list = activity.findViewById<ListView>(R.id.app_permission_list)
                val visibleApp = (0 until list.childCount).map(list::getChildAt).any { child ->
                    val visible = Rect()
                    list.getPositionForView(child) >= list.headerViewsCount && child.getGlobalVisibleRect(visible) &&
                        visible.height() >= 48 * activity.resources.displayMetrics.density
                }
                assertTrue("Could not scroll from the controls header to an actual application", visibleApp)
                drawFixture(activity, "apps-results")
                list.setSelection(0)
            }
            instrumentation.waitForIdleSync(); Thread.sleep(150)
            listOf(R.id.clear_selected_apps, R.id.select_filtered_apps, R.id.batch_app_permissions, R.id.grant_everything, R.id.add_app_package).forEach { id ->
                scenario.onActivity { activity ->
                    val control = activity.findViewById<Button>(id)
                    control.requestRectangleOnScreen(Rect(0, 0, control.width, control.height), true)
                }
                instrumentation.waitForIdleSync(); Thread.sleep(100)
                scenario.onActivity { activity ->
                    val control = activity.findViewById<Button>(id)
                    val visible = Rect()
                    assertTrue("Application control ${activity.resources.getResourceEntryName(id)} is unreachable", control.getGlobalVisibleRect(visible) && visible.height() >= control.height)
                    assertTrue(control.performClick())
                    if (id == R.id.clear_selected_apps) assertFalse(activity.findViewById<Button>(R.id.batch_app_permissions).isEnabled)
                    if (id == R.id.select_filtered_apps) assertTrue(activity.findViewById<Button>(R.id.batch_app_permissions).isEnabled)
                }
                if (id == R.id.batch_app_permissions || id == R.id.add_app_package) dialogAction("取消")
                if (id == R.id.grant_everything) {
                    assertEquals(Capability.entries.toSet(), prefs.policy().capabilities)
                    assertTrue(prefs.policy().apps.values.all { it == AppAccess(true, true) })
                }
            }
        }
    }
}
