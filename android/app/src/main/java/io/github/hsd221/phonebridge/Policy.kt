package io.github.hsd221.phonebridge

enum class Capability { LOCATION, UI, SCREENSHOT, OPERATE }
data class AppAccess(val read: Boolean = false, val operate: Boolean = false)
class ToolFailure(val code: String, override val message: String) : Exception(message)

data class AccessPolicy(
    val pausedAll: Boolean = false,
    val pausedOperations: Boolean = false,
    val capabilities: Set<Capability> = emptySet(),
    val apps: Map<String, AppAccess> = emptyMap(),
) {
    fun requireTool(tool: String) {
        // Status stays available as a non-sensitive way to learn why requests are denied.
        if (tool == "phone_status") return
        if (pausedAll) throw ToolFailure("PAUSED", "用户已暂停全部访问")
        val capability = when (tool) {
            "phone_location" -> Capability.LOCATION
            "phone_ui" -> Capability.UI
            "phone_screenshot" -> Capability.SCREENSHOT
            "phone_open_app", "phone_tap", "phone_swipe", "phone_input", "phone_back" -> Capability.OPERATE
            else -> throw ToolFailure("UNKNOWN_TOOL", "手机不支持此工具")
        }
        if (capability == Capability.OPERATE && pausedOperations) throw ToolFailure("OPERATIONS_PAUSED", "用户已暂停操作")
        if (capability !in capabilities) throw ToolFailure("CAPABILITY_DENIED", "用户未开放此能力：$capability")
    }

    fun requireApp(packageName: String, operation: Boolean = false) {
        val access = apps[packageName] ?: AppAccess()
        if (if (operation) !access.operate else !access.read) throw ToolFailure("APP_DENIED", "未授权访问应用：$packageName")
    }
}

/** Called on the serialized phone dispatcher, before queueing each request. */
class RequestGate(private val deviceId: String, private val now: () -> Long = System::currentTimeMillis) {
    private val seen = LinkedHashMap<String, Long>()
    fun accept(id: String, target: String, protocol: Int, issuedAt: Long, expiresAt: Long) {
        val time = now()
        if (target != deviceId || protocol != 1) throw ToolFailure("IDENTITY_MISMATCH", "设备或协议不匹配")
        try { if (java.util.UUID.fromString(id).toString() != id.lowercase()) throw IllegalArgumentException() }
        catch (_: IllegalArgumentException) { throw ToolFailure("INVALID_REQUEST", "请求 ID 无效") }
        if (expiresAt <= time || issuedAt > time + 30000 || expiresAt <= issuedAt || expiresAt - issuedAt > 60000) {
            throw ToolFailure("EXPIRED", "请求已过期或手机与 Bridge 时钟不一致")
        }
        seen.entries.removeAll { it.value < time - 60000 }
        if (seen.containsKey(id)) throw ToolFailure("REPLAY_REJECTED", "请求 ID 已使用，不重复执行")
        if (seen.size >= 1024) throw ToolFailure("BUSY", "请求记录已满")
        seen[id] = expiresAt
    }
}
