package io.github.hsd221.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Test

/** Synthetic public-profile metadata only; no credential or trusted-window bypass. */
class PinAutoConfirmPolicyTest {
    private val verifiedKeys = ('0'..'9').map { PinDigitKey(it, it.toString(), actionable = true) }
    private val reviewedIds = listOf("back_button", "cancel_button")

    private fun control(id: String) = PinEmptyControl(id, visible = true, enabled = true, actionable = true)

    private fun check(
        keys: List<PinDigitKey> = verifiedKeys,
        emptyControls: List<PinEmptyControl> = listOf(control("back_button")),
        deleteButtonVisible: Boolean = false,
    ) = PinAutoConfirmPolicy.check(keys, emptyControls, deleteButtonVisible)

    @Test fun reviewedBackAndCancelProfilesAcceptDigitsInAnyTraversalOrder() {
        for (id in reviewedIds) {
            val controls = listOf(control(id))
            assertEquals(id, PinEmptyControlCheck.READY, PinAutoConfirmPolicy.emptyControlCheck(controls))
            for (keys in listOf(verifiedKeys, verifiedKeys.reversed(), verifiedKeys.drop(4) + verifiedKeys.take(4))) {
                assertEquals(id, PinAutoConfirmCheck.SUPPORTED, check(keys, controls))
            }
        }
    }

    @Test fun missingHiddenDisabledAndNonActionableEmptyControlsNeverProveEmptyInput() {
        val cases = mutableListOf(PinEmptyControlCheck.MISSING to emptyList<PinEmptyControl>())
        for (id in reviewedIds) {
            cases += PinEmptyControlCheck.HIDDEN to listOf(control(id).copy(visible = false))
            cases += PinEmptyControlCheck.DISABLED to listOf(control(id).copy(enabled = false))
            cases += PinEmptyControlCheck.NOT_ACTIONABLE to listOf(control(id).copy(actionable = false))
        }
        for ((expected, controls) in cases) {
            assertEquals(expected, PinAutoConfirmPolicy.emptyControlCheck(controls))
            assertEquals(PinAutoConfirmCheck.EMPTY_STATE_UNKNOWN, check(emptyControls = controls))
        }
    }

    @Test fun unknownIdsCannotSubstituteForEitherReviewedEmptyControl() {
        for (id in listOf("", "back", "cancel", "cancel_button_other", "android:id/button2", "unknown_button")) {
            val unknown = listOf(control(id))
            assertEquals(id, PinEmptyControlCheck.MISSING, PinAutoConfirmPolicy.emptyControlCheck(unknown))
            assertEquals(id, PinAutoConfirmCheck.EMPTY_STATE_UNKNOWN, check(emptyControls = unknown))
            for (reviewed in reviewedIds) {
                val hiddenKnown = unknown + control(reviewed).copy(visible = false)
                assertEquals(PinEmptyControlCheck.HIDDEN, PinAutoConfirmPolicy.emptyControlCheck(hiddenKnown))
                assertEquals(PinAutoConfirmCheck.EMPTY_STATE_UNKNOWN, check(emptyControls = hiddenKnown))
            }
        }
    }

    @Test fun duplicateAndMixedVisibleAliasesAreAmbiguousEvenWhenOnlyOneIsEnabled() {
        val pairs = listOf(
            "back_button" to "back_button",
            "cancel_button" to "cancel_button",
            "back_button" to "cancel_button",
            "cancel_button" to "back_button",
        )
        for ((first, second) in pairs) {
            for (other in listOf(control(second), control(second).copy(enabled = false), control(second).copy(actionable = false))) {
                val controls = listOf(control(first), other)
                assertEquals(PinEmptyControlCheck.AMBIGUOUS, PinAutoConfirmPolicy.emptyControlCheck(controls))
                assertEquals(PinAutoConfirmCheck.EMPTY_STATE_UNKNOWN, check(emptyControls = controls))
            }
        }
    }

    @Test fun aHiddenAlternateAliasDoesNotMakeTheOneVisibleReviewedControlAmbiguous() {
        for ((visibleId, hiddenId) in listOf("back_button" to "cancel_button", "cancel_button" to "back_button")) {
            val controls = listOf(control(visibleId), control(hiddenId).copy(visible = false))
            assertEquals(PinEmptyControlCheck.READY, PinAutoConfirmPolicy.emptyControlCheck(controls))
            assertEquals(PinAutoConfirmCheck.SUPPORTED, check(emptyControls = controls))
        }
    }

