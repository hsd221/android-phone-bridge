package io.github.hsd221.phonebridge

import org.junit.Assert.*
import org.junit.Test

class ToolCallStatusTest {
    @Test fun idleHasNoCurrentTool() {
        val calls = ToolCallStatus()
        assertEquals(0, calls.count)
        assertNull(calls.currentTool)
    }

    @Test fun completingACallKeepsTheNextAcceptedCallVisible() {
        val calls = ToolCallStatus()
        calls.begin("first", "phone_location")
        calls.begin("second", "phone_ui")
        assertEquals(2, calls.count)
        assertEquals("phone_location", calls.currentTool)
        calls.finish("first")
        assertEquals(1, calls.count)
        assertEquals("phone_ui", calls.currentTool)
        calls.finish("second")
        assertEquals(0, calls.count)
        assertNull(calls.currentTool)
    }

    @Test fun cancellingAQueuedCallDoesNotHideTheRunningCall() {
        val calls = ToolCallStatus()
        calls.begin("running", "phone_location")
        calls.begin("cancelled", "phone_ui")
        calls.finish("cancelled")
        assertEquals(1, calls.count)
        assertEquals("phone_location", calls.currentTool)
    }

    @Test fun duplicateIdsAndLateCompletionsDoNotInflateTheCount() {
        val calls = ToolCallStatus()
        calls.begin("same", "phone_ui")
        calls.begin("same", "phone_screenshot")
        assertEquals(1, calls.count)
        assertEquals("phone_screenshot", calls.currentTool)
        calls.finish("unknown")
        assertEquals(1, calls.count)
        calls.finish("same")
        calls.finish("same")
        assertEquals(0, calls.count)
    }

    @Test fun disconnectClearsAllCallsAndAllowsAFreshSession() {
        val calls = ToolCallStatus()
        calls.begin("old", "phone_input")
        calls.begin("queued", "phone_back")
        calls.clear()
        calls.finish("old")
        assertEquals(0, calls.count)
        assertNull(calls.currentTool)
        calls.begin("new", "phone_status")
        assertEquals(1, calls.count)
        assertEquals("phone_status", calls.currentTool)
    }
}
