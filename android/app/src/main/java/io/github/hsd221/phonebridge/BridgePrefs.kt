package io.github.hsd221.phonebridge

import android.content.Context
import android.annotation.SuppressLint
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class PhoneIdentity(val url: String, val token: String, val deviceId: String, val insecure: Boolean)

// Authorization changes must be durable before reporting success to the user.
@SuppressLint("ApplySharedPref")
class BridgePrefs(context: Context) {
    val preferences = context.getSharedPreferences("bridge", Context.MODE_PRIVATE)
    private val vault = IdentityVault()
    val deviceId: String get() = preferences.getString("deviceId", null) ?: UUID.randomUUID().toString().also {
        preferences.edit().putString("deviceId", it).commit()
    }
    var identity: PhoneIdentity?
        get() = readIdentity("identity")
        set(value) { saveIdentity("identity", value) }
    var pendingRevocation: PhoneIdentity?
        get() = readIdentity("pendingRevocation")
        set(value) { saveIdentity("pendingRevocation", value) }
    var shizukuEnabled: Boolean
        get() = preferences.getBoolean("shizukuEnabled", false)
        set(value) { preferences.edit().putBoolean("shizukuEnabled", value).apply() }
    fun policy(): AccessPolicy {
        val apps = JSONObject(preferences.getString("apps", "{}")!!)
        return AccessPolicy(preferences.getBoolean("pausedAll", false), preferences.getBoolean("pausedOperations", false),
            Capability.entries.filter { preferences.getBoolean("cap_${it.name}", false) }.toSet(),
            apps.keys().asSequence().associateWith { val app = apps.getJSONObject(it); AppAccess(app.optBoolean("read"), app.optBoolean("operate")) })
    }
    fun setApp(packageName: String, access: AppAccess) {
        val apps = JSONObject(preferences.getString("apps", "{}")!!)
        apps.put(packageName, JSONObject().put("read", access.read).put("operate", access.operate))
        preferences.edit().putString("apps", apps.toString()).commit()
    }
    fun setFlag(key: String, value: Boolean) { preferences.edit().putBoolean(key, value).commit() }
    private fun readIdentity(key: String): PhoneIdentity? {
        val encrypted = preferences.getString(key, null) ?: return null
        return try {
            val json = JSONObject(vault.decrypt(encrypted))
            PhoneIdentity(json.getString("url"), json.getString("token"), json.getString("deviceId"), json.optBoolean("insecure"))
        } catch (_: Exception) { null }
    }
    private fun saveIdentity(key: String, value: PhoneIdentity?) {
        val editor = preferences.edit()
        if (value == null) editor.remove(key)
        else editor.putString(key, vault.encrypt(JSONObject().put("url", value.url).put("token", value.token)
            .put("deviceId", value.deviceId).put("insecure", value.insecure).toString()))
        check(editor.commit()) { "无法保存连接身份" }
    }
}

private class IdentityVault {
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("phone-bridge-identity", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("phone-bridge-identity", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
    fun decrypt(value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12))) }
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }
}
