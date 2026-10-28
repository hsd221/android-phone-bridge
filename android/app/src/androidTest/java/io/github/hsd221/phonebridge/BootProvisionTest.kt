package io.github.hsd221.phonebridge

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Only provisions a dedicated emulator. The host verifies recovery after instrumentation and reboot. */
@RunWith(AndroidJUnit4::class)
class BootProvisionTest {
    @Test fun provision() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        val prefs = BridgePrefs(context)
        assertNull("Will not overwrite an existing phone identity", prefs.identity)
        prefs.identity = BridgeNetwork.pair(args.getString("bridgeUrl")!!, args.getString("pairCode")!!, prefs, true)
        prefs.setFlag("pausedAll", false); prefs.setFlag("pausedOperations", false)
        Capability.entries.forEach { prefs.setFlag("cap_${it.name}", true) }
        listOf("io.github.hsd221.phonebridge.demo", "com.android.launcher3", "com.android.systemui").forEach {
            prefs.setApp(it, AppAccess(read = true, operate = true))
        }
        prefs.shizukuEnabled = false
        instrumentation.runOnMainSync {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { BridgeService.start(context) }
        repeat(60) { if (!BridgeService.connected) delay(200) }
        assertTrue("Provisioned phone did not connect", BridgeService.connected)
        // Keep the encrypted identity for the host-controlled reboot; host revokes it afterward.
    }
}
