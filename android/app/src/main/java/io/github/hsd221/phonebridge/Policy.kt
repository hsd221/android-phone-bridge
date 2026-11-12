package io.github.hsd221.phonebridge


enum class Capability { LOCATION, UI, SCREENSHOT, OPERATE, DEVICE, APPS, NOTIFICATIONS, CLIPBOARD, MEDIA }

/** The externally callable phone tools that can be enabled independently. */
object BridgeTools {
    val all = listOf(
        "phone_device_info", "phone_apps", "phone_notifications", "phone_clipboard", "phone_set_clipboard", "phone_media",
        "phone_location", "phone_ui", "phone_screenshot", "phone_open_app",
        "phone_tap", "phone_swipe", "phone_input", "phone_back", "phone_key", "phone_unlock", "phone_lock",
    )
    val operations = setOf("phone_unlock", "phone_lock", "phone_open_app", "phone_tap", "phone_swipe", "phone_input", "phone_back", "phone_key", "phone_set_clipboard")

    fun capability(tool: String) = when (tool) {
        "phone_device_info" -> Capability.DEVICE
        "phone_apps" -> Capability.APPS
        "phone_notifications" -> Capability.NOTIFICATIONS
        "phone_clipboard", "phone_set_clipboard" -> Capability.CLIPBOARD
        "phone_media" -> Capability.MEDIA
        "phone_location" -> Capability.LOCATION
        "phone_ui" -> Capability.UI
        "phone_screenshot" -> Capability.SCREENSHOT
        "phone_unlock", "phone_lock", "phone_open_app", "phone_tap", "phone_swipe", "phone_input", "phone_back", "phone_key" -> Capability.OPERATE
        else -> null
    }

    fun toolsFor(capabilities: Set<Capability>) = all.filter { capability(it) in capabilities }.toSet()
    fun capabilitiesFor(tools: Set<String>) = tools.mapNotNull(::capability).toSet()
}
data class AppAccess(val read: Boolean = false, val operate: Boolean = false)
class ToolFailure(val code: String, override val message: String, val outcomeUnknown: Boolean = false) : Exception(message)

data class AccessPolicy(
    val pausedAll: Boolean = false,
    val pausedOperations: Boolean = false,
    val capabilities: Set<Capability> = emptySet(),
    val apps: Map<String, AppAccess> = emptyMap(),
    val unlockEnabled: Boolean = false,
    val tools: Set<String> = emptySet(),
    val toolPermissionsConfigured: Boolean = false,
) {
    fun requireTool(tool: String) {
        // Status stays available as a non-sensitive way to learn why requests are denied.
        if (tool == "phone_status") return
        if (pausedAll) throw ToolFailure("PAUSED", text(R.string.err_access_paused))
        val capability = BridgeTools.capability(tool) ?: throw ToolFailure("UNKNOWN_TOOL", text(R.string.err_unknown_tool))
        if (tool in BridgeTools.operations && pausedOperations) throw ToolFailure("OPERATIONS_PAUSED", text(R.string.err_operations_paused))
        val allowed = if (tool == "phone_lock" || toolPermissionsConfigured || tools.isNotEmpty()) tool in tools else capability in capabilities
        if (!allowed) throw ToolFailure("CAPABILITY_DENIED", text(R.string.err_tool_disabled, tool))
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
