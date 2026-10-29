package io.github.hsd221.phonebridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserManager
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume

internal enum class RevealPhase { WAKE_READY, BEFORE_SWIPE, AFTER_SWIPE, FOUND_BOUNCER }

/** All credential handling stays inside the APK; remote tools cannot inspect this tree. */
class LocalUnlocker internal constructor(
    private val context: Context,
    private val prefs: BridgePrefs,
    private val onReveal: (RevealPhase) -> Unit = {},
) {
    companion object {
        fun screenState(context: Context): JSONObject {
            val keyguard = context.getSystemService(KeyguardManager::class.java)
            return JSONObject().put("interactive", context.getSystemService(PowerManager::class.java).isInteractive)
                .put("keyguardLocked", keyguard.isKeyguardLocked).put("deviceLocked", keyguard.isDeviceLocked)
                .put("secure", keyguard.isDeviceSecure).put("userUnlocked", context.getSystemService(UserManager::class.java).isUserUnlocked)
        }
        private val keyguardPackages = setOf("com.android.systemui", "com.android.keyguard")
    }
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val store = LocalUnlockStore(context)

    suspend fun unlock(ensure: () -> Unit): JSONObject {
        var activity: UnlockActivity? = null
        fun checkAccess() { ensure(); prefs.policy().requireTool("phone_unlock"); activity?.ensureNotCancelled() }
        checkAccess()
        UnlockDiagnostics.begin()
        var attempted = false
        try {
            if (!context.getSystemService(UserManager::class.java).isUserUnlocked) {
                throw ToolFailure("USER_UNLOCK_REQUIRED", "重启后请先在手机上完成首次解锁")
            }
            if (!keyguard.isKeyguardLocked && !keyguard.isDeviceLocked && context.getSystemService(PowerManager::class.java).isInteractive) return success("already_unlocked")
            if (keyguard.isKeyguardLocked && store.retryBlocked) {
                throw ToolFailure("UNLOCK_RETRY_BLOCKED", "上次自动解锁未确认成功，请在手机本地检查并重新允许尝试")
            }
            val accessibility = PhoneAccessibilityService.instance
                ?: throw ToolFailure("ACCESSIBILITY_UNAVAILABLE", "请在系统设置中启用手机工具桥无障碍服务")
            val credential: UnlockCredential
            val root: AccessibilityNodeInfo
            if (context.getSystemService(PowerManager::class.java).isInteractive && bouncerPresent(accessibility)) {
                // Opening a show-when-locked activity can hide an existing bouncer
                // and clear a user's partial entry. Do not disturb that surface.
                credential = store.read() ?: throw ToolFailure("USER_UNLOCK_REQUIRED", "请在手机上完成当前系统解锁")
                root = systemRoot(accessibility, allowInputMethod = credential.kind == UnlockKind.PASSWORD) ?: unsupported()
                if (entry(root, credential.kind) == null) unsupported()
                UnlockDiagnostics.stage(UnlockDiagnostics.Stage.WAITING_FOR_BOUNCER)
                onReveal(RevealPhase.FOUND_BOUNCER)
                checkAccess()
            } else {
                // OEMs can cancel a system-dismiss request while hiding its bouncer.
                // Configured local credentials use the verified reveal path directly;
                // phones without a local credential retain Android's system dismissal.
                val systemDismiss = !store.configured
                UnlockDiagnostics.waking(systemDismiss)
                val opened = UnlockActivity.open(accessibility, ::checkAccess, systemDismiss)
                activity = opened
                onReveal(RevealPhase.WAKE_READY)
                checkAccess()
                // Give Android time to wake or complete a trusted/system dismissal.
                repeat(10) {
                    checkAccess()
                    if (!keyguard.isKeyguardLocked && !keyguard.isDeviceLocked && context.getSystemService(PowerManager::class.java).isInteractive) return success("system")
                    delay(100)
                }
                credential = store.read()
                    ?: throw ToolFailure("USER_UNLOCK_REQUIRED", "请在手机上解锁；自动输入需要在本机配置 PIN、密码或图案，指纹与人脸由系统处理")
                root = awaitBouncer(accessibility, opened, credential.kind, ::checkAccess) ?: return success("system")
            }
            UnlockDiagnostics.stage(UnlockDiagnostics.Stage.PREFLIGHT)
            val pinSubmission = preflight(root, credential)
            checkAccess()
            store.beginAttempt()
            attempted = true
            UnlockDiagnostics.enteringCredential()
            when (credential.kind) {
                UnlockKind.PIN -> enterPin(accessibility, credential.value, checkNotNull(pinSubmission), ::checkAccess)
                UnlockKind.PASSWORD -> enterPassword(accessibility, credential.value, ::checkAccess)
                UnlockKind.PATTERN -> enterPattern(accessibility, credential.value, ::checkAccess)
            }
            UnlockDiagnostics.stage(UnlockDiagnostics.Stage.VERIFYING)
            repeat(60) {
                checkAccess()
                if (!keyguard.isKeyguardLocked && !keyguard.isDeviceLocked) {
                    store.allowNextAttempt()
                    return success("local_credential")
                }
                delay(100)
            }
            throw ToolFailure("UNLOCK_FAILED", "未确认解锁成功，已停止自动尝试。请在手机本地检查解锁方式和凭据后重新允许尝试", true)
        } catch (failure: ToolFailure) {
            UnlockDiagnostics.finish(failure.code)
            throw if (attempted && !failure.outcomeUnknown) ToolFailure(failure.code, failure.message, true) else failure
        } catch (failure: CancellationException) {
            UnlockDiagnostics.finish("CANCELLED")
            throw failure
        } catch (failure: Exception) {
            UnlockDiagnostics.finish("EXECUTION_FAILED")
            throw failure
        } finally {
            activity?.finishAndRemoveTask()
            PhoneAccessibilityService.instance?.invalidate()
        }
    }

    private fun success(path: String): JSONObject {
        UnlockDiagnostics.finish("SUCCESS")
        return JSONObject().put("unlocked", true).put("path", path).put("screen", screenState(context))
    }

    private fun systemRoot(accessibility: PhoneAccessibilityService, allowInputMethod: Boolean = false): AccessibilityNodeInfo? {
        if (!keyguard.isKeyguardLocked || !context.getSystemService(PowerManager::class.java).isInteractive) return null
        // UnlockActivity wakes the device and, without a local credential, lets
        // Android dismiss keyguard. It can therefore become rootInActiveWindow;
        // resolve the actual keyguard from the window list instead of trusting focus.
        val windows = accessibility.windows.filter { it.displayId == Display.DEFAULT_DISPLAY }
        val target = windows.firstOrNull { window ->
            val root = window.root ?: return@firstOrNull false
            val owner = root.packageName?.toString()
            owner in keyguardPackages && root.isVisibleToUser && !Rect().also(window::getBoundsInScreen).isEmpty &&
                UnlockKind.entries.any { entry(root, it) != null }
        } ?: return null
        val root = target.root ?: return null
        val owner = root.packageName?.toString() ?: return null
        val info = context.packageManager.getApplicationInfo(owner, 0)
        if (info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) return null
        val inputBounds = Rect()
        UnlockKind.entries.mapNotNull { entry(root, it) }.forEach { inputBounds.union(Rect().also(it::getBoundsInScreen)) }
        if (entry(root, UnlockKind.PIN) != null) {
            (('0'..'9').map { "key$it" } + "key_enter").mapNotNull { node(root, it) }
                .forEach { inputBounds.union(Rect().also(it::getBoundsInScreen)) }
        }
        if (inputBounds.isEmpty) return null
        // Navigation/gesture bars may be owned by the launcher and sit above keyguard.
        // Only windows intersecting the actual credential controls can intercept input;
        // never whitelist the entire launcher or any other third-party application.
        if (windows.any { window ->
                window.layer > target.layer && Rect.intersects(inputBounds, Rect().also(window::getBoundsInScreen)) &&
                    !(allowInputMethod && window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) &&
                    window.root?.packageName?.toString() !in keyguardPackages &&
                    window.root?.packageName?.toString() != context.packageName
            }) return null
        return root
    }

    private fun node(root: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo? {
        val owner = root.packageName?.toString() ?: return null
        return root.findAccessibilityNodeInfosByViewId("$owner:id/$id").singleOrNull {
            it.packageName?.toString() == owner && it.isVisibleToUser && it.isEnabled
        }
    }
    private fun entry(root: AccessibilityNodeInfo, kind: UnlockKind) = node(root, when (kind) {
        UnlockKind.PIN -> "pinEntry"
        UnlockKind.PASSWORD -> "passwordEntry"
        UnlockKind.PATTERN -> "lockPatternView"
    })

    private suspend fun waitForBouncer(accessibility: PhoneAccessibilityService, kind: UnlockKind, timeoutMs: Long, ensure: () -> Unit): AccessibilityNodeInfo? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            ensure()
            if (!keyguard.isKeyguardLocked && !keyguard.isDeviceLocked) return null
            systemRoot(accessibility, allowInputMethod = kind == UnlockKind.PASSWORD)?.let { root -> if (entry(root, kind) != null) return root }
            delay(100)
        }
        return null
    }

    private fun bouncerPresent(accessibility: PhoneAccessibilityService): Boolean = accessibility.windows.any { window ->
        if (window.displayId != Display.DEFAULT_DISPLAY) return@any false
        val root = window.root ?: return@any false
        val owner = root.packageName?.toString() ?: return@any false
        if (owner !in keyguardPackages) return@any false
        // Inspect identifiers only. Never read a credential field's text here.
        listOf("pinEntry", "passwordEntry", "lockPatternView").any { id ->
            root.findAccessibilityNodeInfosByViewId("$owner:id/$id").any { it.isVisibleToUser }
        }
    }

    private suspend fun awaitBouncer(accessibility: PhoneAccessibilityService, activity: UnlockActivity, kind: UnlockKind, ensure: () -> Unit): AccessibilityNodeInfo? {
        UnlockDiagnostics.stage(UnlockDiagnostics.Stage.WAITING_FOR_BOUNCER)
        waitForBouncer(accessibility, kind, 1500, ensure)?.let { root ->
            onReveal(RevealPhase.FOUND_BOUNCER); ensure(); return root
        }
        ensure()
        if (!keyguard.isKeyguardLocked && !keyguard.isDeviceLocked) return null
        if (UnlockActivity.dismissStatus == "cancelled") throw ToolFailure("USER_UNLOCK_REQUIRED", "系统解锁请求已取消，请在手机上处理")
        // Existing controls may belong to another lock method or be obstructed. A
        // swipe could submit a partial pattern, so never swipe in that state.
        if (bouncerPresent(accessibility)) unsupported()

        // Some OEMs wake the phone without showing credentials in response to
        // requestDismissKeyguard. Remove our helper, then reveal the real keyguard.
        activity.releaseToKeyguard()
        delay(300)
        ensure()
        if (!keyguard.isKeyguardLocked && !keyguard.isDeviceLocked) return null
        if (!bouncerPresent(accessibility) && awaitRevealWindow(accessibility, ensure)) {
            onReveal(RevealPhase.BEFORE_SWIPE)
            ensure()
            val bounds = revealBounds(accessibility)
            val x = bounds.exactCenterX()
            val path = Path().apply {
                moveTo(x, bounds.top + bounds.height() * .72f)
                lineTo(x, bounds.top + bounds.height() * .28f)
            }
            UnlockDiagnostics.revealing()
            dispatchLocalGesture(accessibility, path, 500, ensure)
            onReveal(RevealPhase.AFTER_SWIPE)
            ensure()
        }
        val root = waitForBouncer(accessibility, kind, 4000, ensure)
        if (!keyguard.isKeyguardLocked && !keyguard.isDeviceLocked) return null
        if (root == null) throw ToolFailure("UNLOCK_UI_UNAVAILABLE", "已唤醒并尝试显示验证界面，但未识别到系统解锁键盘；尚未输入凭据，请在手机上处理")
        onReveal(RevealPhase.FOUND_BOUNCER); ensure()
        return root
    }

    private suspend fun awaitRevealWindow(accessibility: PhoneAccessibilityService, ensure: () -> Unit): Boolean {
        UnlockDiagnostics.stage(UnlockDiagnostics.Stage.WAITING_FOR_KEYGUARD)
        // Finishing the helper can briefly leave windows with null roots. Wait for
        // a verifiable system surface; never substitute guessed coordinates.
        val deadline = SystemClock.elapsedRealtime() + 2000
        var refusal: ToolFailure? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            ensure()
            if ((!keyguard.isKeyguardLocked && !keyguard.isDeviceLocked) || bouncerPresent(accessibility)) return false
            try { revealBounds(accessibility); return true }
            catch (failure: ToolFailure) {
                if (failure.code != "UNLOCK_UNSUPPORTED") throw failure
                refusal = failure
            }
            delay(100)
        }
        throw refusal ?: ToolFailure("UNLOCK_UNSUPPORTED", "未能确认系统锁屏窗口，尚未输入凭据，请手动解锁")
    }

    private fun revealBounds(accessibility: PhoneAccessibilityService): Rect {
        if (!keyguard.isKeyguardLocked || !keyguard.isDeviceLocked || !context.getSystemService(PowerManager::class.java).isInteractive || bouncerPresent(accessibility)) {
            throw ToolFailure("UNLOCK_INTERRUPTED", "锁屏界面状态已改变，已停止显示解锁键盘")
        }
        val metrics = accessibility.resources.displayMetrics
        val windows = accessibility.windows.filter { it.displayId == Display.DEFAULT_DISPLAY }
        val target = windows.firstOrNull { window ->
            val root = window.root ?: return@firstOrNull false
            val owner = root.packageName?.toString() ?: return@firstOrNull false
            val bounds = Rect().also(window::getBoundsInScreen)
            owner in keyguardPackages && root.isVisibleToUser && window.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
                bounds.width() >= metrics.widthPixels * .8f && bounds.height() >= metrics.heightPixels * .8f
        } ?: throw ToolFailure("UNLOCK_UNSUPPORTED", "无法安全确认系统锁屏壁纸窗口，尚未输入凭据，请手动解锁")
        val owner = target.root?.packageName?.toString() ?: unsupported()
        val info = context.packageManager.getApplicationInfo(owner, 0)
        if (info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) unsupported()
        val bounds = Rect().also(target::getBoundsInScreen)
        val swipeBounds = Rect(
            (bounds.exactCenterX() - 2).toInt(), (bounds.top + bounds.height() * .28f).toInt(),
            (bounds.exactCenterX() + 2).toInt(), (bounds.top + bounds.height() * .72f).toInt(),
        )
        if (windows.any { window ->
                window.layer > target.layer && Rect.intersects(swipeBounds, Rect().also(window::getBoundsInScreen)) &&
                    window.root?.packageName?.toString() !in keyguardPackages
            }) throw ToolFailure("UNLOCK_UNSUPPORTED", "系统锁屏滑动区域被其他窗口覆盖，尚未输入凭据，请在手机上处理")
        return bounds
    }

    private fun preflight(root: AccessibilityNodeInfo, credential: UnlockCredential): PinSubmission? {
        val containerId = when (credential.kind) {
            UnlockKind.PIN -> "keyguard_pin_view"
            UnlockKind.PASSWORD -> "keyguard_password_view"
            UnlockKind.PATTERN -> "keyguard_pattern_view"
        }
        if (node(root, containerId) == null) unsupported(UnlockDiagnostics.Reason.CONTAINER_UNAVAILABLE)
        val field = entry(root, credential.kind) ?: unsupported(UnlockDiagnostics.Reason.ENTRY_UNAVAILABLE)
        when (credential.kind) {
            UnlockKind.PIN -> {
                if (!field.isPassword) unsupported(UnlockDiagnostics.Reason.ENTRY_NOT_PROTECTED)
                if (!field.text.isNullOrEmpty()) existingInput()
                if (credential.value.any { node(root, "key$it")?.isClickable != true }) unsupported(UnlockDiagnostics.Reason.DIGIT_CONTROL_UNAVAILABLE)
                if (node(root, "key_enter")?.isClickable == true) return PinSubmission.EXPLICIT_ENTER
                val owner = root.packageName?.toString() ?: unsupported()
                // Do not reinterpret a disabled/ambiguous confirmation control as
                // auto-confirm. The supported MIUI profile has no Enter control.
                if (root.findAccessibilityNodeInfosByViewId("$owner:id/key_enter").isNotEmpty()) {
                    unsupported(UnlockDiagnostics.Reason.CONFIRM_CONTROL_UNAVAILABLE)
                }
                val keys = ('0'..'9').map { digit ->
                    val key = node(root, "key$digit")
                    PinDigitKey(digit, key?.contentDescription?.toString(), key?.isClickable == true)
                }
                val back = node(root, "back_button")
                val deleteVisible = root.findAccessibilityNodeInfosByViewId("$owner:id/delete_button").any { it.isVisibleToUser }
                when (PinAutoConfirmPolicy.check(keys, back?.isClickable == true, deleteVisible)) {
                    PinAutoConfirmCheck.SUPPORTED -> return PinSubmission.AUTO_CONFIRM
                    PinAutoConfirmCheck.INPUT_PRESENT -> existingInput()
                    PinAutoConfirmCheck.EMPTY_STATE_UNKNOWN -> {
                        UnlockDiagnostics.refused(UnlockDiagnostics.Reason.ENTRY_EMPTY_STATE_UNKNOWN)
                        throw ToolFailure("USER_UNLOCK_REQUIRED", "无法确认 PIN 输入框为空，请在手机上处理")
                    }
                    PinAutoConfirmCheck.KEYPAD_UNVERIFIED -> unsupported(UnlockDiagnostics.Reason.AUTO_CONFIRM_UNVERIFIED)
                }
            }
            UnlockKind.PASSWORD -> {
                if (!field.isPassword) unsupported(UnlockDiagnostics.Reason.ENTRY_NOT_PROTECTED)
                if (!field.isEditable) unsupported(UnlockDiagnostics.Reason.ENTRY_NOT_EDITABLE)
                if (!field.text.isNullOrEmpty()) existingInput()
                if (field.actionList.none { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT } ||
                    field.actionList.none { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id }) unsupported(UnlockDiagnostics.Reason.PASSWORD_ACTION_UNAVAILABLE)
            }
            UnlockKind.PATTERN -> {
                val bounds = Rect().also(field::getBoundsInScreen)
                if (bounds.width() < 90 || bounds.height() < 90) unsupported(UnlockDiagnostics.Reason.PATTERN_BOUNDS_INVALID)
            }
        }
        return null
    }
    private fun existingInput(): Nothing {
        UnlockDiagnostics.refused(UnlockDiagnostics.Reason.ENTRY_NOT_EMPTY)
        throw ToolFailure("USER_UNLOCK_REQUIRED", "锁屏输入框已有内容，请先在手机上处理")
    }
    private fun unsupported(reason: UnlockDiagnostics.Reason = UnlockDiagnostics.Reason.UNRECOGNIZED): Nothing {
        UnlockDiagnostics.refused(reason)
        throw ToolFailure("UNLOCK_UNSUPPORTED", "此系统锁屏暂不支持本地自动输入，请在手机上手动解锁")
    }

    private fun currentRoot(accessibility: PhoneAccessibilityService, ensure: () -> Unit, allowInputMethod: Boolean = false): AccessibilityNodeInfo {
        ensure()
        return systemRoot(accessibility, allowInputMethod) ?: throw ToolFailure("UNLOCK_INTERRUPTED", "系统锁屏状态已改变，已停止本地输入")
    }

    private suspend fun enterPin(accessibility: PhoneAccessibilityService, value: String, submission: PinSubmission, ensure: () -> Unit) {
        enterPinOnce(value, submission, ensure,
            isUnlocked = { !keyguard.isKeyguardLocked && !keyguard.isDeviceLocked },
            clickDigit = { digit ->
                val root = currentRoot(accessibility, ensure)
                if (entry(root, UnlockKind.PIN)?.isPassword != true) unsupported(UnlockDiagnostics.Reason.ENTRY_NOT_PROTECTED)
                val key = node(root, "key$digit") ?: unsupported(UnlockDiagnostics.Reason.DIGIT_CONTROL_UNAVAILABLE)
                if (!key.isClickable || (submission == PinSubmission.AUTO_CONFIRM && key.contentDescription?.toString() != digit.toString())) {
                    unsupported(UnlockDiagnostics.Reason.DIGIT_CONTROL_UNAVAILABLE)
                }
                ensure()
                if (!key.performAction(AccessibilityNodeInfo.ACTION_CLICK)) throw ToolFailure("UNLOCK_INTERRUPTED", "系统拒绝本地 PIN 输入")
            },
            confirm = {
                val root = currentRoot(accessibility, ensure)
                if (entry(root, UnlockKind.PIN) == null) unsupported(UnlockDiagnostics.Reason.ENTRY_UNAVAILABLE)
                ensure()
                if (node(root, "key_enter")?.performAction(AccessibilityNodeInfo.ACTION_CLICK) != true) throw ToolFailure("UNLOCK_INTERRUPTED", "系统拒绝本地 PIN 确认")
            },
        )
    }

    private fun enterPassword(accessibility: PhoneAccessibilityService, value: String, ensure: () -> Unit) {
        ensure()
        if (!keyguard.isKeyguardLocked && !keyguard.isDeviceLocked) return
        val root = currentRoot(accessibility, ensure, allowInputMethod = true)
        val field = entry(root, UnlockKind.PASSWORD) ?: unsupported()
        ensure()
        if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            })) throw ToolFailure("UNLOCK_INTERRUPTED", "系统拒绝本地密码输入")
        ensure()
        if (!keyguard.isKeyguardLocked && !keyguard.isDeviceLocked) return
        val fresh = entry(currentRoot(accessibility, ensure, allowInputMethod = true), UnlockKind.PASSWORD) ?: unsupported()
        ensure()
        if (!fresh.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) throw ToolFailure("UNLOCK_INTERRUPTED", "系统拒绝本地密码确认")
    }

    private suspend fun enterPattern(accessibility: PhoneAccessibilityService, value: String, ensure: () -> Unit) {
        ensure()
        if (!keyguard.isKeyguardLocked && !keyguard.isDeviceLocked) return
        val field = entry(currentRoot(accessibility, ensure), UnlockKind.PATTERN) ?: unsupported()
        val bounds = Rect().also(field::getBoundsInScreen)
        val path = Path()
        value.forEachIndexed { index, char ->
            val point = char - '1'
            val x = bounds.left + bounds.width() * ((point % 3 + .5f) / 3)
            val y = bounds.top + bounds.height() * ((point / 3 + .5f) / 3)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, (value.length * 120L).coerceAtLeast(600))).build()
        dispatchLocalGesture(accessibility, gesture, ensure)
    }

    private suspend fun dispatchLocalGesture(accessibility: PhoneAccessibilityService, path: Path, durationMs: Long, ensure: () -> Unit) {
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
        dispatchLocalGesture(accessibility, gesture, ensure)
    }

    private suspend fun dispatchLocalGesture(accessibility: PhoneAccessibilityService, gesture: GestureDescription, ensure: () -> Unit) {
        ensure()
        val completed = suspendCancellableCoroutine { continuation ->
            val accepted = accessibility.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { if (continuation.isActive) continuation.resume(true) }
                override fun onCancelled(gestureDescription: GestureDescription?) { if (continuation.isActive) continuation.resume(false) }
            }, null)
            if (!accepted && continuation.isActive) continuation.resume(false)
        }
        if (!completed) throw ToolFailure("UNLOCK_INTERRUPTED", "系统取消了本地解锁手势")
    }
}
