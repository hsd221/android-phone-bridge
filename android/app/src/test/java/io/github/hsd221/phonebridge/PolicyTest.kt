package io.github.hsd221.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.UUID

class PolicyTest {
    private fun denied(code: String, action: () -> Unit) = assertEquals(code, assertThrows(ToolFailure::class.java, action).code)
    @Test fun defaultDenyAndSeparateReadFromOperate() {
        denied("CAPABILITY_DENIED") { AccessPolicy().requireTool("phone_ui") }
        denied("APP_DENIED") { AccessPolicy().requireApp("com.example.test") }
        val policy = AccessPolicy(capabilities = setOf(Capability.UI, Capability.OPERATE), apps = mapOf("com.example.test" to AppAccess(read = true)))
        policy.requireTool("phone_ui")
        policy.requireApp("com.example.test")
        denied("APP_DENIED") { policy.requireApp("com.example.test", operation = true) }
        denied("APP_DENIED") { policy.requireApp("com.example.other") }
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
}
