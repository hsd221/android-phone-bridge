package io.github.hsd221.phonebridge

import kotlinx.coroutines.delay

internal enum class PinSubmission { EXPLICIT_ENTER, AUTO_CONFIRM }

/** One local sequence. The caller persists its attempt latch and validates every action. */
internal suspend fun enterPinOnce(
    value: String,
    submission: PinSubmission,
    ensure: () -> Unit,
    isUnlocked: () -> Boolean,
    clickDigit: (Char) -> Unit,
    confirm: () -> Unit,
) {
    for (digit in value) {
        ensure()
        if (isUnlocked()) return
        clickDigit(digit)
        delay(100)
    }
    delay(250)
    ensure()
    if (isUnlocked() || submission == PinSubmission.AUTO_CONFIRM) return
    confirm()
}
