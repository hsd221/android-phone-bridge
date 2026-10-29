package io.github.hsd221.phonebridge

import android.annotation.SuppressLint
import android.content.Context
import org.json.JSONObject

enum class UnlockKind(val label: String) { PIN("数字 PIN"), PASSWORD("文字密码"), PATTERN("图案") }

/** Never include a credential in diagnostics, tool results, intents, or saved view state. */
class UnlockCredential(val kind: UnlockKind, val value: String) {
    init {
        require(when (kind) {
            UnlockKind.PIN -> value.length in 4..16 && value.all { it in '0'..'9' }
            UnlockKind.PASSWORD -> value.length in 4..64 && value.none { it.isISOControl() }
            UnlockKind.PATTERN -> value.length in 4..9 && value.all { it in '1'..'9' } &&
                value.toSet().size == value.length && PatternSequence.normalize(value) == value
        }) { "格式无效：PIN 为 4–16 位数字；密码为 4–64 个字符；图案至少连接 4 个不同的点" }
    }
    override fun toString() = "UnlockCredential(${kind.name}, [redacted])"
}

/** Android inserts an unvisited midpoint when a pattern crosses it. */
object PatternSequence {
    fun normalize(value: String): String {
        require(value.all { it in '1'..'9' }) { "图案节点无效" }
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
            throw ToolFailure("LOCAL_CREDENTIAL_UNAVAILABLE", "本地解锁凭据无法读取，请在手机上重新配置")
        }
    }
    fun save(credential: UnlockCredential) {
        val encrypted = vault.encrypt(JSONObject().put("kind", credential.kind.name).put("value", credential.value).toString())
        check(preferences.edit().putString("credential", encrypted).putBoolean("attemptPending", false).commit()) { "无法保存本地解锁配置" }
    }
    fun beginAttempt() {
        if (retryBlocked) throw ToolFailure("UNLOCK_RETRY_BLOCKED", "上次自动解锁未确认成功，请在手机本地检查凭据并重新允许尝试")
        // Persist BEFORE the first credential action: crashes/cancellation must not enable retries.
        check(preferences.edit().putBoolean("attemptPending", true).commit()) { "无法记录本地解锁状态" }
    }
    fun allowNextAttempt() {
        check(preferences.edit().putBoolean("attemptPending", false).commit()) { "无法保存本地解锁状态" }
    }
    fun clear() {
        check(preferences.edit().clear().commit()) { "无法删除本地解锁凭据" }
        vault.destroy()
    }
}
