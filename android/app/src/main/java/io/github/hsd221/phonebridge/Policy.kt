package io.github.hsd221.phonebridge


enum class Capability { LOCATION, UI, SCREENSHOT, OPERATE }
data class AppAccess(val read: Boolean = false, val operate: Boolean = false)
class ToolFailure(val code: String, override val message: String, val outcomeUnknown: Boolean = false) : Exception(message)

data class AccessPolicy(
    val pausedAll: Boolean = false,
    val pausedOperations: Boolean = false,
    val capabilities: Set<Capability> = emptySet(),
    val apps: Map<String, AppAccess> = emptyMap(),
    val unlockEnabled: Boolean = false,
) {
    fun requireTool(tool: String) {
        // Status stays available as a non-sensitive way to learn why requests are denied.
        if (tool == "phone_status") return
        if (pausedAll) throw ToolFailure("PAUSED", text(R.string.err_access_paused))
        val capability = when (tool) {
            "phone_location" -> Capability.LOCATION
            "phone_ui" -> Capability.UI
            "phone_screenshot" -> Capability.SCREENSHOT
            "phone_unlock", "phone_open_app", "phone_tap", "phone_swipe", "phone_input", "phone_back" -> Capability.OPERATE
            else -> throw ToolFailure("UNKNOWN_TOOL", text(R.string.err_unknown_tool))
        }
        if (capability == Capability.OPERATE && pausedOperations) throw ToolFailure("OPERATIONS_PAUSED", text(R.string.err_operations_paused))
        val capabilityName = text(when (capability) {
            Capability.LOCATION -> R.string.cap_location
            Capability.UI -> R.string.cap_ui
            Capability.SCREENSHOT -> R.string.cap_screenshot
            Capability.OPERATE -> R.string.cap_operate
        })
        if (capability !in capabilities) throw ToolFailure("CAPABILITY_DENIED", text(R.string.err_capability_disabled, capabilityName))
        if (tool == "phone_unlock" && !unlockEnabled) throw ToolFailure("CAPABILITY_DENIED", text(R.string.err_unlock_authorization_required))
    }

    fun requireApp(packageName: String, operation: Boolean = false) {
        val access = apps[packageName] ?: AppAccess()
        if (if (operation) !access.operate else !access.read) throw ToolFailure("APP_DENIED", text(R.string.err_app_not_authorized, packageName))
    }
}

/** Called on the serialized phone dispatcher, before queueing each request. */
class RequestGate(private val deviceId: String, private val now: () -> Long = System::currentTimeMillis) {
    private val seen = LinkedHashMap<String, Long>()
    fun accept(id: String, target: String, protocol: Int, issuedAt: Long, expiresAt: Long) {
        val time = now()
        if (target != deviceId || protocol != 1) throw ToolFailure("IDENTITY_MISMATCH", text(R.string.err_identity_mismatch))
        try { if (java.util.UUID.fromString(id).toString() != id.lowercase()) throw IllegalArgumentException() }
        catch (_: IllegalArgumentException) { throw ToolFailure("INVALID_REQUEST", text(R.string.err_request_id_invalid)) }
        if (expiresAt <= time || issuedAt > time + 30000 || expiresAt <= issuedAt || expiresAt - issuedAt > 60000) {
            throw ToolFailure("EXPIRED", text(R.string.err_request_expired))
        }
        seen.entries.removeAll { it.value < time - 60000 }
        if (seen.containsKey(id)) throw ToolFailure("REPLAY_REJECTED", text(R.string.err_request_replayed))
        if (seen.size >= 1024) throw ToolFailure("BUSY", text(R.string.err_request_capacity))
        seen[id] = expiresAt
    }
}
