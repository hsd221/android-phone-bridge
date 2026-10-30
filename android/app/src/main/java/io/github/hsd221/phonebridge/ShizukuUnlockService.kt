package io.github.hsd221.phonebridge

import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.UiAutomation
import android.content.Context
import android.graphics.Rect
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Narrow shell-UID service: all inspection and input stay in code from this APK. */
@SuppressLint("PrivateApi") // Shizuku shell UserServices explicitly support non-SDK APIs; never used in the app process.
class ShizukuUnlockService(private val context: Context) : ILocalKeyguard.Stub() {
    private val ownerUid = context.applicationInfo.uid
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val ui = KeyguardUi(context)
    private var automation: UiAutomation? = null
    private var thread: HandlerThread? = null
    private var kind: UnlockKind? = null
    private var submission: PinSubmission? = null
    private var prepared = false
    private var inputStarted = false
    private var lastReason = UnlockDiagnostics.Reason.NONE

    override fun uid() = Process.myUid()
    private fun authorized() = uid() == 2000 && Binder.getCallingUid() == ownerUid

    @Synchronized override fun prepare(): Int {
        if (!authorized()) return ShellUnlockResult.UNAVAILABLE
        val identity = Binder.clearCallingIdentity()
        return try {
            if (automation == null) {
                val handlerThread = HandlerThread("local-keyguard").apply { start() }
                thread = handlerThread
                // Shizuku UserServices allow non-SDK APIs. UiAutomation's public
                // inspection/injection APIs use the same shell identity as adb.
                val connectionType = Class.forName("android.app.IUiAutomationConnection")
                val connection = Class.forName("android.app.UiAutomationConnection").getConstructor().newInstance()
                val instance = UiAutomation::class.java.getConstructor(Looper::class.java, connectionType)
                    .newInstance(handlerThread.looper, connection)
                automation = instance
                val timedConnect = runCatching {
                    UiAutomation::class.java.getMethod("connectWithTimeout", Int::class.javaPrimitiveType, Long::class.javaPrimitiveType)
                }.getOrNull()
                if (timedConnect != null) {
                    timedConnect.invoke(instance, UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES, 4000L)
                } else {
                    // Android 11/12 expose the older bounded connect(flags) API.
                    UiAutomation::class.java.getMethod("connect", Int::class.javaPrimitiveType)
                        .invoke(instance, UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                }
                instance.serviceInfo = instance.serviceInfo.apply {
                    flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                        AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                        AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                }
            }
            ShellUnlockResult.OK
        } catch (_: Exception) { release(); ShellUnlockResult.UNAVAILABLE }
        finally { Binder.restoreCallingIdentity(identity) }
    }

    @Synchronized override fun preflight(kind: Int): Int = guarded {
        prepared = false
        inputStarted = false
        submission = null
        val mode = UnlockKind.entries.getOrNull(kind) ?: return@guarded ShellUnlockResult.UNSUPPORTED
        this.kind = mode
        submission = ui.preflight(root(mode), mode)
        prepared = true
        when (submission) {
            PinSubmission.EXPLICIT_ENTER -> ShellUnlockResult.PIN_EXPLICIT
            PinSubmission.AUTO_CONFIRM -> ShellUnlockResult.PIN_AUTO
            null -> ShellUnlockResult.OK
        }
    }

    @Synchronized override fun digit(digit: Int, first: Boolean): Int = guarded {
        if (!prepared || kind != UnlockKind.PIN || digit !in 0..9 || first == inputStarted) return@guarded ShellUnlockResult.INTERRUPTED
        val root = root(UnlockKind.PIN)
        if (ui.entry(root, UnlockKind.PIN)?.isPassword != true) ui.unsupported(UnlockDiagnostics.Reason.ENTRY_NOT_PROTECTED)
        if (first) {
            if (ui.preflight(root, UnlockKind.PIN) != submission) return@guarded ShellUnlockResult.INTERRUPTED
        } else if (submission == PinSubmission.AUTO_CONFIRM) {
            if (!PinAutoConfirmPolicy.canContinue(ui.pinEmptyControls(root), ui.node(root, "delete_button")?.isClickable == true)) {
                return@guarded ShellUnlockResult.INTERRUPTED
            }
        }
        val key = ui.node(root, "key$digit") ?: return@guarded ShellUnlockResult.INTERRUPTED
        if (!key.isClickable || (submission == PinSubmission.AUTO_CONFIRM && key.contentDescription?.toString() != digit.toString())) {
            return@guarded ShellUnlockResult.INTERRUPTED
        }
        inputStarted = true
        tap(key)
    }

    @Synchronized override fun confirmPin(): Int = guarded {
        if (!prepared || !inputStarted || kind != UnlockKind.PIN || submission != PinSubmission.EXPLICIT_ENTER) return@guarded ShellUnlockResult.INTERRUPTED
        val root = root(UnlockKind.PIN)
        if (ui.entry(root, UnlockKind.PIN)?.isPassword != true) return@guarded ShellUnlockResult.INTERRUPTED
        val enter = ui.node(root, "key_enter") ?: return@guarded ShellUnlockResult.INTERRUPTED
        if (!enter.isClickable) return@guarded ShellUnlockResult.INTERRUPTED
        prepared = false
        tap(enter)
    }

    @Synchronized override fun password(value: String): Int = guarded {
        if (!prepared || inputStarted || kind != UnlockKind.PASSWORD) return@guarded ShellUnlockResult.INTERRUPTED
        UnlockCredential(UnlockKind.PASSWORD, value) // Bounds/format only, never retained.
        val root = root(UnlockKind.PASSWORD)
        ui.preflight(root, UnlockKind.PASSWORD)
        val field = ui.entry(root, UnlockKind.PASSWORD) ?: return@guarded ShellUnlockResult.INTERRUPTED
        inputStarted = true
        accepted(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
    }

    @Synchronized override fun confirmPassword(): Int = guarded {
        if (!prepared || !inputStarted || kind != UnlockKind.PASSWORD) return@guarded ShellUnlockResult.INTERRUPTED
        val field = ui.entry(root(UnlockKind.PASSWORD), UnlockKind.PASSWORD) ?: return@guarded ShellUnlockResult.INTERRUPTED
        if (!field.isPassword || !field.isEditable) return@guarded ShellUnlockResult.INTERRUPTED
        prepared = false
        accepted(field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id))
    }

    @Synchronized override fun pattern(value: String): Int = guarded {
        if (!prepared || inputStarted || kind != UnlockKind.PATTERN) return@guarded ShellUnlockResult.INTERRUPTED
        UnlockCredential(UnlockKind.PATTERN, value)
        val root = root(UnlockKind.PATTERN)
        ui.preflight(root, UnlockKind.PATTERN)
        val field = ui.entry(root, UnlockKind.PATTERN) ?: return@guarded ShellUnlockResult.INTERRUPTED
        val bounds = Rect().also(field::getBoundsInScreen)
        val down = SystemClock.uptimeMillis()
        inputStarted = true
        prepared = false
        var x = 0f; var y = 0f
        var needsCancel = false
        try {
            value.forEachIndexed { index, char ->
                // Revalidate the trusted surface before every segment. No remote coordinates.
                val current = ui.entry(root(UnlockKind.PATTERN), UnlockKind.PATTERN) ?: return@guarded ShellUnlockResult.INTERRUPTED
                if (Rect().also(current::getBoundsInScreen) != bounds) return@guarded ShellUnlockResult.INTERRUPTED
                val point = char - '1'
                x = bounds.left + bounds.width() * ((point % 3 + .5f) / 3)
                y = bounds.top + bounds.height() * ((point / 3 + .5f) / 3)
                needsCancel = true
                if (!touch(down, if (index == 0) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_MOVE, x, y)) {
                    return@guarded ShellUnlockResult.INTERRUPTED
                }
                SystemClock.sleep(120)
            }
            val released = touch(down, MotionEvent.ACTION_UP, x, y)
            needsCancel = !released
            accepted(released)
        } finally { if (needsCancel) runCatching { touch(down, MotionEvent.ACTION_CANCEL, x, y) } }
    }

    @Synchronized override fun reason(): Int = if (authorized()) lastReason.ordinal else UnlockDiagnostics.Reason.NONE.ordinal

    private fun root(mode: UnlockKind): AccessibilityNodeInfo {
        if (!keyguard.isKeyguardLocked || !keyguard.isDeviceLocked) throw ToolFailure("UNLOCK_INTERRUPTED", "系统锁屏状态已改变")
        val client = automation ?: throw ToolFailure("SHIZUKU_UNAVAILABLE", "Shizuku 本地输入服务不可用")
        if (Build.VERSION.SDK_INT >= 34) {
            client.clearCache()
        } else {
            val interaction = Class.forName("android.view.accessibility.AccessibilityInteractionClient")
            val instance = interaction.getMethod("getInstance").invoke(null)
            val legacyClear = runCatching { interaction.getMethod("clearCache") }.getOrNull()
            if (legacyClear != null) legacyClear.invoke(instance)
            else {
                val connectionId = UiAutomation::class.java.getMethod("getConnectionId").invoke(client)
                interaction.getMethod("clearCache", Int::class.javaPrimitiveType).invoke(instance, connectionId)
            }
        }
        return ui.systemRoot(client.windows, mode == UnlockKind.PASSWORD)
            ?: throw ToolFailure("UNLOCK_INTERRUPTED", "无法确认系统锁屏或存在覆盖窗口")
    }

    private fun tap(node: AccessibilityNodeInfo): Int {
        val bounds = Rect().also(node::getBoundsInScreen)
        if (bounds.isEmpty) return ShellUnlockResult.INTERRUPTED
        val down = SystemClock.uptimeMillis()
        val x = bounds.exactCenterX(); val y = bounds.exactCenterY()
        var needsCancel = true
        return try {
            if (!touch(down, MotionEvent.ACTION_DOWN, x, y)) ShellUnlockResult.INTERRUPTED
            else {
                val released = touch(down, MotionEvent.ACTION_UP, x, y)
                needsCancel = !released
                accepted(released)
            }
        } finally { if (needsCancel) runCatching { touch(down, MotionEvent.ACTION_CANCEL, x, y) } }
    }

    private fun touch(down: Long, action: Int, x: Float, y: Float): Boolean {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        return try { automation?.injectInputEvent(event, true) == true } finally { event.recycle() }
    }
    private fun accepted(value: Boolean) = if (value) ShellUnlockResult.OK else ShellUnlockResult.INTERRUPTED
    private fun guarded(action: () -> Int): Int {
        if (!authorized() || automation == null) return ShellUnlockResult.UNAVAILABLE
        val identity = Binder.clearCallingIdentity()
        UnlockDiagnostics.begin()
        lastReason = UnlockDiagnostics.Reason.NONE
        return try { action().also { if (it < 0) prepared = false } } catch (failure: ToolFailure) {
            prepared = false
            val reason = UnlockDiagnostics.snapshot()?.optString("reason")
            lastReason = UnlockDiagnostics.Reason.entries.firstOrNull { it.name.lowercase() == reason } ?: UnlockDiagnostics.Reason.NONE
            when (failure.code) {
                "USER_UNLOCK_REQUIRED" -> ShellUnlockResult.USER_REQUIRED
                "UNLOCK_UNSUPPORTED" -> ShellUnlockResult.UNSUPPORTED
                "SHIZUKU_UNAVAILABLE" -> ShellUnlockResult.UNAVAILABLE
                else -> ShellUnlockResult.INTERRUPTED
            }
        } catch (_: Exception) { prepared = false; ShellUnlockResult.UNAVAILABLE }
        finally { Binder.restoreCallingIdentity(identity) }
    }
    private fun release() {
        automation?.let { runCatching { UiAutomation::class.java.getMethod("disconnect").invoke(it) } }
        automation = null; thread?.quitSafely(); thread = null; kind = null; submission = null; prepared = false; inputStarted = false
    }
    @Synchronized override fun destroy() {
        Binder.clearCallingIdentity()
        release()
        kotlin.system.exitProcess(0)
    }
}

internal object ShellUnlockResult {
    const val OK = 0
    const val PIN_EXPLICIT = 1
    const val PIN_AUTO = 2
    const val UNAVAILABLE = -1
    const val UNSUPPORTED = -2
    const val USER_REQUIRED = -3
    const val INTERRUPTED = -4
}
