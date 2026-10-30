package io.github.hsd221.phonebridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
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
        var input: LocalCredentialInput? = null
        var accessibilityInfo: Pair<PhoneAccessibilityService, Int>? = null
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
            accessibilityInfo = accessibility to accessibility.serviceInfo.flags
            accessibility.serviceInfo = accessibility.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            }
            val credential: UnlockCredential
            if (context.getSystemService(PowerManager::class.java).isInteractive && bouncerPresent(accessibility)) {
                // Opening a show-when-locked activity can hide an existing bouncer
                // and clear a user's partial entry. Do not disturb that surface.
                credential = store.read() ?: throw ToolFailure("USER_UNLOCK_REQUIRED", "请在手机上完成当前系统解锁")
                val root = systemRoot(accessibility, allowInputMethod = credential.kind == UnlockKind.PASSWORD) ?: unsupported()
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
                awaitBouncer(accessibility, opened, credential.kind, ::checkAccess) ?: return success("system")
            }
            UnlockDiagnostics.stage(UnlockDiagnostics.Stage.PREFLIGHT)
            val prepared = prepareCredentialInput(
                openPreferred = { if (prefs.shizukuEnabled) ShizukuCredentialInput.open(context, ::checkAccess) else null },
                credential = credential,
                ensure = ::checkAccess,
                // A disconnected preparation may have taken time. Always acquire
                // a fresh system surface before the accessibility fallback.
                fallbackPreflight = {
                    val fresh = systemRoot(accessibility, credential.kind == UnlockKind.PASSWORD) ?: unsupported()
                    preflight(fresh, credential)
                },
            )
            input = prepared.input
            val pinSubmission = prepared.submission
            checkAccess()
            store.beginAttempt()
            attempted = true
            UnlockDiagnostics.enteringCredential()
            if (input != null) {
                input.enter(credential, pinSubmission, ensure = {
                    checkAccess()
                    if (!prefs.shizukuEnabled) throw ToolFailure("UNLOCK_INTERRUPTED", "Shizuku 增强已关闭，已停止本地输入")
                }, isUnlocked = { !keyguard.isKeyguardLocked && !keyguard.isDeviceLocked })
            } else {
                when (credential.kind) {
                    UnlockKind.PIN -> enterPin(accessibility, credential.value, checkNotNull(pinSubmission), ::checkAccess)
                    UnlockKind.PASSWORD -> enterPassword(accessibility, credential.value, ::checkAccess)
                    UnlockKind.PATTERN -> enterPattern(accessibility, credential.value, ::checkAccess)
                }
            }
            UnlockDiagnostics.stage(UnlockDiagnostics.Stage.VERIFYING)
            repeat(60) {
                checkAccess()
                if (!keyguard.isKeyguardLocked && !keyguard.isDeviceLocked) {
                    store.allowNextAttempt()
                    return success(if (input == null) "local_credential" else "shizuku")
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
            input?.close()
            accessibilityInfo?.let { (service, flags) ->
                runCatching { service.serviceInfo = service.serviceInfo.apply { this.flags = flags } }
            }
            activity?.finishAndRemoveTask()
            PhoneAccessibilityService.instance?.invalidate()
        }
    }

    private fun success(path: String): JSONObject {
        UnlockDiagnostics.finish("SUCCESS")
        return JSONObject().put("unlocked", true).put("path", path).put("screen", screenState(context))
    }

    private val ui = KeyguardUi(context)
    private fun systemRoot(accessibility: PhoneAccessibilityService, allowInputMethod: Boolean = false) =
        ui.systemRoot(accessibility.windows, allowInputMethod)
    private fun node(root: AccessibilityNodeInfo, id: String) = ui.node(root, id)
    private fun entry(root: AccessibilityNodeInfo, kind: UnlockKind) = ui.entry(root, kind)

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

    private fun preflight(root: AccessibilityNodeInfo, credential: UnlockCredential) =
        ui.preflight(root, credential.kind, credential.value.toSet())
    private fun unsupported(reason: UnlockDiagnostics.Reason = UnlockDiagnostics.Reason.UNRECOGNIZED): Nothing = ui.unsupported(reason)

    private fun currentRoot(accessibility: PhoneAccessibilityService, ensure: () -> Unit, allowInputMethod: Boolean = false): AccessibilityNodeInfo {
        ensure()
        return systemRoot(accessibility, allowInputMethod) ?: throw ToolFailure("UNLOCK_INTERRUPTED", "系统锁屏状态已改变，已停止本地输入")
    }

    private suspend fun enterPin(accessibility: PhoneAccessibilityService, value: String, submission: PinSubmission, ensure: () -> Unit) {
        var entered = false
        enterPinOnce(value, submission, ensure,
            isUnlocked = { !keyguard.isKeyguardLocked && !keyguard.isDeviceLocked },
            clickDigit = { digit ->
                val root = currentRoot(accessibility, ensure)
                if (entry(root, UnlockKind.PIN)?.isPassword != true) unsupported(UnlockDiagnostics.Reason.ENTRY_NOT_PROTECTED)
                if (submission == PinSubmission.AUTO_CONFIRM) {
                    if (!entered) {
                        // Recheck emptiness at the first action, after the latch
                        // was saved. Do not append to a user's intervening input.
                        if (preflight(root, UnlockCredential(UnlockKind.PIN, value)) != PinSubmission.AUTO_CONFIRM) {
                            throw ToolFailure("UNLOCK_INTERRUPTED", "系统锁屏状态已改变，已停止本地输入")
                        }
                    } else {
                        val owner = root.packageName?.toString() ?: unsupported()
                        val backVisible = root.findAccessibilityNodeInfosByViewId("$owner:id/back_button").any { it.isVisibleToUser }
                        val deleteActionable = node(root, "delete_button")?.isClickable == true
                        if (!PinAutoConfirmPolicy.canContinue(backVisible, deleteActionable)) {
                            throw ToolFailure("UNLOCK_INTERRUPTED", "系统锁屏状态已改变，已停止本地输入")
                        }
                    }
                }
                val key = node(root, "key$digit") ?: unsupported(UnlockDiagnostics.Reason.DIGIT_CONTROL_UNAVAILABLE)
                if (!key.isClickable || (submission == PinSubmission.AUTO_CONFIRM && key.contentDescription?.toString() != digit.toString())) {
                    unsupported(UnlockDiagnostics.Reason.DIGIT_CONTROL_UNAVAILABLE)
                }
                ensure()
                if (!key.performAction(AccessibilityNodeInfo.ACTION_CLICK)) throw ToolFailure("UNLOCK_INTERRUPTED", "系统拒绝本地 PIN 输入")
                entered = true
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
