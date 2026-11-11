package io.github.hsd221.phonebridge

import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI

/** Only imports pairing inputs. Scanning never changes HTTP consent, identity, or permissions. */
data class PairingPayload(val url: String, val code: String, val expiresAt: Long) {
    companion object {
        fun parse(raw: String, nowMs: Long = System.currentTimeMillis()): PairingPayload {
            try {
                require(raw.length <= 4096)
                val parser = JSONTokener(raw)
                val json = parser.nextValue() as? JSONObject ?: throw IllegalArgumentException()
                require(parser.nextClean() == '\u0000')
                require(json.keys().asSequence().toSet() == setOf("type", "version", "url", "code", "expiresAt"))
                require(json.get("type") == "phone-bridge-pair")
                val version = json.get("version")
                require((version is Int || version is Long) && (version as Number).toLong() == 1L)
                val url = json.get("url") as? String ?: throw IllegalArgumentException()
                val code = json.get("code") as? String ?: throw IllegalArgumentException()
                require(code.matches(Regex("[0-9A-F]{16}")))
                val expiry = json.get("expiresAt")
                require(expiry is Long || expiry is Int)
                val expiresAt = (expiry as Number).toLong()
                require(expiresAt > nowMs && expiresAt <= 9007199254740991L)
                val uri = URI(url)
                require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrEmpty())
                require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
                require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")
                require(uri.port == -1 || uri.port in 1..65535)
                require(uri.rawAuthority?.endsWith(':') == false)
                return PairingPayload(url.trimEnd('/'), code, expiresAt)
            } catch (_: Exception) {
                // QR contents contain a temporary secret: never expose parser input in errors/logs.
                throw IllegalArgumentException("Invalid or expired Phone Bridge pairing QR code")
            }
        }
    }
}
