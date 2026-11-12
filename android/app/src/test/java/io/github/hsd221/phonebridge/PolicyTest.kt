package io.github.hsd221.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.UUID

class PolicyTest {
    private fun denied(code: String, action: () -> Unit) = assertEquals(code, assertThrows(ToolFailure::class.java, action).code)
    @Test fun binaryMediaIsIndependentAndReadOnly() {
        denied("CAPABILITY_DENIED") { AccessPolicy().requireTool("phone_media_read") }
        denied("CAPABILITY_DENIED") { AccessPolicy(capabilities = setOf(Capability.MEDIA)).requireTool("phone_media_read") }
        denied("CAPABILITY_DENIED") {
            AccessPolicy(tools = setOf("phone_media"), toolPermissionsConfigured = true).requireTool("phone_media_read")
        }
        val media = AccessPolicy(tools = setOf("phone_media_read"), toolPermissionsConfigured = true)
        media.requireTool("phone_media_read")
        media.copy(pausedOperations = true).requireTool("phone_media_read")
        denied("PAUSED") { media.copy(pausedAll = true).requireTool("phone_media_read") }
        assertEquals(false, "phone_media_read" in BridgeTools.operations)
        assertEquals(Capability.MEDIA, BridgeTools.capability("phone_media_read"))
    }
    @Test fun defaultDenyAndSeparateReadFromOperate() {
        denied("CAPABILITY_DENIED") { AccessPolicy().requireTool("phone_ui") }
        denied("APP_DENIED") { AccessPolicy().requireApp("com.example.test") }
        val policy = AccessPolicy(capabilities = setOf(Capability.UI, Capability.OPERATE), apps = mapOf("com.example.test" to AppAccess(read = true)))
        policy.requireTool("phone_ui")
        policy.requireApp("com.example.test")
        denied("APP_DENIED") { policy.requireApp("com.example.test", operation = true) }
        denied("APP_DENIED") { policy.requireApp("com.example.other") }
    }
    @Test fun toolPermissionsAreIndependentWithinAnOldCapabilityGroup() {
        val policy = AccessPolicy(tools = setOf("phone_ui"), toolPermissionsConfigured = true)
        policy.requireTool("phone_ui")
        denied("CAPABILITY_DENIED") { policy.requireTool("phone_screenshot") }
        denied("CAPABILITY_DENIED") { policy.requireTool("phone_tap") }
    }
    @Test fun pauseStopsNewOperationsButStatusCanExplain() {
        val all = AccessPolicy(pausedAll = true, capabilities = Capability.entries.toSet())
        all.requireTool("phone_status")
        denied("PAUSED") { all.requireTool("phone_location") }
        val operations = AccessPolicy(pausedOperations = true, capabilities = Capability.entries.toSet())
        operations.requireTool("phone_ui")
        denied("OPERATIONS_PAUSED") { operations.requireTool("phone_input") }
        denied("OPERATIONS_PAUSED") { operations.requireTool("phone_open_app") }
    }
    @Test fun gateRejectsReplayExpiredWrongIdentityAndBadClocks() {
        val gate = RequestGate("phone") { 100000 }
        val id = UUID.randomUUID().toString()
        gate.accept(id, "phone", 1, 99000, 110000)
        denied("REPLAY_REJECTED") { gate.accept(id, "phone", 1, 99000, 110000) }
        denied("EXPIRED") { gate.accept(UUID.randomUUID().toString(), "phone", 1, 90000, 100000) }
        denied("EXPIRED") { gate.accept(UUID.randomUUID().toString(), "phone", 1, 200000, 210000) }
        denied("EXPIRED") { gate.accept(UUID.randomUUID().toString(), "phone", 1, 99000, 200000) }
        denied("IDENTITY_MISMATCH") { gate.accept(UUID.randomUUID().toString(), "other", 1, 99000, 110000) }
        denied("IDENTITY_MISMATCH") { gate.accept(UUID.randomUUID().toString(), "phone", 2, 99000, 110000) }
    }
    @Test fun navigationIsAnIndependentOperationAndCannotBypassPauses() {
        denied("CAPABILITY_DENIED") { AccessPolicy().requireTool("phone_key") }
        AccessPolicy(capabilities = setOf(Capability.OPERATE)).requireTool("phone_key")
        denied("CAPABILITY_DENIED") {
            AccessPolicy(tools = setOf("phone_back"), toolPermissionsConfigured = true).requireTool("phone_key")
        }
        denied("OPERATIONS_PAUSED") {
            AccessPolicy(pausedOperations = true, tools = setOf("phone_key"), toolPermissionsConfigured = true).requireTool("phone_key")
        }
        denied("PAUSED") {
            AccessPolicy(pausedAll = true, tools = setOf("phone_key"), toolPermissionsConfigured = true).requireTool("phone_key")
        }
    }
    @Test fun unlockRequiresOperationPermissionAndItsOwnLocalOptIn() {
        denied("CAPABILITY_DENIED") { AccessPolicy().requireTool("phone_unlock") }
        denied("CAPABILITY_DENIED") { AccessPolicy(capabilities = Capability.entries.toSet()).requireTool("phone_unlock") }
        denied("CAPABILITY_DENIED") { AccessPolicy(unlockEnabled = true).requireTool("phone_unlock") }
        AccessPolicy(capabilities = setOf(Capability.OPERATE), unlockEnabled = true).requireTool("phone_unlock")
        denied("OPERATIONS_PAUSED") {
            AccessPolicy(pausedOperations = true, capabilities = Capability.entries.toSet(), unlockEnabled = true).requireTool("phone_unlock")
        }
        denied("PAUSED") {
            AccessPolicy(pausedAll = true, capabilities = Capability.entries.toSet(), unlockEnabled = true).requireTool("phone_unlock")
        }
    }
    @Test fun lockNeedsAnIndependentToolGrantAndNeverRequiresUnlockAuthorization() {
        denied("CAPABILITY_DENIED") { AccessPolicy().requireTool("phone_lock") }
        denied("CAPABILITY_DENIED") {
            AccessPolicy(capabilities = Capability.entries.toSet(), unlockEnabled = true).requireTool("phone_lock")
        }
        denied("CAPABILITY_DENIED") {
            AccessPolicy(tools = BridgeTools.all.toSet() - "phone_lock", toolPermissionsConfigured = true,
                unlockEnabled = true).requireTool("phone_lock")
        }
        val lockOnly = AccessPolicy(tools = setOf("phone_lock"), toolPermissionsConfigured = true)
        lockOnly.requireTool("phone_lock")
        denied("CAPABILITY_DENIED") { lockOnly.requireTool("phone_unlock") }
        denied("OPERATIONS_PAUSED") { lockOnly.copy(pausedOperations = true).requireTool("phone_lock") }
        denied("PAUSED") { lockOnly.copy(pausedAll = true).requireTool("phone_lock") }
        lockOnly.copy(pausedAll = true).requireTool("phone_status")
        assertEquals(true, "phone_lock" in BridgeTools.operations)
        assertEquals(Capability.OPERATE, BridgeTools.capability("phone_lock"))
    }
}
