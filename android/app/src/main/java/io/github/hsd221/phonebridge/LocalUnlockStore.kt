package io.github.hsd221.phonebridge

import android.annotation.SuppressLint
import android.content.Context
import org.json.JSONObject

enum class UnlockKind(val labelRes: Int) {
    PIN(R.string.unlock_kind_pin), PASSWORD(R.string.unlock_kind_password), PATTERN(R.string.unlock_kind_pattern)
}

/** Never include a credential in diagnostics, tool results, intents, or saved view state. */
class UnlockCredential(val kind: UnlockKind, val value: String) {
    init {
        require(when (kind) {
            UnlockKind.PIN -> value.length in 4..16 && value.all { it in '0'..'9' }
            UnlockKind.PASSWORD -> value.length in 4..64 && value.none { it.isISOControl() }
            UnlockKind.PATTERN -> value.length in 4..9 && value.all { it in '1'..'9' } &&
                value.toSet().size == value.length && PatternSequence.normalize(value) == value
        }) { text(R.string.err_credential_invalid) }
    }
    override fun toString() = "UnlockCredential(${kind.name}, [redacted])"
}

/** Android inserts an unvisited midpoint when a pattern crosses it. */
object PatternSequence {
    fun normalize(value: String): String {
        require(value.all { it in '1'..'9' }) { text(R.string.err_pattern_node_invalid) }
        val result = mutableListOf<Int>()
        for (char in value) {
            val next = char - '1'
            if (next in result) continue
            result.lastOrNull()?.let { previous ->
                val row = previous / 3 + next / 3
                val column = previous % 3 + next % 3
                if (row % 2 == 0 && column % 2 == 0) {
                    val middle = row / 2 * 3 + column / 2
                    if (middle != previous && middle != next && middle !in result) result.add(middle)
                }
            }
            result.add(next)
        }
        return result.joinToString("") { (it + 1).toString() }
    }
}

/** Separate from bridge identity/settings; ciphertext never enters a network serializer. */
@SuppressLint("ApplySharedPref")
class LocalUnlockStore(context: Context) {
    private val preferences = context.getSharedPreferences("local_unlock", Context.MODE_PRIVATE)
    private val vault = IdentityVault("phone-bridge-local-unlock")
    val configured get() = preferences.contains("credential")
    val retryBlocked get() = preferences.getBoolean("attemptPending", false)
    fun read(): UnlockCredential? {
        val encrypted = preferences.getString("credential", null) ?: return null
        return try {
            val json = JSONObject(vault.decrypt(encrypted))
            UnlockCredential(UnlockKind.valueOf(json.getString("kind")), json.getString("value"))
        } catch (_: Exception) {
            throw ToolFailure("LOCAL_CREDENTIAL_UNAVAILABLE", text(R.string.err_local_credential_unreadable))
        }
    }
    fun save(credential: UnlockCredential) {
        val encrypted = vault.encrypt(JSONObject().put("kind", credential.kind.name).put("value", credential.value).toString())
        check(preferences.edit().putString("credential", encrypted).putBoolean("attemptPending", false).commit()) { text(R.string.err_local_credential_save_failed) }
    }
    fun beginAttempt() {
        if (retryBlocked) throw ToolFailure("UNLOCK_RETRY_BLOCKED", text(R.string.err_unlock_retry_blocked_credentials))
        // Persist BEFORE the first credential action: crashes/cancellation must not enable retries.
        check(preferences.edit().putBoolean("attemptPending", true).commit()) { text(R.string.err_unlock_attempt_record_failed) }
    }
    fun allowNextAttempt() {
        check(preferences.edit().putBoolean("attemptPending", false).commit()) { text(R.string.err_unlock_state_save_failed) }
    }
    fun clear() {
        check(preferences.edit().clear().commit()) { text(R.string.err_local_credential_delete_failed) }
        vault.destroy()
    }
}
