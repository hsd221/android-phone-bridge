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
    @OptIn(InternalCoroutinesApi::class)
    private class ImmediateClock(private val afterDelay: (Long) -> Unit = {}) : CoroutineDispatcher(), Delay {
        override fun dispatch(context: CoroutineContext, block: Runnable) = block.run()
        override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
            afterDelay(timeMillis)
            continuation.resume(Unit)
        }
    }

    /** Synthetic public-profile adapter; it cannot replace Android system/window validation. */
    private class ReviewedProfile {
        val digits = mutableListOf<Char>()
        var backVisible = true
        var deleteActionable = false
        var afterClick: () -> Unit = {}

        fun click(digit: Char) {
            val accepted = if (digits.isEmpty()) {
                val keys = ('0'..'9').map { PinDigitKey(it, it.toString(), actionable = true) }
                PinAutoConfirmPolicy.check(keys, backButtonActionable = backVisible, deleteButtonVisible = deleteActionable) == PinAutoConfirmCheck.SUPPORTED
            } else PinAutoConfirmPolicy.canContinue(backButtonVisible = backVisible, deleteButtonActionable = deleteActionable)
            check(accepted) { "synthetic profile refusal" }
            digits.add(digit)
            backVisible = false
            deleteActionable = true
            afterClick()
        }
    }

    @Test fun reviewedProfileWithContinuousInputCompletesOnlyOneSequence() = runBlocking(ImmediateClock()) {
        val profile = ReviewedProfile()
        var confirmations = 0
        enterPinOnce("120934", PinSubmission.AUTO_CONFIRM, {}, { false }, profile::click, { confirmations++ })
        assertEquals(listOf('1', '2', '0', '9', '3', '4'), profile.digits)
        assertEquals(0, confirmations)
    }

    @Test fun automaticFailureAndResetAtAShorterPrefixStopsTheRemainingConfiguredDigits() {
        val profile = ReviewedProfile().apply {
            afterClick = {
                if (digits.size == 4) { backVisible = true; deleteActionable = false }
            }
        }
        var confirmations = 0
        assertThrows(IllegalStateException::class.java) {
            runBlocking(ImmediateClock()) {
                enterPinOnce("120934", PinSubmission.AUTO_CONFIRM, {}, { false }, profile::click, { confirmations++ })
            }
        }
        assertEquals(listOf('1', '2', '0', '9'), profile.digits)
        assertEquals(0, confirmations)
    }

    @Test fun missingDeleteOrConflictingStateAfterPartialInputStopsImmediately() {
        for ((back, delete) in listOf(false to false, true to true)) {
            val profile = ReviewedProfile().apply {
                afterClick = {
                    if (digits.size == 2) { backVisible = back; deleteActionable = delete }
                }
            }
            var confirmations = 0
            assertThrows(IllegalStateException::class.java) {
                runBlocking(ImmediateClock()) {
                    enterPinOnce("120934", PinSubmission.AUTO_CONFIRM, {}, { false }, profile::click, { confirmations++ })
                }
            }
            assertEquals(listOf('1', '2'), profile.digits)
            assertEquals(0, confirmations)
        }
    }

    @Test fun changedEmptyStateBeforeTheFirstClickRefusesWithoutInput() {
        for ((back, delete) in listOf(false to false, true to true, false to true)) {
            val profile = ReviewedProfile().apply { backVisible = back; deleteActionable = delete }
            var confirmations = 0
            assertThrows(IllegalStateException::class.java) {
                runBlocking(ImmediateClock()) {
                    enterPinOnce("120934", PinSubmission.AUTO_CONFIRM, {}, { false }, profile::click, { confirmations++ })
                }
            }
            assertEquals(emptyList<Char>(), profile.digits)
            assertEquals(0, confirmations)
        }
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
