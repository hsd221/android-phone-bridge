package io.github.hsd221.phonebridge

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

object BridgeNetwork {
    val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(35, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS).pingInterval(20, TimeUnit.SECONDS).retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false).build()
    fun normalizeUrl(raw: String, allowInsecure: Boolean): String {
        val uri = try { URI(raw.trim().trimEnd('/')) } catch (_: Exception) { throw ToolFailure("INVALID_ADDRESS", text(R.string.err_bridge_address_invalid)) }
        if (uri.scheme !in setOf("https", "http") || uri.host.isNullOrEmpty() || uri.rawUserInfo != null ||
            uri.rawQuery != null || uri.rawFragment != null || !uri.path.isNullOrEmpty()) throw ToolFailure("INVALID_ADDRESS", text(R.string.err_bridge_root_address_required))
        if (uri.scheme == "http" && !allowInsecure) throw ToolFailure("TLS_REQUIRED", text(R.string.err_https_required))
        return uri.toString()
    }
    suspend fun pair(url: String, code: String, prefs: BridgePrefs, insecure: Boolean): PhoneIdentity = withContext(Dispatchers.IO) {
        val normalized = normalizeUrl(url, insecure)
        val payload = JSONObject().put("code", code.trim().uppercase()).put("deviceId", prefs.deviceId)
            .put("label", "${Build.MANUFACTURER} ${Build.MODEL}".take(80))
        client.newCall(Request.Builder().url("$normalized/pair").post(payload.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { response ->
            if (!response.isSuccessful) throw ToolFailure("PAIRING_FAILED", text(R.string.err_pairing_http_status, response.code))
            val result = JSONObject(response.body?.string() ?: "{}")
            if (result.optInt("protocol") != 1 || result.optString("deviceId") != prefs.deviceId || result.optString("token").length < 32) throw ToolFailure("PAIRING_FAILED", text(R.string.err_bridge_identity_invalid))
            PhoneIdentity(normalized, result.getString("token"), prefs.deviceId, insecure)
        }
    }
    suspend fun revoke(identity: PhoneIdentity): Boolean = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url("${identity.url}/device/revoke").header("Authorization", "Bearer ${identity.token}").delete().build()).execute().use {
                it.isSuccessful || it.code == 401
            }
        } catch (_: Exception) { false }
    }
}
