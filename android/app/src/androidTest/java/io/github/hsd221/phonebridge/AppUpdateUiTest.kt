package io.github.hsd221.phonebridge

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Build
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Runs only on an explicitly selected, unpaired test emulator; never contacts GitHub. */
@RunWith(AndroidJUnit4::class)
class AppUpdateUiTest {
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

    // Inject at the existing request boundary without adding an externally callable test hook.
    private fun replaceRequest(activity: MainActivity, request: UpdateCheckRequest) {
        val field = MainActivity::class.java.getDeclaredField("updateCheckRequest").apply { isAccessible = true }
        val original = field.get(activity) as UpdateCheckRequest
        request.changed = original.changed
        original.close()
        field.set(activity, request)
        request.changed?.invoke()
    }

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
        instrumentation.addMonitor(monitor)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    replaceRequest(activity, UpdateCheckRequest(load = { calls.incrementAndGet(); result.await() }))
                    activity.findViewById<Button>(R.id.nav_settings).performClick()
                    assertTrue(activity.findViewById<TextView>(R.id.update_current_version).text.contains(BuildConfig.VERSION_NAME))
                    assertEquals(0, calls.get())
                    assertEquals(View.GONE, activity.findViewById<Button>(R.id.download_update).visibility)
                    val check = activity.findViewById<Button>(R.id.check_updates)
                    check.performClick()
                    check.performClick()
                    assertFalse(check.isEnabled)
                    assertTrue(activity.findViewById<TextView>(R.id.update_status).text.contains("正在检查"))
                    assertEquals(1, calls.get())
                    assertNull(opened.get())
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertTrue(activity.findViewById<Button>(R.id.nav_settings).isSelected)
                    assertFalse(activity.findViewById<Button>(R.id.check_updates).isEnabled)
                    assertEquals(1, calls.get())
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
                    assertEquals(1, calls.get())
                    assertEquals(View.VISIBLE, activity.findViewById<Button>(R.id.download_update).visibility)
                    assertTrue(activity.findViewById<TextView>(R.id.update_status).text.contains(update.tag))
                }
            }
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test fun failuresRemainRetryableAndEqualVersionsDoNotOfferADownload() {
        val calls = AtomicInteger()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                replaceRequest(activity, UpdateCheckRequest(currentVersion = "0.3.0-pre.7+installed", load = {
                    if (calls.incrementAndGet() == 1) throw UpdateCheckException("测试网络暂时不可用，请重试。")
                    release("v0.3.0-pre.7+other-build")
                }))
                activity.findViewById<Button>(R.id.nav_settings).performClick()
                val check = activity.findViewById<Button>(R.id.check_updates)
                check.performClick()
                assertTrue(check.isEnabled)
                assertTrue(activity.findViewById<TextView>(R.id.update_status).text.contains("请重试"))
                assertEquals(View.GONE, activity.findViewById<Button>(R.id.download_update).visibility)
                check.performClick()
                assertEquals(2, calls.get())
                assertEquals("已是最新版本", activity.findViewById<TextView>(R.id.update_status).text.toString())
                assertEquals(View.GONE, activity.findViewById<Button>(R.id.download_update).visibility)
                assertEquals(View.GONE, activity.findViewById<TextView>(R.id.update_notes).visibility)
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals(2, calls.get())
                assertEquals("已是最新版本", activity.findViewById<TextView>(R.id.update_status).text.toString())
                assertTrue(activity.findViewById<Button>(R.id.check_updates).isEnabled)
            }
        }
    }

    @Test fun finishingTheActivityCancelsItsPendingCheck() {
        val cancelled = CountDownLatch(1)
        val result = CompletableDeferred<AppRelease>()
        lateinit var request: UpdateCheckRequest
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                request = UpdateCheckRequest(load = { try { result.await() } finally { cancelled.countDown() } })
                replaceRequest(activity, request)
                activity.findViewById<Button>(R.id.nav_settings).performClick()
                activity.findViewById<Button>(R.id.check_updates).performClick()
                assertTrue(request.busy)
            }
        }
        assertTrue("Finishing the Activity leaked the in-flight update check", cancelled.await(5, TimeUnit.SECONDS))
        assertNull(request.changed)
        assertNull(request.release)
        assertNull(prefs.identity)
    }
}
