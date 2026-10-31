package io.github.hsd221.phonebridge

import android.app.Activity
import android.app.AlertDialog
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.os.Build
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Runs only on an explicitly selected, unpaired test emulator; never contacts GitHub. */
@RunWith(AndroidJUnit4::class)
class AppUpdateUiTest {
    @get:Rule val appUpdates = AppUpdateFixture()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val prefs = BridgePrefs(instrumentation.targetContext)
    private var prepared = false

    @Before fun prepare() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("permissionUiTest") == "true")
        assertTrue("Update UI tests require a dedicated emulator", Build.HARDWARE in setOf("ranchu", "goldfish"))
        assertNull("Update UI tests refuse to replace a paired identity", prefs.identity)
        assertNull(prefs.pendingRevocation)
        assertTrue(prefs.preferences.edit().clear().commit())
        prepared = true
    }

    @After fun cleanup() {
        if (prepared) assertTrue(prefs.preferences.edit().clear().commit())
    }

    private fun release(tag: String = "v9.0.0-pre.10") = AppRelease(
        tag, requireNotNull(AppVersion.parse(tag)),
        "https://github.com/hsd221/android-phone-bridge/releases/download/$tag/phone-bridge-${tag.removePrefix("v")}.apk",
        "新增检查更新与下载入口。",
    )

    private fun dialog(activity: MainActivity): AlertDialog? =
        MainActivity::class.java.getDeclaredField("updateDialog").apply { isAccessible = true }.get(activity) as? AlertDialog

    private fun generation(activity: MainActivity) = (activity.application as BridgeApplication).foregroundGeneration

    private fun eventually(description: String, condition: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 10000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        fail(description)
    }

    @Test fun manualCheckSurvivesRecreationAndDownloadsOnlyAfterUserAction() {
        val calls = AtomicInteger()
        val result = CompletableDeferred<AppRelease>()
        val opened = AtomicReference<Intent>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_VIEW) return null
                opened.set(Intent(intent))
                return Instrumentation.ActivityResult(Activity.RESULT_OK, null)
            }
        }
        appUpdates.requestFactory = {
            UpdateCheckRequest(load = {
                if (calls.incrementAndGet() == 1) AppUpdateFixture.currentRelease() else result.await()
            })
        }
        instrumentation.addMonitor(monitor)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.findViewById<Button>(R.id.nav_settings).performClick()
                    assertTrue(activity.findViewById<TextView>(R.id.update_current_version).text.contains(BuildConfig.VERSION_NAME))
                    assertEquals("The initial opening must check automatically", 1, calls.get())
                    assertEquals(View.GONE, activity.findViewById<Button>(R.id.download_update).visibility)
                    val check = activity.findViewById<Button>(R.id.check_updates)
                    check.performClick()
                    check.performClick()
                    assertFalse(check.isEnabled)
                    assertTrue(activity.findViewById<TextView>(R.id.update_status).text.contains("正在检查"))
                    assertEquals(2, calls.get())
                    assertNull(opened.get())
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertTrue(activity.findViewById<Button>(R.id.nav_settings).isSelected)
                    assertFalse(activity.findViewById<Button>(R.id.check_updates).isEnabled)
                    assertEquals(2, calls.get())
                }
                val update = release()
                result.complete(update)
                eventually("Update result did not reach the recreated settings page") {
                    var shown = false
                    scenario.onActivity { activity -> shown = activity.findViewById<Button>(R.id.download_update).visibility == View.VISIBLE }
                    shown
                }
                scenario.onActivity { activity ->
                    assertTrue(activity.findViewById<TextView>(R.id.update_status).text.contains(update.tag))
                    assertEquals(update.notes, activity.findViewById<TextView>(R.id.update_notes).text.toString())
                    assertTrue(activity.findViewById<Button>(R.id.check_updates).isEnabled)
                    assertNull("Checking updates must never start a download", opened.get())
                    activity.findViewById<Button>(R.id.download_update).performClick()
                    assertEquals(update.downloadUrl, opened.get().dataString)
                    assertTrue(opened.get().hasCategory(Intent.CATEGORY_BROWSABLE))
                    activity.findViewById<View>(R.id.open_releases).performClick()
                    assertEquals(AppUpdates.RELEASES_URL, opened.get().dataString)
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertEquals(2, calls.get())
                    assertEquals(View.VISIBLE, activity.findViewById<Button>(R.id.download_update).visibility)
                    assertTrue(activity.findViewById<TextView>(R.id.update_status).text.contains(update.tag))
                }
            }
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test fun failuresRemainRetryableAndEqualVersionsDoNotOfferADownload() {
        val calls = AtomicInteger()
        appUpdates.requestFactory = {
            UpdateCheckRequest(currentVersion = "0.3.0-pre.7+installed", load = {
                if (calls.incrementAndGet() == 2) throw UpdateCheckException("测试网络暂时不可用，请重试。")
                release("v0.3.0-pre.7+other-build")
            })
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<Button>(R.id.nav_settings).performClick()
                val check = activity.findViewById<Button>(R.id.check_updates)
                check.performClick()
                assertTrue(check.isEnabled)
                assertTrue(activity.findViewById<TextView>(R.id.update_status).text.contains("请重试"))
                assertEquals(View.GONE, activity.findViewById<Button>(R.id.download_update).visibility)
                check.performClick()
                assertEquals(3, calls.get())
                assertEquals("已是最新版本", activity.findViewById<TextView>(R.id.update_status).text.toString())
                assertEquals(View.GONE, activity.findViewById<Button>(R.id.download_update).visibility)
                assertEquals(View.GONE, activity.findViewById<TextView>(R.id.update_notes).visibility)
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals(3, calls.get())
                assertEquals("已是最新版本", activity.findViewById<TextView>(R.id.update_status).text.toString())
                assertTrue(activity.findViewById<Button>(R.id.check_updates).isEnabled)
            }
        }
    }

    @Test fun finishingTheActivityCancelsItsPendingCheck() {
        val cancelled = CountDownLatch(1)
        val result = CompletableDeferred<AppRelease>()
        lateinit var request: UpdateCheckRequest
        appUpdates.requestFactory = {
            UpdateCheckRequest(load = { try { result.await() } finally { cancelled.countDown() } }).also { request = it }
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<Button>(R.id.nav_settings).performClick()
                assertTrue(request.busy)
            }
        }
        assertTrue("Finishing the Activity leaked the in-flight update check", cancelled.await(5, TimeUnit.SECONDS))
        assertNull(request.changed)
        assertNull(request.release)
        assertNull(prefs.identity)
    }

    @Test fun automaticCheckAndItsPromptSurviveRotationAndLaterDismissal() {
        val calls = AtomicInteger()
        val result = CompletableDeferred<AppRelease>()
        lateinit var request: UpdateCheckRequest
        appUpdates.requestFactory = {
            UpdateCheckRequest(load = { calls.incrementAndGet(); result.await() }).also { request = it }
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var opening = 0
            scenario.onActivity { activity ->
                opening = generation(activity)
                assertEquals(1, calls.get())
                assertTrue(request.busy)
                assertNull(dialog(activity))
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals(opening, generation(activity))
                assertEquals(1, calls.get())
                assertTrue(request.busy)
            }
            result.complete(release())
            eventually("Automatic check did not show its update prompt") {
                var shown = false
                scenario.onActivity { shown = dialog(it)?.isShowing == true }
                shown
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals(opening, generation(activity))
                assertEquals(1, calls.get())
                val prompt = requireNotNull(dialog(activity))
                assertTrue(prompt.isShowing)
                assertEquals(release().notes, prompt.findViewById<TextView>(android.R.id.message).text.toString())
                prompt.findViewById<Button>(R.id.update_prompt_later).performClick()
            }
            instrumentation.waitForIdleSync()
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals(1, calls.get())
                assertNull(request.pendingPrompt)
                assertNull(dialog(activity))
            }
        }
    }

    @Test fun automaticPromptDownloadsOnlyWhenTheUserChoosesIt() {
        val update = release()
        val opened = AtomicReference<Intent>()
        appUpdates.requestFactory = { UpdateCheckRequest(load = { update }) }
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_VIEW) return null
                opened.set(Intent(intent))
                return Instrumentation.ActivityResult(Activity.RESULT_OK, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    assertNull("Automatic checks must wait for a download choice", opened.get())
                    requireNotNull(dialog(activity)).findViewById<Button>(R.id.update_prompt_download).performClick()
                }
                eventually("The update choice did not open its download intent") { opened.get() != null }
                assertEquals(update.downloadUrl, opened.get().dataString)
                assertTrue(opened.get().hasCategory(Intent.CATEGORY_BROWSABLE))
            }
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test fun genuineForegroundOpeningsCheckAgainButInternalNavigationDoesNot() {
        val calls = AtomicInteger()
        appUpdates.requestFactory = { UpdateCheckRequest(load = { calls.incrementAndGet(); AppUpdateFixture.currentRelease() }) }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var opening = 0
            scenario.onActivity { activity ->
                opening = generation(activity)
                assertEquals(1, calls.get())
                activity.startActivity(Intent(activity, AppPermissionsActivity::class.java))
            }
            eventually("Application permissions page did not resume") {
                var resumed = false
                instrumentation.runOnMainSync {
                    val permissions = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<AppPermissionsActivity>().firstOrNull()
                    resumed = permissions != null
                    permissions?.finish()
                }
                resumed
            }
            eventually("Main page did not resume from internal navigation") { scenario.state == Lifecycle.State.RESUMED }
            scenario.onActivity { activity ->
                assertEquals(opening, generation(activity))
                assertEquals(1, calls.get())
            }
            repeat(2) { index ->
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                scenario.onActivity { activity ->
                    assertEquals(opening + index + 1, generation(activity))
                    assertEquals(index + 2, calls.get())
                    assertNull(dialog(activity))
                }
            }
        }
    }

    @Test fun backgroundResultsWaitForForegroundEvenWhileTheNextCheckIsPending() {
        val calls = AtomicInteger()
        val first = CompletableDeferred<AppRelease>()
        val second = CompletableDeferred<AppRelease>()
        lateinit var request: UpdateCheckRequest
        appUpdates.requestFactory = {
            UpdateCheckRequest(load = { if (calls.incrementAndGet() == 1) first.await() else second.await() }).also { request = it }
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.CREATED)
            first.complete(release())
            eventually("Background check result was not retained") {
                var pending = false
                scenario.onActivity { pending = request.pendingPrompt != null }
                pending
            }
            scenario.onActivity { assertNull("Background completion opened a window", dialog(it)) }
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { activity ->
                assertEquals(2, calls.get())
                assertTrue("The new foreground must still check again", request.busy)
                val prompt = requireNotNull(dialog(activity))
                assertTrue("The known update was lost when a new request started", prompt.isShowing)
                prompt.findViewById<Button>(R.id.update_prompt_later).performClick()
            }
            instrumentation.waitForIdleSync()
            second.complete(release())
            eventually("Second foreground check did not finish") {
                var done = false
                scenario.onActivity { done = !request.busy }
                done
            }
            scenario.onActivity { activity ->
                assertNull("Choosing later was undone by the already-running check", request.pendingPrompt)
                assertNull("The same opening prompted again after choosing later", dialog(activity))
            }
        }
    }

    @Test fun automaticFailuresAndCurrentVersionsStayQuietAndBackCancelsThePrompt() {
        val calls = AtomicInteger()
        appUpdates.requestFactory = {
            UpdateCheckRequest(load = {
                when (calls.incrementAndGet()) {
                    1 -> throw UpdateCheckException("测试网络暂时不可用，请重试。")
                    2 -> AppUpdateFixture.currentRelease()
                    else -> release()
                }
            })
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(1, calls.get())
                assertNull(dialog(activity))
                activity.findViewById<Button>(R.id.nav_settings).performClick()
                assertTrue(activity.findViewById<TextView>(R.id.update_status).text.contains("请重试"))
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { activity ->
                assertEquals(2, calls.get())
                assertNull(dialog(activity))
                assertEquals("已是最新版本", activity.findViewById<TextView>(R.id.update_status).text.toString())
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { activity -> requireNotNull(dialog(activity)).cancel() }
            instrumentation.waitForIdleSync()
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals(3, calls.get())
                assertNull(dialog(activity))
            }
        }
    }

    @Test fun reopeningAnInternalPageChecksAndWaitsForMainBeforePrompting() {
        val calls = AtomicInteger()
        lateinit var request: UpdateCheckRequest
        appUpdates.requestFactory = {
            UpdateCheckRequest(load = {
                if (calls.incrementAndGet() == 1) AppUpdateFixture.currentRelease() else release()
            }).also { request = it }
        }
        ActivityScenario.launch(MainActivity::class.java).use { main ->
            var opening = 0
            main.onActivity { activity ->
                opening = generation(activity)
                assertEquals(1, calls.get())
                activity.startActivity(Intent(activity, AppPermissionsActivity::class.java))
            }
            var permissions: AppPermissionsActivity? = null
            eventually("Internal page did not resume") {
                instrumentation.runOnMainSync {
                    permissions = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<AppPermissionsActivity>().firstOrNull()
                }
                permissions != null
            }
            val child = requireNotNull(permissions)
            try {
                assertEquals(1, calls.get())
                instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                    .executeShellCommand("input keyevent KEYCODE_HOME").use {
                        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
                    }
                eventually("Home did not stop the internal page") {
                    var stopped = false
                    instrumentation.runOnMainSync {
                        stopped = child in ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.STOPPED)
                    }
                    stopped
                }
                instrumentation.runOnMainSync {
                    instrumentation.targetContext.startActivity(Intent(instrumentation.targetContext, AppPermissionsActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                }
                eventually("The existing internal page did not resume") {
                    var resumed = false
                    instrumentation.runOnMainSync {
                        resumed = child in ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    }
                    resumed
                }
                instrumentation.runOnMainSync {
                    assertEquals(opening + 1, (child.application as BridgeApplication).foregroundGeneration)
                    assertEquals(2, calls.get())
                    assertNotNull(request.pendingPrompt)
                }
                main.onActivity { assertNull("An update prompt interrupted the internal page", dialog(it)) }
            } finally {
                instrumentation.runOnMainSync { if (!child.isDestroyed) child.finish() }
            }
            eventually("Main did not resume after closing the internal page") { main.state == Lifecycle.State.RESUMED }
            main.onActivity { activity ->
                assertEquals(2, calls.get())
                assertEquals(opening + 1, generation(activity))
                requireNotNull(dialog(activity)).findViewById<Button>(R.id.update_prompt_later).performClick()
            }
        }
    }
}
