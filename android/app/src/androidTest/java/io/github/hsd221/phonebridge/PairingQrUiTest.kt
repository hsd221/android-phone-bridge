package io.github.hsd221.phonebridge

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Build
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.graphics.Bitmap
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.google.zxing.BinaryBitmap
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/** Explicit opt-in, dedicated unpaired emulator only. No Bridge, GitHub or camera images are uploaded. */
@RunWith(AndroidJUnit4::class)
class PairingQrUiTest {
    @get:Rule val appUpdates = AppUpdateFixture()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefs = BridgePrefs(context)
    private var prepared = false

    @Before fun prepare() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("pairingQrUiTest") == "true")
        assertTrue("QR UI tests require a dedicated emulator", Build.HARDWARE in setOf("ranchu", "goldfish"))
        assertNull("QR UI tests refuse to replace a paired identity", prefs.identity)
        assertNull(prefs.pendingRevocation)
        assertTrue(prefs.preferences.edit().clear().commit())
        prepared = true
    }

    @After fun cleanup() {
        if (prepared) assertTrue(prefs.preferences.edit().clear().commit())
    }

    private fun payload(url: String = "https://bridge.example.com", expiry: Long = System.currentTimeMillis() + 300000) = JSONObject()
        .put("type", "phone-bridge-pair").put("version", 1).put("url", url)
        .put("code", "0123456789ABCDEF").put("expiresAt", expiry).toString()

    private fun consume(activity: MainActivity, raw: String) = MainActivity::class.java
        .getDeclaredMethod("consumePairingQr", String::class.java).apply { isAccessible = true }.invoke(activity, raw)

    private fun dialog(activity: MainActivity): AlertDialog? = MainActivity::class.java
        .getDeclaredField("pairingQrDialog").apply { isAccessible = true }.get(activity) as? AlertDialog

    private fun cameraField(activity: QrScannerActivity, name: String): Any? = QrScannerActivity::class.java
        .getDeclaredField(name).apply { isAccessible = true }.get(activity)

    private fun eventually(description: String, condition: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 15000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        fail(description)
    }

    /** Opt-in diagnostics for a synthetic emulator camera fixture; never part of the main APK. */
    private fun captureCameraFixture() {
        if (InstrumentationRegistry.getArguments().getString("pairingQrCapture") != "true") return
        val captured = CountDownLatch(1)
        instrumentation.runOnMainSync {
            val scanner = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<QrScannerActivity>().firstOrNull() ?: return@runOnMainSync
            println("QR fixture camera=${cameraField(scanner, "camera") != null} session=${cameraField(scanner, "session") != null} status=${scanner.findViewById<TextView>(R.id.scan_status).text}")
            (cameraField(scanner, "preview") as? TextureView)?.bitmap?.let { bitmap ->
                File(context.cacheDir, "pairing-qr-preview-fixture.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            (cameraField(scanner, "images") as? ImageReader)?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                image.use {
                    val plane = image.planes[0]; val buffer = plane.buffer; val offset = buffer.position()
                    val width = image.width; val height = image.height
                    val luminance = ByteArray(width * height)
                    for (y in 0 until height) for (x in 0 until width) luminance[y * width + x] = buffer.get(offset + y * plane.rowStride + x * plane.pixelStride)
                    val pixels = IntArray(luminance.size) { index ->
                        val value = luminance[index].toInt() and 255
                        0xFF000000.toInt() or (value shl 16) or (value shl 8) or value
                    }
                    val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
                    File(context.cacheDir, "pairing-qr-yuv-fixture.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                    val decoded = runCatching {
                        QRCodeReader().decode(BinaryBitmap(HybridBinarizer(PlanarYUVLuminanceSource(luminance, width, height, 0, 0, width, height, false)))).text
                    }.getOrNull()
                    println("QR fixture frame=${width}x$height decodedPairing=${decoded?.let { runCatching { PairingPayload.parse(it) }.isSuccess } ?: false}")
                }
                reader.setOnImageAvailableListener(null, null); captured.countDown()
            }, Handler(Looper.getMainLooper()))
        }
        captured.await(3, TimeUnit.SECONDS)
    }

    @Test fun confirmingAQrOnlyImportsInputsAndPreservesHttpAndToolConsent() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val address = activity.findViewById<EditText>(R.id.pair_address)
                val code = activity.findViewById<EditText>(R.id.pair_code)
                address.setText("https://draft.example.com")
                code.setText("DRAFT")
                consume(activity, payload())
                val prompt = requireNotNull(dialog(activity))
                assertTrue(prompt.isShowing)
                assertTrue(prompt.findViewById<TextView>(android.R.id.message).text.contains("bridge.example.com"))
                assertFalse(prompt.findViewById<TextView>(android.R.id.message).text.contains("0123456789ABCDEF"))
                assertEquals("https://draft.example.com", address.text.toString())
                assertFalse(prefs.preferences.contains("deviceId"))
                prompt.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                assertEquals("DRAFT", code.text.toString())
                consume(activity, payload("http://192.0.2.10:8787"))
                requireNotNull(dialog(activity)).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val address = activity.findViewById<EditText>(R.id.pair_address)
                val code = activity.findViewById<EditText>(R.id.pair_code)
                assertEquals("http://192.0.2.10:8787", address.text.toString())
                assertEquals("0123456789ABCDEF", code.text.toString())
                assertFalse("QR imported HTTP consent", activity.findViewById<Switch>(R.id.pair_allow_http).isChecked)
                assertNull(prefs.identity)
                assertFalse("QR confirmation issued a pairing request", prefs.preferences.contains("deviceId"))
                assertTrue(prefs.policy().tools.isEmpty())
                assertTrue(prefs.policy().apps.isEmpty())
                assertFalse(code.isSaveEnabled)
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals("http://192.0.2.10:8787", activity.findViewById<EditText>(R.id.pair_address).text.toString())
                assertEquals("", activity.findViewById<EditText>(R.id.pair_code).text.toString())
                assertNull(dialog(activity))
            }
        }
    }

    @Test fun invalidAndExpiredWhileConfirmingQrCodesLeaveDraftsUnchanged() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<EditText>(R.id.pair_address).setText("https://draft.example.com")
                activity.findViewById<EditText>(R.id.pair_code).setText("DRAFT")
                consume(activity, "https://foreign.example.com/?token=not-a-pairing-qr")
                assertNull(dialog(activity))
                assertEquals(View.VISIBLE, activity.findViewById<TextView>(R.id.pair_error).visibility)
                consume(activity, payload(expiry = System.currentTimeMillis() + 1500))
                assertNotNull(dialog(activity))
            }
            Thread.sleep(1700)
            scenario.onActivity { activity ->
                requireNotNull(dialog(activity)).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals("https://draft.example.com", activity.findViewById<EditText>(R.id.pair_address).text.toString())
                assertEquals("DRAFT", activity.findViewById<EditText>(R.id.pair_code).text.toString())
                assertEquals(View.VISIBLE, activity.findViewById<TextView>(R.id.pair_error).visibility)
                assertFalse(prefs.preferences.contains("deviceId"))
                assertNull(prefs.identity)
            }
        }
    }

    @Test fun cameraClosesOnBackgroundAndReopensAfterRecreationAndCancellation() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("pairingQrCameraTest") == "true")
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.CAMERA))
        ActivityScenario.launch(QrScannerActivity::class.java).use { scenario ->
            eventually("Camera2 did not create a capture session") {
                var opened = false
                scenario.onActivity { opened = cameraField(it, "camera") != null && cameraField(it, "session") != null }
                opened
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.onActivity { activity ->
                for (name in listOf("camera", "session", "images", "previewSurface", "worker", "workerHandler")) {
                    assertNull("Camera resource survived onPause: $name", cameraField(activity, name))
                }
            }
            scenario.moveToState(Lifecycle.State.RESUMED)
            eventually("Camera did not reopen after returning from background") {
                var opened = false
                scenario.onActivity { opened = cameraField(it, "session") != null }
                opened
            }
            scenario.recreate()
            eventually("Camera did not reopen after Activity recreation") {
                var opened = false
                scenario.onActivity { opened = cameraField(it, "session") != null }
                opened
            }
            scenario.onActivity { it.findViewById<Button>(R.id.scan_cancel).performClick() }
            eventually("Cancel did not finish the scanner") { scenario.state == Lifecycle.State.DESTROYED }
            assertNull(prefs.identity)
        }
    }

    @Test fun cameraImageQrReturnsToTheSameConfirmationFlow() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("pairingQrImageTest") == "true")
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.findViewById<Button>(R.id.pair_scan).performClick() }
            try {
                eventually("Camera image was not decoded into the pairing confirmation") {
                    var shown = false
                    scenario.onActivity { shown = dialog(it)?.isShowing == true }
                    shown
                }
            } catch (failure: AssertionError) { captureCameraFixture(); throw failure }
            scenario.onActivity { activity ->
                val prompt = requireNotNull(dialog(activity))
                assertTrue(prompt.findViewById<TextView>(android.R.id.message).text.contains("https://bridge.example.com"))
                prompt.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals("https://bridge.example.com", activity.findViewById<EditText>(R.id.pair_address).text.toString())
                assertEquals("0123456789ABCDEF", activity.findViewById<EditText>(R.id.pair_code).text.toString())
                assertNull(prefs.identity)
                assertFalse(prefs.preferences.contains("deviceId"))
            }
        }
    }

    @Test fun denyingCameraPermissionReturnsToUsableManualPairing() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("pairingQrDeniedCameraTest") == "true")
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.CAMERA))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertNull(prefs.identity)
                assertEquals(PackageManager.PERMISSION_DENIED, activity.checkSelfPermission(Manifest.permission.CAMERA))
                activity.findViewById<Button>(R.id.pair_scan).performClick()
            }
            eventually("Camera permission dialog was not shown") {
                instrumentation.uiAutomation.rootInActiveWindow
                    ?.findAccessibilityNodeInfosByViewId("com.android.permissioncontroller:id/permission_deny_button")
                    ?.firstOrNull()?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            }
            eventually("Camera denial did not expose the manual-pairing fallback") {
                var denied = false
                instrumentation.runOnMainSync {
                    val scanner = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<QrScannerActivity>().firstOrNull()
                    val status = scanner?.findViewById<TextView>(R.id.scan_status)
                    denied = status?.text?.toString() == context.getString(R.string.scan_camera_permission_denied)
                    if (denied) scanner?.findViewById<Button>(R.id.scan_cancel)?.performClick()
                }
                denied
            }
            eventually("Cancel did not return from the scanner") { scenario.state == Lifecycle.State.RESUMED }
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<EditText>(R.id.pair_address).isEnabled)
                assertTrue(activity.findViewById<Button>(R.id.pair_submit).isEnabled)
                assertNull(prefs.identity)
                assertFalse(prefs.preferences.contains("deviceId"))
            }
        }
    }
}
