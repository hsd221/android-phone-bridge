package io.github.hsd221.phonebridge

/** Phone-local transport; credentials never enter the network tool interface. */
internal interface LocalCredentialInput : AutoCloseable {
    suspend fun preflight(credential: UnlockCredential, ensure: () -> Unit): PinSubmission?
    suspend fun enter(credential: UnlockCredential, submission: PinSubmission?, ensure: () -> Unit, isUnlocked: () -> Boolean)
}

internal class LocalInputUnavailable : Exception()
internal data class PreparedCredentialInput(val input: LocalCredentialInput?, val submission: PinSubmission?)

/** Fallback exists only during preparation, before the durable credential-attempt latch. */
internal suspend fun prepareCredentialInput(
    openPreferred: suspend () -> LocalCredentialInput?,
    credential: UnlockCredential,
    ensure: () -> Unit,
    fallbackPreflight: () -> PinSubmission?,
): PreparedCredentialInput {
    ensure()
    val preferred = try { openPreferred() } catch (_: LocalInputUnavailable) { null }
    if (preferred != null) {
        try {
            ensure()
            return PreparedCredentialInput(preferred, preferred.preflight(credential, ensure))
        } catch (_: LocalInputUnavailable) {
            preferred.close()
        } catch (failure: Exception) {
            preferred.close()
            throw failure
        }
    }
    ensure()
    return PreparedCredentialInput(null, fallbackPreflight())
}
