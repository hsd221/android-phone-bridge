package io.github.hsd221.phonebridge

/** Public keypad labels only; this model never takes the user's PIN. */
internal data class PinDigitKey(val digit: Char, val label: String?, val actionable: Boolean)
internal data class PinEmptyControl(val id: String, val visible: Boolean, val enabled: Boolean, val actionable: Boolean)
internal enum class PinEmptyControlCheck { READY, MISSING, HIDDEN, DISABLED, AMBIGUOUS, NOT_ACTIONABLE }
internal enum class PinAutoConfirmCheck { SUPPORTED, INPUT_PRESENT, EMPTY_STATE_UNKNOWN, KEYPAD_UNVERIFIED }

internal object PinAutoConfirmPolicy {
    // Reviewed MIUI controllers toggle one of these controls against Delete.
    // cancel_button is the PIN controller's Back control in newer layouts;
    // do not discover alternatives from labels or arbitrary cancel buttons.
    val emptyControlIds = listOf("back_button", "cancel_button")

    fun emptyControlCheck(controls: List<PinEmptyControl>): PinEmptyControlCheck {
        val candidates = controls.filter { it.id in emptyControlIds }
        if (candidates.isEmpty()) return PinEmptyControlCheck.MISSING
        val visible = candidates.filter { it.visible }
        if (visible.isEmpty()) return PinEmptyControlCheck.HIDDEN
        if (visible.size != 1) return PinEmptyControlCheck.AMBIGUOUS
        val control = visible.single()
        if (!control.enabled) return PinEmptyControlCheck.DISABLED
        return if (control.actionable) PinEmptyControlCheck.READY else PinEmptyControlCheck.NOT_ACTIONABLE
    }

    fun check(keys: List<PinDigitKey>, emptyControls: List<PinEmptyControl>, deleteButtonVisible: Boolean): PinAutoConfirmCheck {
        if (deleteButtonVisible) return PinAutoConfirmCheck.INPUT_PRESENT
        // Missing Delete or null field text never proves an empty custom field.
        if (emptyControlCheck(emptyControls) != PinEmptyControlCheck.READY) return PinAutoConfirmCheck.EMPTY_STATE_UNKNOWN
        if (keys.size != 10 || keys.map { it.digit }.toSet() != ('0'..'9').toSet() ||
            keys.any { !it.actionable || it.label != it.digit.toString() }) return PinAutoConfirmCheck.KEYPAD_UNVERIFIED
        return PinAutoConfirmCheck.SUPPORTED
    }

    // A reset after automatic verification must end this sequence. In the
    // reviewed layout, only Delete with Back absent proves input is ongoing.
    fun canContinue(emptyControls: List<PinEmptyControl>, deleteButtonActionable: Boolean): Boolean =
        emptyControls.none { it.id in emptyControlIds && it.visible } && deleteButtonActionable
}
