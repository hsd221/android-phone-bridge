package io.github.hsd221.phonebridge

/** Public keypad labels only; this model never takes the user's PIN. */
internal data class PinDigitKey(val digit: Char, val label: String?, val actionable: Boolean)
internal enum class PinAutoConfirmCheck { SUPPORTED, INPUT_PRESENT, EMPTY_STATE_UNKNOWN, KEYPAD_UNVERIFIED }

internal object PinAutoConfirmPolicy {
    fun check(keys: List<PinDigitKey>, backButtonActionable: Boolean, deleteButtonVisible: Boolean): PinAutoConfirmCheck {
        if (deleteButtonVisible) return PinAutoConfirmCheck.INPUT_PRESENT
        // MIUI shows Back only at zero input and Delete otherwise. A missing
        // Delete node alone proves nothing: GONE views are omitted by Android.
        if (!backButtonActionable) return PinAutoConfirmCheck.EMPTY_STATE_UNKNOWN
        if (keys.size != 10 || keys.map { it.digit }.toSet() != ('0'..'9').toSet() ||
            keys.any { !it.actionable || it.label != it.digit.toString() }) return PinAutoConfirmCheck.KEYPAD_UNVERIFIED
        return PinAutoConfirmCheck.SUPPORTED
    }

    // A reset after automatic verification must end this sequence. In the
    // reviewed layout, only Delete with Back absent proves input is ongoing.
    fun canContinue(backButtonVisible: Boolean, deleteButtonActionable: Boolean): Boolean =
        !backButtonVisible && deleteButtonActionable
}
