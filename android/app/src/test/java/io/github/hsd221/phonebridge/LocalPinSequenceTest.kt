package io.github.hsd221.phonebridge

import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/** Only synthetic digits and action records; system-window guards remain in the Android adapter. */
class LocalPinSequenceTest {
    private val reviewedIds = listOf("back_button", "cancel_button")

    @OptIn(InternalCoroutinesApi::class)
    private class ImmediateClock(private val afterDelay: (Long) -> Unit = {}) : CoroutineDispatcher(), Delay {
        override fun dispatch(context: CoroutineContext, block: Runnable) = block.run()
        override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
            afterDelay(timeMillis)
            continuation.resume(Unit)
        }
    }

    /** Synthetic public-profile adapter; it cannot replace Android system/window validation. */
    private class ReviewedProfile(emptyControlId: String) {
        val digits = mutableListOf<Char>()
        val requestedDigits = mutableListOf<Char>()
        var emptyControls = listOf(PinEmptyControl(emptyControlId, visible = true, enabled = true, actionable = true))
        var deleteVisible = false
        var deleteActionable = false
        var afterClick: () -> Unit = {}

        fun click(digit: Char) {
            requestedDigits.add(digit)
            val accepted = if (digits.isEmpty()) {
                val keys = ('0'..'9').map { PinDigitKey(it, it.toString(), actionable = true) }
                PinAutoConfirmPolicy.check(keys, emptyControls, deleteButtonVisible = deleteVisible) == PinAutoConfirmCheck.SUPPORTED
            } else PinAutoConfirmPolicy.canContinue(emptyControls, deleteButtonActionable = deleteActionable)
            check(accepted) { "synthetic profile refusal" }
            digits.add(digit)
            emptyControls = emptyControls.map { it.copy(visible = false) }
            deleteVisible = true
            deleteActionable = true
            afterClick()
        }
    }

    @Test fun reviewedProfileWithContinuousInputCompletesOnlyOneSequence() = runBlocking(ImmediateClock()) {
        for (id in reviewedIds) {
            val profile = ReviewedProfile(id)
            var confirmations = 0
            enterPinOnce("120934", PinSubmission.AUTO_CONFIRM, {}, { false }, profile::click, { confirmations++ })
            assertEquals(listOf('1', '2', '0', '9', '3', '4'), profile.digits)
            assertEquals(profile.digits, profile.requestedDigits)
            assertEquals(0, confirmations)
        }
    }

    @Test fun resetAtEveryShorterPrefixStopsRemainingDigitsForBackAndCancelProfiles() {
        val syntheticSequence = "120934"
        for (id in reviewedIds) for (prefix in 1 until syntheticSequence.length) {
            val profile = ReviewedProfile(id).apply {
                afterClick = {
                    if (digits.size == prefix) {
                        emptyControls = emptyControls.map { it.copy(visible = true) }
                        deleteVisible = false
                        deleteActionable = false
                    }
                }
            }
            assertProfileRefusesOnce(profile, syntheticSequence.take(prefix).toList(), syntheticSequence.take(prefix + 1).toList())
        }
    }

    @Test fun missingDeleteOrConflictingStateAfterPartialInputStopsImmediately() {
        for (id in reviewedIds) for (resetVisible in listOf(false, true)) {
            val profile = ReviewedProfile(id).apply {
                afterClick = {
                    if (digits.size == 2) {
                        emptyControls = emptyControls.map { it.copy(visible = resetVisible) }
                        deleteActionable = resetVisible
                    }
                }
            }
            assertProfileRefusesOnce(profile, listOf('1', '2'), listOf('1', '2', '0'))
        }
    }

    @Test fun eitherKnownEmptyAliasReappearingStopsEvenWithActionableDelete() {
        for (initialId in reviewedIds) for (reappearingId in reviewedIds) {
            val control = PinEmptyControl(reappearingId, visible = true, enabled = true, actionable = true)
            for (reappearing in listOf(control, control.copy(enabled = false), control.copy(actionable = false))) {
                val profile = ReviewedProfile(initialId).apply {
                    afterClick = {
                        if (digits.size == 2) emptyControls = listOf(reappearing)
                    }
                }
                assertProfileRefusesOnce(profile, listOf('1', '2'), listOf('1', '2', '0'))
            }
        }
    }

    @Test fun mixedAliasAmbiguityAfterPartialInputStopsWithoutReplaying() {
        for (id in reviewedIds) {
            val profile = ReviewedProfile(id).apply {
                afterClick = {
                    if (digits.size == 2) emptyControls = listOf(
                        PinEmptyControl("back_button", visible = true, enabled = true, actionable = true),
                        PinEmptyControl("cancel_button", visible = true, enabled = true, actionable = true),
                    )
                }
            }
            assertProfileRefusesOnce(profile, listOf('1', '2'), listOf('1', '2', '0'))
        }
    }

    @Test fun changedEmptyStateBeforeTheFirstClickRefusesWithoutInput() {
        for (id in reviewedIds) {
            val known = PinEmptyControl(id, visible = true, enabled = true, actionable = true)
            val controls = listOf(
                emptyList(),
                listOf(known.copy(visible = false)),
                listOf(known.copy(enabled = false)),
                listOf(known.copy(actionable = false)),
                listOf(known, known),
                listOf(known, PinEmptyControl(if (id == "back_button") "cancel_button" else "back_button", true, true, true)),
                listOf(PinEmptyControl("unknown_button", true, true, true)),
            )
            for (invalid in controls) {
                val profile = ReviewedProfile(id).apply { emptyControls = invalid }
                assertProfileRefusesOnce(profile, emptyList(), listOf('1'))
            }
        }
    }

    @Test fun existingInputOnBackOrCancelProfileNeverAppendsAnyDigit() {
        for (id in reviewedIds) for (emptyVisible in listOf(false, true)) {
            val profile = ReviewedProfile(id).apply {
                emptyControls = emptyControls.map { it.copy(visible = emptyVisible) }
                deleteVisible = true
                deleteActionable = true
            }
            assertProfileRefusesOnce(profile, emptyList(), listOf('1'))
        }
    }

    private fun assertProfileRefusesOnce(profile: ReviewedProfile, accepted: List<Char>, requested: List<Char>) {
        var confirmations = 0
        assertThrows(IllegalStateException::class.java) {
            runBlocking(ImmediateClock()) {
                enterPinOnce("120934", PinSubmission.AUTO_CONFIRM, {}, { false }, profile::click, { confirmations++ })
            }
        }
        assertEquals(accepted, profile.digits)
        assertEquals("A refused action was replayed or remaining input continued", requested, profile.requestedDigits)
        assertEquals(0, confirmations)
    }

    @Test fun autoConfirmEntersOneSequenceAndNeverDispatchesConfirmation() = runBlocking(ImmediateClock()) {
        val digits = mutableListOf<Char>()
        var confirmations = 0
        enterPinOnce("1209", PinSubmission.AUTO_CONFIRM, {}, { false }, { digits.add(it) }, { confirmations++ })
        assertEquals(listOf('1', '2', '0', '9'), digits)
        assertEquals(0, confirmations)
    }

    @Test fun explicitEnterSubmitsExactlyOnceAfterTheCompleteSequence() = runBlocking(ImmediateClock()) {
        val actions = mutableListOf<String>()
        enterPinOnce("1209", PinSubmission.EXPLICIT_ENTER, {}, { false }, { actions.add("digit:$it") }, { actions.add("confirm") })
        assertEquals(listOf("digit:1", "digit:2", "digit:0", "digit:9", "confirm"), actions)
    }

    @Test fun anAlreadyUnlockedDeviceReceivesNoCredentialAction() {
        for (submission in PinSubmission.entries) runBlocking(ImmediateClock()) {
            val actions = mutableListOf<String>()
            enterPinOnce("1209", submission, {}, { true }, { actions.add("digit") }, { actions.add("confirm") })
            assertEquals(emptyList<String>(), actions)
        }
    }

    @Test fun unlockDuringInputStopsRemainingDigitsWithoutConfirmation() {
        for (submission in PinSubmission.entries) runBlocking(ImmediateClock()) {
            val digits = mutableListOf<Char>()
            var confirmations = 0
            enterPinOnce("1209", submission, {}, { digits.size == 2 }, { digits.add(it) }, { confirmations++ })
            assertEquals(listOf('1', '2'), digits)
            assertEquals(0, confirmations)
        }
    }

    @Test fun delayedUnlockAfterTheFinalDigitPreventsExplicitEnter() {
        var unlocked = false
        runBlocking(ImmediateClock { if (it == 250L) unlocked = true }) {
            val digits = mutableListOf<Char>()
            var confirmations = 0
            enterPinOnce("1209", PinSubmission.EXPLICIT_ENTER, {}, { unlocked }, { digits.add(it) }, { confirmations++ })
            assertEquals(listOf('1', '2', '0', '9'), digits)
            assertEquals(0, confirmations)
        }
    }

    @Test fun unlockOnTheFinalDigitPreventsAnyConfirmation() {
        for (submission in PinSubmission.entries) runBlocking(ImmediateClock()) {
            val digits = mutableListOf<Char>()
            var confirmations = 0
            enterPinOnce("1209", submission, {}, { digits.size == 4 }, { digits.add(it) }, { confirmations++ })
            assertEquals(listOf('1', '2', '0', '9'), digits)
            assertEquals(0, confirmations)
        }
    }

    @Test fun cancellationBeforeTheFirstClickDispatchesNothing() {
        for (submission in PinSubmission.entries) {
            var clicks = 0
            var confirmations = 0
            val cancellation = CancellationException("synthetic cancellation")
            val caught = assertThrows(CancellationException::class.java) {
                runBlocking(ImmediateClock()) {
                    enterPinOnce("1209", submission, { throw cancellation }, { false }, { clicks++ }, { confirmations++ })
                }
            }
            assertSame(cancellation, caught)
            assertEquals(0, clicks)
            assertEquals(0, confirmations)
        }
    }

    @Test fun revocationBetweenDigitsStopsWithoutReplayingOrConfirming() {
        for (submission in PinSubmission.entries) {
            val digits = mutableListOf<Char>()
            var confirmations = 0
            val refusal = IllegalStateException("synthetic revocation")
            val caught = assertThrows(IllegalStateException::class.java) {
                runBlocking(ImmediateClock()) {
                    enterPinOnce("1209", submission, { if (digits.size == 1) throw refusal }, { false }, { digits.add(it) }, { confirmations++ })
                }
            }
            assertSame(refusal, caught)
            assertEquals(listOf('1'), digits)
            assertEquals(0, confirmations)
        }
    }

    @Test fun revocationAfterTheFinalDigitStillPreventsConfirmation() {
        for (submission in PinSubmission.entries) {
            val digits = mutableListOf<Char>()
            var confirmations = 0
            val refusal = IllegalStateException("synthetic revocation")
            val caught = assertThrows(IllegalStateException::class.java) {
                runBlocking(ImmediateClock()) {
                    enterPinOnce("1209", submission, { if (digits.size == 4) throw refusal }, { false }, { digits.add(it) }, { confirmations++ })
                }
            }
            assertSame(refusal, caught)
            assertEquals(listOf('1', '2', '0', '9'), digits)
            assertEquals(0, confirmations)
        }
    }

    @Test fun anActionFailurePropagatesWithoutAnyRetry() {
        for (submission in PinSubmission.entries) {
            var clicks = 0
            var confirmations = 0
            val refusal = IllegalStateException("synthetic dispatch failure")
            val caught = assertThrows(IllegalStateException::class.java) {
                runBlocking(ImmediateClock()) {
                    enterPinOnce("1209", submission, {}, { false }, { clicks++; throw refusal }, { confirmations++ })
                }
            }
            assertSame(refusal, caught)
            assertEquals(1, clicks)
            assertEquals(0, confirmations)
        }
    }

    @Test fun changedWindowOrLabelAfterPartialInputStopsAtTheAdapterRefusal() {
        for (submission in PinSubmission.entries) {
            val digits = mutableListOf<Char>()
            var confirmations = 0
            var resolutions = 0
            val refusal = IllegalStateException("synthetic adapter refusal")
            val caught = assertThrows(IllegalStateException::class.java) {
                runBlocking(ImmediateClock()) {
                    enterPinOnce("1209", submission, {}, { false }, {
                        resolutions++
                        if (digits.size == 1) throw refusal
                        digits.add(it)
                    }, { confirmations++ })
                }
            }
            assertSame(refusal, caught)
            assertEquals(listOf('1'), digits)
            assertEquals(2, resolutions)
            assertEquals(0, confirmations)
        }
    }
}
