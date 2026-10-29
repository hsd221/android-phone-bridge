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
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume

/** All credential handling stays inside the APK; remote tools cannot inspect this tree. */
class LocalUnlocker(private val context: Context, private val prefs: BridgePrefs) {
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
        fun checkAccess() { ensure(); prefs.policy().requireTool("phone_unlock") }
        checkAccess()
        if (!context.getSystemService(UserManager::class.java).isUserUnlocked) {
            throw ToolFailure("USER_UNLOCK_REQUIRED", "重启后请先在手机上完成首次解锁")
        }
        if (!keyguard.isKeyguardLocked && !keyguard.isDeviceLocked && context.getSystemService(PowerManager::class.java).isInteractive) return success("already_unlocked")
        if (keyguard.isKeyguardLocked && store.retryBlocked) {
            throw ToolFailure("UNLOCK_RETRY_BLOCKED", "上次自动解锁未确认成功，请在手机本地检查并重新允许尝试")
        }
        val accessibility = PhoneAccessibilityService.instance
            ?: throw ToolFailure("ACCESSIBILITY_UNAVAILABLE", "请在系统设置中启用手机工具桥无障碍服务")
        val activity = UnlockActivity.open(accessibility, ::checkAccess)
        var attempted = false
        try {
            // Give Android time to wake, dismiss a trusted/swipe keyguard, or show its bouncer.
            repeat(10) {
                checkAccess()
                if (!keyguard.isKeyguardLocked && !keyguard.isDeviceLocked && context.getSystemService(PowerManager::class.java).isInteractive) return success("system")
                delay(100)
            }
            val credential = store.read()
                ?: throw ToolFailure("USER_UNLOCK_REQUIRED", "请在手机上解锁；自动输入需要在本机配置 PIN、密码或图案，指纹与人脸由系统处理")
            val root = awaitBouncer(accessibility, credential.kind, ::checkAccess) ?: return success("system")
            preflight(root, credential)
            checkAccess()
            store.beginAttempt()
            attempted = true
            when (credential.kind) {
                UnlockKind.PIN -> enterPin(accessibility, credential.value, ::checkAccess)
                UnlockKind.PASSWORD -> enterPassword(accessibility, credential.value, ::checkAccess)
                UnlockKind.PATTERN -> enterPattern(accessibility, credential.value, ::checkAccess)
            }
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
            throw if (attempted && !failure.outcomeUnknown) ToolFailure(failure.code, failure.message, true) else failure
        } finally {
            activity.finishAndRemoveTask()
            accessibility.invalidate()
        }
    }

    private fun success(path: String) = JSONObject().put("unlocked", true).put("path", path).put("screen", screenState(context))

    private fun systemRoot(accessibility: PhoneAccessibilityService, allowInputMethod: Boolean = false): AccessibilityNodeInfo? {
        if (!keyguard.isKeyguardLocked || !context.getSystemService(PowerManager::class.java).isInteractive) return null
        // UnlockActivity is deliberately visible above the keyguard so Android can
        // deliver requestDismissKeyguard. It therefore becomes rootInActiveWindow;
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

    private suspend fun awaitBouncer(accessibility: PhoneAccessibilityService, kind: UnlockKind, ensure: () -> Unit): AccessibilityNodeInfo? {
        val deadline = SystemClock.elapsedRealtime() + 4000
        while (SystemClock.elapsedRealtime() < deadline) {
            ensure()
            if (!keyguard.isKeyguardLocked && !keyguard.isDeviceLocked) return null
            systemRoot(accessibility, allowInputMethod = kind == UnlockKind.PASSWORD)?.let { root -> if (entry(root, kind) != null) return root }
            delay(100)
        }
        throw ToolFailure("UNLOCK_UNSUPPORTED", "未识别到与本地配置匹配的系统锁屏。此设备或当前解锁方式需要在手机上手动解锁")
    }

    private fun preflight(root: AccessibilityNodeInfo, credential: UnlockCredential) {
        val containerId = when (credential.kind) {
            UnlockKind.PIN -> "keyguard_pin_view"
            UnlockKind.PASSWORD -> "keyguard_password_view"
            UnlockKind.PATTERN -> "keyguard_pattern_view"
        }
        if (node(root, containerId) == null) unsupported()
        val field = entry(root, credential.kind) ?: unsupported()
        when (credential.kind) {
            UnlockKind.PIN -> {
                if (!field.isPassword || !field.text.isNullOrEmpty()) throw ToolFailure("USER_UNLOCK_REQUIRED", "锁屏输入框已有内容或状态不明，请先在手机上处理")
                if (credential.value.any { node(root, "key$it")?.isClickable != true } || node(root, "key_enter")?.isClickable != true) unsupported()
            }
            UnlockKind.PASSWORD -> {
                if (!field.isPassword || !field.isEditable || !field.text.isNullOrEmpty()) throw ToolFailure("USER_UNLOCK_REQUIRED", "锁屏输入框已有内容或状态不明，请先在手机上处理")
                if (field.actionList.none { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT } ||
                    field.actionList.none { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id }) unsupported()
            }
            UnlockKind.PATTERN -> {
                val bounds = Rect().also(field::getBoundsInScreen)
                if (bounds.width() < 90 || bounds.height() < 90) unsupported()
            }
        }
    }
    private fun unsupported(): Nothing = throw ToolFailure("UNLOCK_UNSUPPORTED", "此系统锁屏暂不支持本地自动输入，请在手机上手动解锁")

    private fun currentRoot(accessibility: PhoneAccessibilityService, ensure: () -> Unit, allowInputMethod: Boolean = false): AccessibilityNodeInfo {
        ensure()
        return systemRoot(accessibility, allowInputMethod) ?: throw ToolFailure("UNLOCK_INTERRUPTED", "系统锁屏状态已改变，已停止本地输入")
    }

    private suspend fun enterPin(accessibility: PhoneAccessibilityService, value: String, ensure: () -> Unit) {
        for (digit in value) {
            ensure()
            if (!keyguard.isKeyguardLocked && !keyguard.isDeviceLocked) return
            val root = currentRoot(accessibility, ensure)
            if (entry(root, UnlockKind.PIN) == null) unsupported()
            val key = node(root, "key$digit") ?: unsupported()
            ensure()
            if (!key.performAction(AccessibilityNodeInfo.ACTION_CLICK)) throw ToolFailure("UNLOCK_INTERRUPTED", "系统拒绝本地 PIN 输入")
            delay(100)
        }
        // Some systems auto-confirm a PIN. Never send Enter into the newly unlocked app.
        delay(250)
        ensure()
        if (!keyguard.isKeyguardLocked) return
        val root = currentRoot(accessibility, ensure)
        if (entry(root, UnlockKind.PIN) == null) unsupported()
        ensure()
        if (node(root, "key_enter")?.performAction(AccessibilityNodeInfo.ACTION_CLICK) != true) throw ToolFailure("UNLOCK_INTERRUPTED", "系统拒绝本地 PIN 确认")
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
        ensure()
        val completed = suspendCancellableCoroutine { continuation ->
            val accepted = accessibility.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { if (continuation.isActive) continuation.resume(true) }
                override fun onCancelled(gestureDescription: GestureDescription?) { if (continuation.isActive) continuation.resume(false) }
            }, null)
            if (!accepted && continuation.isActive) continuation.resume(false)
        }
        if (!completed) throw ToolFailure("UNLOCK_INTERRUPTED", "系统取消了本地图案输入")
    }
}
