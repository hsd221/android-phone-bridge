package io.github.hsd221.phonebridge

import org.json.JSONObject

/** In-memory, fixed-vocabulary progress only. Never accept credentials, UI text or exception messages. */
internal object UnlockDiagnostics {
    enum class Stage { CHECKING, WAKING, WAITING_FOR_BOUNCER, WAITING_FOR_KEYGUARD, REVEALING, PREFLIGHT, ENTERING_CREDENTIAL, VERIFYING }
    enum class Dismiss { NOT_REQUESTED, WAKE_ONLY, SYSTEM }
    enum class Reason {
        NONE, UNRECOGNIZED, CONTAINER_UNAVAILABLE, ENTRY_UNAVAILABLE, ENTRY_NOT_PROTECTED,
        ENTRY_NOT_EDITABLE, ENTRY_NOT_EMPTY, ENTRY_EMPTY_STATE_UNKNOWN, DIGIT_CONTROL_UNAVAILABLE,
        CONFIRM_CONTROL_UNAVAILABLE, AUTO_CONFIRM_UNVERIFIED, PASSWORD_ACTION_UNAVAILABLE, PATTERN_BOUNDS_INVALID,
    }
    private data class Attempt(
        val stage: Stage = Stage.CHECKING,
        val outcome: String = "RUNNING",
        val dismiss: Dismiss = Dismiss.NOT_REQUESTED,
        val revealAttempted: Boolean = false,
        val credentialAttempted: Boolean = false,
        val reason: Reason = Reason.NONE,
    )
    private var last: Attempt? = null
    private val outcomes = setOf(
        "SUCCESS", "USER_UNLOCK_REQUIRED", "UNLOCK_RETRY_BLOCKED", "ACCESSIBILITY_UNAVAILABLE",
        "LOCAL_CREDENTIAL_UNAVAILABLE", "UNLOCK_UNSUPPORTED", "UNLOCK_UI_UNAVAILABLE", "UNLOCK_INTERRUPTED",
        "UNLOCK_FAILED", "CANCELLED", "EXPIRED", "PAUSED", "OPERATIONS_PAUSED", "CAPABILITY_DENIED", "EXECUTION_FAILED",
    )

    fun begin() { last = Attempt() }
    fun clear() { last = null }
    fun stage(value: Stage) { last = last?.copy(stage = value) }
    fun waking(systemDismiss: Boolean) {
        last = last?.copy(stage = Stage.WAKING, dismiss = if (systemDismiss) Dismiss.SYSTEM else Dismiss.WAKE_ONLY)
    }
    fun revealing() { last = last?.copy(stage = Stage.REVEALING, revealAttempted = true) }
    fun enteringCredential() { last = last?.copy(stage = Stage.ENTERING_CREDENTIAL, credentialAttempted = true) }
    fun refused(reason: Reason) { last = last?.copy(reason = reason) }
    fun finish(outcome: String) {
        last = last?.copy(outcome = outcome.takeIf { it in outcomes } ?: "EXECUTION_FAILED")
    }
    fun snapshot(): JSONObject? = last?.let {
        JSONObject().put("stage", it.stage.name.lowercase()).put("outcome", it.outcome)
            .put("systemDismiss", it.dismiss.name.lowercase()).put("revealAttempted", it.revealAttempted)
            .put("credentialAttempted", it.credentialAttempted)
            .put("reason", it.reason.name.lowercase())
    }
}