    @Test fun visibleDeleteRejectsBeforeEmptyControlAndKeypadValidation() {
        val controls = listOf(
            emptyList(),
            listOf(control("unknown_button")),
            listOf(control("back_button")),
            listOf(control("cancel_button")),
            listOf(control("cancel_button").copy(enabled = false)),
            listOf(control("back_button"), control("cancel_button")),
        )
        for (candidate in controls) {
            for (keys in listOf(verifiedKeys, emptyList())) {
                assertEquals(PinAutoConfirmCheck.INPUT_PRESENT, check(keys, candidate, deleteButtonVisible = true))
            }
        }
    }

    @Test fun allTenUniqueCanonicalDigitsAreRequiredBeforeAnyInput() {
        for (id in reviewedIds) {
            val controls = listOf(control(id))
            for (digit in '0'..'9') {
                assertEquals("A missing digit was accepted", PinAutoConfirmCheck.KEYPAD_UNVERIFIED, check(verifiedKeys.filter { it.digit != digit }, controls))
                assertEquals("A duplicate digit was accepted", PinAutoConfirmCheck.KEYPAD_UNVERIFIED, check(verifiedKeys + verifiedKeys.first { it.digit == digit }, controls))
            }
            val duplicateWithMissing = verifiedKeys.toMutableList().apply { this[0] = this[1] }
            assertEquals(PinAutoConfirmCheck.KEYPAD_UNVERIFIED, check(duplicateWithMissing, controls))
            val nonCanonical = verifiedKeys.toMutableList().apply { this[0] = PinDigitKey('x', "x", actionable = true) }
            assertEquals(PinAutoConfirmCheck.KEYPAD_UNVERIFIED, check(nonCanonical, controls))
        }
    }

    @Test fun everyDigitMustBeActionableIncludingDigitsOutsideAnyOnePin() {
        for (id in reviewedIds) for (digit in '0'..'9') {
            val textOnlyOrDisabled = verifiedKeys.map { if (it.digit == digit) it.copy(actionable = false) else it }
            assertEquals("A non-actionable digit was accepted", PinAutoConfirmCheck.KEYPAD_UNVERIFIED, check(textOnlyOrDisabled, listOf(control(id))))
        }
    }

    @Test fun labelsMustBeExactRatherThanMissingLocalizedOrDescriptive() {
        for (id in reviewedIds) for (digit in '0'..'9') {
            val fullWidth = ('\uFF10'.code + (digit - '0')).toChar().toString()
            for (label in listOf(null, "", " $digit", "$digit ", "$digit ABC", fullWidth, "number")) {
                val changed = verifiedKeys.map { if (it.digit == digit) it.copy(label = label) else it }
                assertEquals("An unverified digit label was accepted", PinAutoConfirmCheck.KEYPAD_UNVERIFIED, check(changed, listOf(control(id))))
            }
        }
    }

    @Test fun scrambledAndDuplicatedSemanticsRefuseTheEntireKeypad() {
        for (id in reviewedIds) {
            val controls = listOf(control(id))
            val scrambled = verifiedKeys.map { it.copy(label = (((it.digit - '0') + 1) % 10).toString()) }
            assertEquals(PinAutoConfirmCheck.KEYPAD_UNVERIFIED, check(scrambled, controls))
            val duplicated = verifiedKeys.map { it.copy(label = "0") }
            assertEquals(PinAutoConfirmCheck.KEYPAD_UNVERIFIED, check(duplicated, controls))
        }
    }

    @Test fun continuedInputRequiresActionableDeleteAndNoVisibleKnownEmptyControl() {
        assertEquals(true, PinAutoConfirmPolicy.canContinue(emptyList(), deleteButtonActionable = true))
        assertEquals(false, PinAutoConfirmPolicy.canContinue(emptyList(), deleteButtonActionable = false))
        for (id in reviewedIds) {
            val hidden = listOf(control(id).copy(visible = false))
            assertEquals(true, PinAutoConfirmPolicy.canContinue(hidden, deleteButtonActionable = true))
            assertEquals(false, PinAutoConfirmPolicy.canContinue(hidden, deleteButtonActionable = false))
            for (visible in listOf(control(id), control(id).copy(enabled = false), control(id).copy(actionable = false))) {
                assertEquals(false, PinAutoConfirmPolicy.canContinue(listOf(visible), deleteButtonActionable = false))
                assertEquals(false, PinAutoConfirmPolicy.canContinue(listOf(visible), deleteButtonActionable = true))
            }
        }
        assertEquals(false, PinAutoConfirmPolicy.canContinue(reviewedIds.map(::control), deleteButtonActionable = true))
    }
}
