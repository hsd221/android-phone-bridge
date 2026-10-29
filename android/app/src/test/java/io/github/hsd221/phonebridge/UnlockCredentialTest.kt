package io.github.hsd221.phonebridge

import org.junit.Assert.*
import org.junit.Test

class UnlockCredentialTest {
    @Test fun formatsRejectInvalidCredentialsWithoutReflectingTheirValues() {
        listOf(
            UnlockKind.PIN to "12ab56",
            UnlockKind.PIN to "123",
            UnlockKind.PASSWORD to "contains\ncontrol",
            UnlockKind.PATTERN to "11258",
            UnlockKind.PATTERN to "1357", // A missing midpoint must be normalized before storage.
            UnlockKind.PATTERN to "1250",
        ).forEach { (kind, value) ->
            val failure = assertThrows(IllegalArgumentException::class.java) { UnlockCredential(kind, value) }
            assertFalse("Validation leaked a credential", failure.message.orEmpty().contains(value))
        }
        listOf(UnlockKind.PIN to "849261", UnlockKind.PASSWORD to "Synthetic password", UnlockKind.PATTERN to "12589")
            .forEach { (kind, value) -> assertFalse(UnlockCredential(kind, value).toString().contains(value)) }
    }

    @Test fun patternNormalizationMatchesAndroidMidpointRules() {
        assertEquals("1236987", PatternSequence.normalize("1397"))
        assertEquals("159", PatternSequence.normalize("19"))
        assertEquals("12589", PatternSequence.normalize("12589"))
        assertEquals("12589", PatternSequence.normalize("1125589"))
        assertEquals("213", PatternSequence.normalize("213")) // Previously visited midpoint is not repeated.
    }
}
