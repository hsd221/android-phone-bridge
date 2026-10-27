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
        val uri = try { URI(raw.trim().trimEnd('/')) } catch (_: Exception) { throw ToolFailure("INVALID_ADDRESS", "Bridge 地址格式错误") }
        if (uri.scheme !in setOf("https", "http") || uri.host.isNullOrEmpty() || uri.rawUserInfo != null ||
            uri.rawQuery != null || uri.rawFragment != null || !uri.path.isNullOrEmpty()) throw ToolFailure("INVALID_ADDRESS", "请输入 HTTPS Bridge 根地址，不包含路径、账号或参数")
        if (uri.scheme == "http" && !allowInsecure) throw ToolFailure("TLS_REQUIRED", "远程配对需要 HTTPS；本地开发需明确启用明文选项")
        return uri.toString()
    }
    suspend fun pair(url: String, code: String, prefs: BridgePrefs, insecure: Boolean): PhoneIdentity = withContext(Dispatchers.IO) {
        val normalized = normalizeUrl(url, insecure)
        val payload = JSONObject().put("code", code.trim().uppercase()).put("deviceId", prefs.deviceId)
            .put("label", "${Build.MANUFACTURER} ${Build.MODEL}".take(80))
        client.newCall(Request.Builder().url("$normalized/pair").post(payload.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { response ->
            if (!response.isSuccessful) throw ToolFailure("PAIRING_FAILED", "配对失败（HTTP ${response.code}），请检查地址、配对码和有效期")
            val result = JSONObject(response.body?.string() ?: "{}")
            if (result.optInt("protocol") != 1 || result.optString("deviceId") != prefs.deviceId || result.optString("token").length < 32) throw ToolFailure("PAIRING_FAILED", "Bridge 返回身份无效")
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
