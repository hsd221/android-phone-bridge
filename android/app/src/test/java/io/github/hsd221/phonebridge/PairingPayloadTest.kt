package io.github.hsd221.phonebridge

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PairingPayloadTest {
    private val now = 1_700_000_000_000L
    private fun valid() = JSONObject()
        .put("type", "phone-bridge-pair")
        .put("version", 1)
        .put("url", "https://bridge.example.com")
        .put("code", "0123456789ABCDEF")
        .put("expiresAt", now + 300000)

    private fun reject(raw: String) {
        assertThrows("Unsafe QR payload was accepted", IllegalArgumentException::class.java) { PairingPayload.parse(raw, now) }
    }

    @Test fun bridgeContractIsParsedWithoutGeneratingADeviceIdentity() {
        val payload = PairingPayload.parse(valid().toString(), now)
        assertEquals("https://bridge.example.com", payload.url)
        assertEquals("0123456789ABCDEF", payload.code)
        assertEquals(now + 300000, payload.expiresAt)
        val http = PairingPayload.parse(valid().put("url", "http://192.0.2.10:8787").toString(), now)
        assertEquals("http://192.0.2.10:8787", http.url)
    }

    @Test fun wrongKindsVersionsUnknownFieldsAndMissingFieldsAreRejected() {
        reject("https://bridge.example.com/?code=0123456789ABCDEF")
        reject("[]")
        reject("null")
        reject("not JSON")
        reject(valid().toString() + " trailing text")
        reject(valid().toString() + "{}")
        reject(valid().put("type", "other-pairing").toString())
        reject(valid().put("version", 2).toString())
        reject(valid().put("version", "1").toString())
        reject(valid().put("version", 1.5).toString())
        reject(valid().put("insecure", true).toString())
        reject(valid().put("harnessToken", "must-never-be-imported").toString())
        for (field in listOf("type", "version", "url", "code", "expiresAt")) {
            reject(valid().apply { remove(field) }.toString())
            reject(valid().put(field, JSONObject.NULL).toString())
        }
    }

    @Test fun expiryMustBeANonCoercedIntegerAndStrictlyInTheFuture() {
        reject(valid().put("expiresAt", now).toString())
        reject(valid().put("expiresAt", now - 1).toString())
        reject(valid().put("expiresAt", -1).toString())
        reject(valid().put("expiresAt", (now + 300000).toString()).toString())
        reject(valid().put("expiresAt", now + 0.5).toString())
        reject(valid().put("expiresAt", true).toString())
        reject(valid().put("expiresAt", 9007199254740992L).toString())
        assertEquals(now + 1, PairingPayload.parse(valid().put("expiresAt", now + 1).toString(), now).expiresAt)
    }

    @Test fun onlyRootHttpAndHttpsAddressesWithoutCredentialsAreAccepted() {
        for (url in listOf(
            "file:///data/data/io.github.hsd221.phonebridge/files/config",
            "javascript:alert(1)", "ftp://bridge.example.com", "https://", "https:///missing-host",
            "https://user:password@bridge.example.com", "https://bridge.example.com/path",
            "https://bridge.example.com/?token=secret", "https://bridge.example.com/#fragment",
            "https://bridge.example.com:99999", "https://bridge.example.com:-1",
            "https://bridge.example.com\n.evil.example",
        )) reject(valid().put("url", url).toString())
        assertEquals("https://[2001:db8::1]:8787", PairingPayload.parse(valid().put("url", "https://[2001:db8::1]:8787").toString(), now).url)
    }

    @Test fun invalidCodesAndOversizedPayloadsAreRejectedWithoutEchoingTheSecret() {
        for (code in listOf("", "0123456789ABCDE", "0123456789ABCDEFG", "0123456789ABCDEG", "0123456789ABCDEF ", "\n0123456789ABCDEF")) {
            reject(valid().put("code", code).toString())
        }
        reject(valid().put("code", 1234567890123456L).toString())
        reject(" ".repeat(4097))
        val raw = valid().put("url", "https://" + "a".repeat(4200) + ".example.com").toString()
        reject(raw)
        try {
            PairingPayload.parse(valid().put("url", "file:///not-http").toString(), now)
            fail("Expected a failure")
        } catch (failure: IllegalArgumentException) {
            assertFalse(failure.message.orEmpty().contains("0123456789ABCDEF"))
        }
    }
}
