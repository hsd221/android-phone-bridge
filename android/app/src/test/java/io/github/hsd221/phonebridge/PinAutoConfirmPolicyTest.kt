package io.github.hsd221.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Test

/** Synthetic public-profile metadata only; no credential or trusted-window bypass. */
class PinAutoConfirmPolicyTest {
    private val verifiedKeys = ('0'..'9').map { PinDigitKey(it, it.toString(), actionable = true) }

    private fun check(
        keys: List<PinDigitKey> = verifiedKeys,
        backButtonActionable: Boolean = true,
        deleteButtonVisible: Boolean = false,
    ) = PinAutoConfirmPolicy.check(keys, backButtonActionable, deleteButtonVisible)

    @Test fun reviewedEmptyProfileAcceptsDigitsInAnyTraversalOrder() {
        assertEquals(PinAutoConfirmCheck.SUPPORTED, check())
        assertEquals(PinAutoConfirmCheck.SUPPORTED, check(verifiedKeys.reversed()))
        assertEquals(PinAutoConfirmCheck.SUPPORTED, check(verifiedKeys.drop(4) + verifiedKeys.take(4)))
    }

    @Test fun visibleDeleteIndicatesExistingInputEvenWhenBackIsAlsoVisible() {
        assertEquals(PinAutoConfirmCheck.INPUT_PRESENT, check(deleteButtonVisible = true))
    }

    @Test fun unavailableBackDoesNotProveAnEmptyCustomField() {
        assertEquals(PinAutoConfirmCheck.EMPTY_STATE_UNKNOWN, check(backButtonActionable = false))
    }

    @Test fun allTenUniqueCanonicalDigitsAreRequiredBeforeAnyInput() {
        for (digit in '0'..'9') {
            assertEquals("A missing digit was accepted", PinAutoConfirmCheck.KEYPAD_UNVERIFIED, check(verifiedKeys.filter { it.digit != digit }))
            assertEquals("A duplicate digit was accepted", PinAutoConfirmCheck.KEYPAD_UNVERIFIED, check(verifiedKeys + verifiedKeys.first { it.digit == digit }))
        }
        val duplicateWithMissing = verifiedKeys.toMutableList().apply { this[0] = this[1] }
        assertEquals(PinAutoConfirmCheck.KEYPAD_UNVERIFIED, check(duplicateWithMissing))
        val nonCanonical = verifiedKeys.toMutableList().apply { this[0] = PinDigitKey('x', "x", actionable = true) }
        assertEquals(PinAutoConfirmCheck.KEYPAD_UNVERIFIED, check(nonCanonical))
    }

    @Test fun everyDigitMustBeActionableIncludingDigitsOutsideAnyOnePin() {
        for (digit in '0'..'9') {
            val textOnlyOrDisabled = verifiedKeys.map { if (it.digit == digit) it.copy(actionable = false) else it }
            assertEquals("A non-actionable digit was accepted", PinAutoConfirmCheck.KEYPAD_UNVERIFIED, check(textOnlyOrDisabled))
        }
    }

    @Test fun labelsMustBeExactRatherThanMissingLocalizedOrDescriptive() {
        for (digit in '0'..'9') {
            val fullWidth = ('\uFF10'.code + (digit - '0')).toChar().toString()
            for (label in listOf(null, "", " $digit", "$digit ", "$digit ABC", fullWidth, "number")) {
                val changed = verifiedKeys.map { if (it.digit == digit) it.copy(label = label) else it }
                assertEquals("An unverified digit label was accepted", PinAutoConfirmCheck.KEYPAD_UNVERIFIED, check(changed))
            }
        }
    }

    @Test fun scrambledAndDuplicatedSemanticsRefuseTheEntireKeypad() {
        val scrambled = verifiedKeys.map { it.copy(label = (((it.digit - '0') + 1) % 10).toString()) }
        assertEquals(PinAutoConfirmCheck.KEYPAD_UNVERIFIED, check(scrambled))
        val duplicated = verifiedKeys.map { it.copy(label = "0") }
        assertEquals(PinAutoConfirmCheck.KEYPAD_UNVERIFIED, check(duplicated))
    }

    @Test fun continuedInputRequiresDeleteActionableAndBackAbsent() {
        assertEquals(true, PinAutoConfirmPolicy.canContinue(backButtonVisible = false, deleteButtonActionable = true))
        assertEquals(false, PinAutoConfirmPolicy.canContinue(backButtonVisible = false, deleteButtonActionable = false))
        assertEquals(false, PinAutoConfirmPolicy.canContinue(backButtonVisible = true, deleteButtonActionable = false))
        assertEquals(false, PinAutoConfirmPolicy.canContinue(backButtonVisible = true, deleteButtonActionable = true))
    }
}
