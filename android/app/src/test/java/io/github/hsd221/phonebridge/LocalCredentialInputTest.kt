package io.github.hsd221.phonebridge

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Exercises the production preparation router, before any credential action or durable latch. */
class LocalCredentialInputTest {
    private val credential = UnlockCredential(UnlockKind.PIN, "1209")
    private class Input(private val preparation: () -> PinSubmission? = { PinSubmission.EXPLICIT_ENTER }) : LocalCredentialInput {
        var preflights = 0
        var closes = 0
        var entries = 0
        override suspend fun preflight(credential: UnlockCredential, ensure: () -> Unit): PinSubmission? {
            preflights++
            ensure()
            return preparation()
        }
        override suspend fun enter(credential: UnlockCredential, submission: PinSubmission?, ensure: () -> Unit, isUnlocked: () -> Boolean) { entries++; ensure() }
        override fun close() { closes++ }
    }

    @Test fun availablePreferredInputIsRetainedWithoutFallbackOrCredentialAction() = runBlocking {
        val input = Input()
        var fallbacks = 0
        val prepared = prepareCredentialInput({ input }, credential, {}, { fallbacks++; PinSubmission.AUTO_CONFIRM })
        assertSame(input, prepared.input)
        assertEquals(PinSubmission.EXPLICIT_ENTER, prepared.submission)
        assertEquals(1, input.preflights)
        assertEquals(0, input.entries)
        assertEquals(0, input.closes)
        assertEquals(0, fallbacks)
        prepared.input!!.close()
        assertEquals(1, input.closes)
    }

    @Test fun absentAndDisconnectedPreferredConnectionUseFallbackExactlyOnce() {
        for (open in listOf<suspend () -> LocalCredentialInput?>({ null }, { throw LocalInputUnavailable() })) runBlocking {
            var fallbacks = 0
            val prepared = prepareCredentialInput(open, credential, {}, { fallbacks++; PinSubmission.EXPLICIT_ENTER })
            assertNull(prepared.input)
            assertEquals(PinSubmission.EXPLICIT_ENTER, prepared.submission)
            assertEquals(1, fallbacks)
        }
    }

    @Test fun disconnectionDuringPreflightClosesPreferredBeforeFreshFallback() = runBlocking {
        val input = Input { throw LocalInputUnavailable() }
        var fallbacks = 0
        val prepared = prepareCredentialInput({ input }, credential, {}, {
            assertEquals("Fallback retained a live preferred session", 1, input.closes)
            fallbacks++
            PinSubmission.AUTO_CONFIRM
        })
        assertNull(prepared.input)
        assertEquals(PinSubmission.AUTO_CONFIRM, prepared.submission)
        assertEquals(1, input.preflights)
        assertEquals(0, input.entries)
        assertEquals(1, input.closes)
        assertEquals(1, fallbacks)
    }

    @Test fun safetyRefusalsClosePreferredWithoutFallback() {
        for (code in listOf("USER_UNLOCK_REQUIRED", "UNLOCK_UNSUPPORTED", "UNLOCK_INTERRUPTED")) {
            val refusal = ToolFailure(code, "synthetic refusal")
            val input = Input { throw refusal }
            var fallbacks = 0
            val caught = assertThrows(ToolFailure::class.java) {
                runBlocking { prepareCredentialInput({ input }, credential, {}, { fallbacks++; null }) }
            }
            assertSame(refusal, caught)
            assertEquals(1, input.closes)
            assertEquals(0, input.entries)
            assertEquals(0, fallbacks)
        }
    }

    @Test fun cancellationDuringOpenNeverFallsBack() {
        val cancellation = CancellationException("synthetic cancellation")
        var fallbacks = 0
        val caught = assertThrows(CancellationException::class.java) {
            runBlocking { prepareCredentialInput({ throw cancellation }, credential, {}, { fallbacks++; null }) }
        }
        assertSame(cancellation, caught)
        assertEquals(0, fallbacks)
    }

    @Test fun cancellationDuringPreflightClosesPreferredWithoutFallback() {
        val cancellation = CancellationException("synthetic cancellation")
        val input = Input { throw cancellation }
        var fallbacks = 0
        val caught = assertThrows(CancellationException::class.java) {
            runBlocking { prepareCredentialInput({ input }, credential, {}, { fallbacks++; null }) }
        }
        assertSame(cancellation, caught)
        assertEquals(1, input.closes)
        assertEquals(0, input.entries)
        assertEquals(0, fallbacks)
    }

    @Test fun revokedAccessAfterConnectionClosesPreferredBeforeAnyPreflightOrFallback() {
        val refusal = ToolFailure("OPERATIONS_PAUSED", "synthetic pause")
        val input = Input()
        var checks = 0
        var fallbacks = 0
        val caught = assertThrows(ToolFailure::class.java) {
            runBlocking {
                prepareCredentialInput({ input }, credential, { if (++checks == 2) throw refusal }, { fallbacks++; null })
            }
        }
        assertSame(refusal, caught)
        assertEquals(0, input.preflights)
        assertEquals(1, input.closes)
        assertEquals(0, fallbacks)
    }

    @Test fun revokedAccessAfterDisconnectionPreventsFallback() {
        val refusal = ToolFailure("PAUSED", "synthetic pause")
        val input = Input { throw LocalInputUnavailable() }
        var fallbacks = 0
        val caught = assertThrows(ToolFailure::class.java) {
            runBlocking {
                prepareCredentialInput({ input }, credential, { if (input.closes > 0) throw refusal }, { fallbacks++; null })
            }
        }
        assertSame(refusal, caught)
        assertEquals(1, input.closes)
        assertEquals(0, input.entries)
        assertEquals(0, fallbacks)
    }

    @Test fun unexpectedPreparationFailureIsNotClassifiedAsDisconnected() {
        val failure = IllegalStateException("synthetic failure")
        val input = Input { throw failure }
        var fallbacks = 0
        val caught = assertThrows(IllegalStateException::class.java) {
            runBlocking { prepareCredentialInput({ input }, credential, {}, { fallbacks++; null }) }
        }
        assertSame(failure, caught)
        assertEquals(1, input.closes)
        assertEquals(0, fallbacks)
    }
}
