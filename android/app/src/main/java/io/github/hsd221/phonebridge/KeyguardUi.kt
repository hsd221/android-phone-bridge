package io.github.hsd221.phonebridge

import android.app.KeyguardManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Rect
import android.os.PowerManager
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/** The same trust, overlap and empty-entry checks protect both local input transports. */
internal class KeyguardUi(private val context: Context) {
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val keyguardPackages = setOf("com.android.systemui", "com.android.keyguard")
    fun systemRoot(windows: List<AccessibilityWindowInfo>, allowInputMethod: Boolean = false): AccessibilityNodeInfo? {
        if (!keyguard.isKeyguardLocked || !context.getSystemService(PowerManager::class.java).isInteractive) return null
        // UnlockActivity wakes the device and, without a local credential, lets
        // Android dismiss keyguard. It can therefore become rootInActiveWindow;
        // resolve the actual keyguard from the window list instead of trusting focus.
        val visibleWindows = windows.filter { it.displayId == Display.DEFAULT_DISPLAY }
        val target = visibleWindows.firstOrNull { window ->
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
        if (visibleWindows.any { window ->
                window.layer > target.layer && Rect.intersects(inputBounds, Rect().also(window::getBoundsInScreen)) &&
                    !(allowInputMethod && window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) &&
                    window.root?.packageName?.toString() !in keyguardPackages &&
                    window.root?.packageName?.toString() != context.packageName
            }) return null
        return root
    }

    fun node(root: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo? {
        val owner = root.packageName?.toString() ?: return null
        return root.findAccessibilityNodeInfosByViewId("$owner:id/$id").singleOrNull {
            it.packageName?.toString() == owner && it.isVisibleToUser && it.isEnabled
        }
    }
    fun entry(root: AccessibilityNodeInfo, kind: UnlockKind) = node(root, when (kind) {
        UnlockKind.PIN -> "pinEntry"
        UnlockKind.PASSWORD -> "passwordEntry"
        UnlockKind.PATTERN -> "lockPatternView"
    })

    fun preflight(root: AccessibilityNodeInfo, kind: UnlockKind, pinDigits: Set<Char> = ('0'..'9').toSet()): PinSubmission? {
        val containerId = when (kind) {
            UnlockKind.PIN -> "keyguard_pin_view"
            UnlockKind.PASSWORD -> "keyguard_password_view"
            UnlockKind.PATTERN -> "keyguard_pattern_view"
        }
        if (node(root, containerId) == null) unsupported(UnlockDiagnostics.Reason.CONTAINER_UNAVAILABLE)
        val field = entry(root, kind) ?: unsupported(UnlockDiagnostics.Reason.ENTRY_UNAVAILABLE)
        when (kind) {
            UnlockKind.PIN -> {
                if (!field.isPassword) unsupported(UnlockDiagnostics.Reason.ENTRY_NOT_PROTECTED)
                if (!field.text.isNullOrEmpty()) existingInput()
                if (pinDigits.any { node(root, "key$it")?.isClickable != true }) unsupported(UnlockDiagnostics.Reason.DIGIT_CONTROL_UNAVAILABLE)
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
                        val candidates = root.findAccessibilityNodeInfosByViewId("$owner:id/back_button")
                            .filter { it.packageName?.toString() == owner }
                        val visible = candidates.filter { it.isVisibleToUser }
                        val enabled = visible.filter { it.isEnabled }
                        UnlockDiagnostics.refused(when {
                            candidates.isEmpty() -> UnlockDiagnostics.Reason.BACK_CONTROL_MISSING
                            visible.isEmpty() -> UnlockDiagnostics.Reason.BACK_CONTROL_HIDDEN
                            enabled.isEmpty() -> UnlockDiagnostics.Reason.BACK_CONTROL_DISABLED
                            enabled.size > 1 -> UnlockDiagnostics.Reason.BACK_CONTROL_AMBIGUOUS
                            else -> UnlockDiagnostics.Reason.BACK_CONTROL_NOT_ACTIONABLE
                        })
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
    fun existingInput(): Nothing {
        UnlockDiagnostics.refused(UnlockDiagnostics.Reason.ENTRY_NOT_EMPTY)
        throw ToolFailure("USER_UNLOCK_REQUIRED", "锁屏输入框已有内容，请先在手机上处理")
    }
    fun unsupported(reason: UnlockDiagnostics.Reason = UnlockDiagnostics.Reason.UNRECOGNIZED): Nothing {
        UnlockDiagnostics.refused(reason)
        throw ToolFailure("UNLOCK_UNSUPPORTED", "此系统锁屏暂不支持本地自动输入，请在手机上手动解锁")
    }

}
