package io.github.hsd221.phonebridge

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.graphics.ImageFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import io.github.hsd221.phonebridge.ui.BridgeUi
import io.github.hsd221.phonebridge.ui.ButtonKind
import java.util.concurrent.atomic.AtomicBoolean

/** Camera2 + ZXing core: local frames only, no photos, model downloads, or Google services. */
class QrScannerActivity : Activity(), TextureView.SurfaceTextureListener {
    private lateinit var ui: BridgeUi
    private lateinit var preview: TextureView
    private lateinit var status: TextView
    private var resumed = false
    private var opening = false
    private var generation = 0
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var images: ImageReader? = null
    private var previewSurface: Surface? = null
    private var worker: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var previewSize = Size(640, 480)
    private var sensorOrientation = 90
    private var frontFacing = false
    private var autofocus = CaptureRequest.CONTROL_AF_MODE_OFF
    private val found = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = BridgeUi(this)
        val content = ui.column().apply { setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(12)) }
        ui.heading(content, getString(R.string.scan_pairing), getString(R.string.scan_pairing_detail))
        val frame = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        preview = TextureView(this).apply { surfaceTextureListener = this@QrScannerActivity }
        frame.addView(preview, FrameLayout.LayoutParams(-1, -1))
        content.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        status = ui.text(getString(R.string.scan_pairing_hint), 14f).apply {
            id = R.id.scan_status; accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content.addView(status, ui.gap(top = 12, bottom = 8))
        ui.addButton(content, getString(R.string.cancel), ButtonKind.SECONDARY, R.id.scan_cancel) { finish() }
        val root = FrameLayout(this).apply { addView(content, FrameLayout.LayoutParams(-1, -1)) }
        ui.attach(this, root)
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION)
        }
    }

    override fun onResume() {
        super.onResume(); resumed = true
        worker = HandlerThread("pairing-qr").also { it.start(); workerHandler = Handler(it.looper) }
        openCamera()
    }
    override fun onPause() {
        resumed = false; closeCamera()
        worker?.quitSafely(); worker = null; workerHandler = null
        super.onPause()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) openCamera()
            else status.setText(R.string.scan_camera_permission_denied)
        }
    }

    @SuppressLint("MissingPermission") // Checked here and requested only after the user opens the scanner.
    private fun openCamera() {
        if (!resumed || !preview.isAvailable || opening || camera != null || found.get() ||
            checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val manager = getSystemService(CameraManager::class.java)
        try {
            val id = manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: manager.cameraIdList.firstOrNull() ?: throw IllegalStateException()
            val info = manager.getCameraCharacteristics(id)
            sensorOrientation = info.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            frontFacing = info.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            val focusModes = info.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
            autofocus = if (CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE in focusModes) CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                else CaptureRequest.CONTROL_AF_MODE_OFF
            val map = info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: throw IllegalStateException()
            val previewSizes = map.getOutputSizes(SurfaceTexture::class.java).toList()
            val scanSizes = map.getOutputSizes(ImageFormat.YUV_420_888).toList()
            val common = previewSizes.filter { it in scanSizes }
            previewSize = (common.filter { it.width <= 1280 && it.height <= 960 }.maxByOrNull { it.width * it.height }
                ?: common.minByOrNull { it.width * it.height }) ?: throw IllegalStateException()
            opening = true
            val current = ++generation
            // Camera callbacks use the main looper; only frame decoding runs on the worker thread.
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (!resumed || current != generation) { device.close(); return }
                    opening = false; camera = device; startPreview(device, current)
                }
                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    if (current == generation) cameraError()
                }
                override fun onError(device: CameraDevice, error: Int) = onDisconnected(device)
            }, null)
        } catch (_: Exception) { cameraError() }
    }

    @Suppress("DEPRECATION") // The API 30-compatible Camera2 session API also works on newer devices.
    private fun startPreview(device: CameraDevice, current: Int) {
        try {
            val texture = preview.surfaceTexture ?: throw IllegalStateException()
            texture.setDefaultBufferSize(previewSize.width, previewSize.height)
            configureTransform(preview.width, preview.height)
            val surface = Surface(texture).also { previewSurface = it }
            val reader = ImageReader.newInstance(previewSize.width, previewSize.height, ImageFormat.YUV_420_888, 2)
            images = reader
            val decoder = QRCodeReader()
            var lastFrame = 0L
            reader.setOnImageAvailableListener({ source ->
                val image = try { source.acquireLatestImage() } catch (_: IllegalStateException) { null }
                    ?: return@setOnImageAvailableListener
                image.use {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (found.get() || now - lastFrame < 200) return@setOnImageAvailableListener
                    lastFrame = now
                    val plane = image.planes[0]
                    val buffer = plane.buffer
                    val offset = buffer.position()
                    val width = image.width; val height = image.height
                    val luminance = ByteArray(width * height)
                    for (y in 0 until height) for (x in 0 until width) {
                        luminance[y * width + x] = buffer.get(offset + y * plane.rowStride + x * plane.pixelStride)
                    }
                    try {
                        val bitmap = BinaryBitmap(HybridBinarizer(PlanarYUVLuminanceSource(luminance, width, height, 0, 0, width, height, false)))
                        val raw = decoder.decode(bitmap, mapOf(DecodeHintType.TRY_HARDER to true)).text
                        if (raw.length <= 4096 && found.compareAndSet(false, true)) runOnUiThread {
                            if (resumed && current == generation) {
                                setResult(RESULT_OK, Intent().putExtra(RESULT_QR, raw)); finish()
                            } else { found.set(false); openCamera() }
                        }
                    } catch (_: ReaderException) { /* Keep scanning; frames are never retained. */ }
                    finally { decoder.reset() }
                }
            }, workerHandler)
            device.createCaptureSession(listOf(surface, reader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(configured: CameraCaptureSession) {
                    if (!resumed || current != generation || camera !== device) { configured.close(); return }
                    session = configured
                    try {
                        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(surface); addTarget(reader.surface)
                            set(CaptureRequest.CONTROL_AF_MODE, autofocus)
                        }
                        configured.setRepeatingRequest(request.build(), null, null)
                    } catch (_: Exception) { cameraError() }
                }
                override fun onConfigureFailed(configured: CameraCaptureSession) {
                    configured.close()
                    if (current == generation) cameraError()
                }
            }, null)
        } catch (_: Exception) { cameraError() }
    }

    private fun cameraError() { closeCamera(); if (resumed) status.setText(R.string.scan_camera_unavailable) }
    private fun closeCamera() {
        generation++; opening = false
        session?.close(); session = null
        camera?.close(); camera = null
        images?.let { reader ->
            reader.setOnImageAvailableListener(null, null)
            // Closing on the decoder's looper lets its current Image finish before invalidation.
            if (workerHandler?.post { reader.close() } != true) reader.close()
        }
        images = null
        previewSurface?.release(); previewSurface = null
    }

    private fun configureTransform(width: Int, height: Int) {
        if (width == 0 || height == 0) return
        val rotation = (display?.rotation ?: Surface.ROTATION_0) * 90
        val degrees = if (frontFacing) (sensorOrientation + rotation) % 360 else (sensorOrientation - rotation + 360) % 360
        val rotated = degrees % 180 != 0
        val orientedWidth = if (rotated) previewSize.height else previewSize.width
        val orientedHeight = if (rotated) previewSize.width else previewSize.height
        val scale = maxOf(width.toFloat() / orientedWidth, height.toFloat() / orientedHeight)
        val matrix = Matrix().apply {
            // Undo TextureView's default stretch, rotate sensor pixels, then center-crop.
            setScale(previewSize.width.toFloat() / width, previewSize.height.toFloat() / height)
            postTranslate(-previewSize.width / 2f, -previewSize.height / 2f)
            postRotate(degrees.toFloat())
            postScale(if (frontFacing) -scale else scale, scale)
            postTranslate(width / 2f, height / 2f)
        }
        preview.setTransform(matrix)
    }
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) { openCamera() }
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) { configureTransform(width, height) }
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean { closeCamera(); return true }
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    companion object {
        const val RESULT_QR = "pairing_qr"
        private const val CAMERA_PERMISSION = 21
    }
}
