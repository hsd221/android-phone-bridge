package io.github.hsd221.phonebridge

import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Two invocations around a normal APK upgrade on a disposable emulator only. */
@RunWith(AndroidJUnit4::class)
class UpgradeCompatibilityTest {
    @Test fun releaseUpgradePreservesLegacyMetadataGrantWithoutEnablingBinaryReads() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("upgradeTest") == "true")
        assertTrue("Dedicated emulator required", Build.HARDWARE in setOf("ranchu", "goldfish"))
        val context = instrumentation.targetContext
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_TEST_ONLY)
        val prefs = BridgePrefs(context)
        assertNull("Refusing to replace an existing paired identity", prefs.identity)
        assertNull(prefs.pendingRevocation)
        when (args.getString("upgradePhase")) {
            "before" -> {
                assertEquals(13L, info.longVersionCode)
                assertEquals("0.3.0", info.versionName)
                assertTrue(prefs.preferences.edit().clear().putBoolean("cap_MEDIA", true)
                    .putBoolean("tool_permissions_configured", true).putBoolean("tool_phone_media", true)
                    .putString("synthetic_upgrade_marker", "synthetic-v13-settings").commit())
                assertTrue("phone_media" in prefs.policy().tools)
            }
            "after" -> {
                assertEquals(BuildConfig.VERSION_CODE.toLong(), info.longVersionCode)
                assertEquals(BuildConfig.VERSION_NAME, info.versionName)
                assertEquals("synthetic-v13-settings", prefs.preferences.getString("synthetic_upgrade_marker", null))
                assertTrue("Legacy metadata grant was lost during APK upgrade", "phone_media" in prefs.policy().tools)
                assertFalse("Upgrade silently granted binary media access", "phone_media_read" in prefs.policy().tools)
            }
            else -> fail("Explicit before/after upgrade phase required")
        }
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "PASS: release upgrade ${args.getString("upgradePhase")}; version=${info.versionName}, code=${info.longVersionCode}, uid=${context.applicationInfo.uid}; synthetic legacy settings checked.\n")
        })
    }
}
