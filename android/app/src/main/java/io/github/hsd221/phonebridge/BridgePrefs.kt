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
class BridgePrefs(private val context: Context) {
    companion object { const val LIVE_UPDATES_ENABLED = "liveUpdatesEnabled" }
    val preferences = context.getSharedPreferences("bridge", Context.MODE_PRIVATE)
    private val vault = IdentityVault()
    val deviceId: String get() = preferences.getString("deviceId", null) ?: UUID.randomUUID().toString().also {
        preferences.edit().putString("deviceId", it).commit()
    }
    var identity: PhoneIdentity?
        get() = readIdentity("identity")
        set(value) {
            if (value == null) { unlockEnabled = false; LocalUnlockStore(context).clear() }
            saveIdentity("identity", value)
        }
    var pendingRevocation: PhoneIdentity?
        get() = readIdentity("pendingRevocation")
        set(value) { saveIdentity("pendingRevocation", value) }
    var shizukuEnabled: Boolean
        get() = preferences.getBoolean("shizukuEnabled", false)
        set(value) { setFlag("shizukuEnabled", value) }
    var liveUpdatesEnabled: Boolean
        get() = preferences.getBoolean(LIVE_UPDATES_ENABLED, false)
        set(value) { setFlag(LIVE_UPDATES_ENABLED, value) }
    var unlockEnabled: Boolean
        get() = preferences.getBoolean("unlockEnabled", false)
        set(value) { setFlag("unlockEnabled", value); if (!value) UnlockDiagnostics.clear() }
    fun policy(): AccessPolicy {
        val apps = JSONObject(preferences.getString("apps", "{}")!!)
        val legacyCapabilities = Capability.entries.filter { preferences.getBoolean("cap_${it.name}", false) }.toSet()
        val configured = preferences.getBoolean("tool_permissions_configured", false) ||
            BridgeTools.all.any { preferences.contains("tool_$it") }
        val tools = if (configured) {
            BridgeTools.all.filter { preferences.getBoolean("tool_$it", false) }.toSet()
        } else {
            // Existing installations used capability switches. Read them as a
            // compatibility view until the user saves tool settings.
            // The new global-key tool needs its own opt-in after an upgrade.
            BridgeTools.toolsFor(legacyCapabilities) - "phone_key"
        }
        return AccessPolicy(
            pausedAll = preferences.getBoolean("pausedAll", false),
            pausedOperations = preferences.getBoolean("pausedOperations", false),
            capabilities = if (configured) BridgeTools.capabilitiesFor(tools) else legacyCapabilities,
            apps = apps.keys().asSequence().associateWith { val app = apps.getJSONObject(it); AppAccess(app.optBoolean("read"), app.optBoolean("operate")) },
            unlockEnabled = unlockEnabled,
            tools = tools,
            toolPermissionsConfigured = configured,
        )
    }
    fun setApp(packageName: String, access: AppAccess) {
        setApps(mapOf(packageName to access))
    }
    fun setApps(updates: Map<String, AppAccess>) = setPermissions(updates)
    fun setCapabilities(enabled: Boolean) = setPermissions(capabilities = if (enabled) Capability.entries.toSet() else emptySet())
    fun setTools(enabled: Boolean) = setPermissions(tools = if (enabled) BridgeTools.all.toSet() else emptySet())
    /** One durable transaction, including when all applications and tool permissions are enabled together. */
    fun setPermissions(updates: Map<String, AppAccess> = emptyMap(), capabilities: Set<Capability>? = null, tools: Set<String>? = null) {
        val editor = preferences.edit()
        if (updates.isNotEmpty()) {
            val apps = JSONObject(preferences.getString("apps", "{}")!!)
            updates.forEach { (packageName, access) ->
                apps.put(packageName, JSONObject().put("read", access.read).put("operate", access.operate))
            }
            editor.putString("apps", apps.toString())
        }
        val selectedTools = when {
            tools != null -> tools.intersect(BridgeTools.all.toSet())
            capabilities != null -> BridgeTools.toolsFor(capabilities)
            else -> null
        }
        selectedTools?.let { enabled ->
            editor.putBoolean("tool_permissions_configured", true)
            BridgeTools.all.forEach { editor.putBoolean("tool_$it", it in enabled) }
            val derivedCapabilities = BridgeTools.capabilitiesFor(enabled)
            Capability.entries.forEach { editor.putBoolean("cap_${it.name}", it in derivedCapabilities) }
        }
        check(editor.commit()) { text(R.string.err_permissions_save_failed) }
    }
    fun setFlag(key: String, value: Boolean) {
        // Keep the old capability-key API coherent for integrations and test
        // provisioning while the durable source of truth is tool permissions.
        if (key.startsWith("cap_")) {
            val capability = runCatching { Capability.valueOf(key.removePrefix("cap_")) }.getOrNull()
            if (capability != null) {
                val enabled = policy().tools.toMutableSet()
                BridgeTools.all.filter { BridgeTools.capability(it) == capability }.forEach { if (value) enabled.add(it) else enabled.remove(it) }
                setPermissions(tools = enabled)
                return
            }
        }
        check(preferences.edit().putBoolean(key, value).commit()) { text(R.string.err_settings_save_failed) }
    }
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
        check(editor.commit()) { text(R.string.err_identity_save_failed) }
    }
}

internal class IdentityVault(private val alias: String = "phone-bridge-identity") {
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
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
    fun destroy() { KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) } }
}
