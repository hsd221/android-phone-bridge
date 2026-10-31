package io.github.hsd221.phonebridge

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource
import java.util.Collections
import java.util.IdentityHashMap

/** Replaces the loader before onResume; no production hook and no public network in instrumentation. */
class AppUpdateFixture : ExternalResource(), Application.ActivityLifecycleCallbacks {
    internal var requestFactory: (() -> UpdateCheckRequest)? = null
    private val injected = Collections.newSetFromMap(IdentityHashMap<UpdateCheckRequest, Boolean>())
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val application get() = instrumentation.targetContext.applicationContext as Application

    override fun before() {
        instrumentation.runOnMainSync { application.registerActivityLifecycleCallbacks(this) }
    }

    override fun after() {
        instrumentation.runOnMainSync {
            application.unregisterActivityLifecycleCallbacks(this)
            injected.clear()
            requestFactory = null
        }
    }

    override fun onActivityPostCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (activity !is MainActivity) return
        val field = MainActivity::class.java.getDeclaredField("updateCheckRequest").apply { isAccessible = true }
        val original = field.get(activity) as UpdateCheckRequest
        if (original in injected) return // Retained requests must survive rotation with their generation.
        val request = requestFactory?.invoke() ?: UpdateCheckRequest(load = { currentRelease() })
        request.changed = original.changed
        original.close()
        field.set(activity, request)
        injected.add(request)
        request.changed?.invoke()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    companion object {
        internal fun currentRelease() = AppRelease(
            "v${BuildConfig.VERSION_NAME}", requireNotNull(AppVersion.parse(BuildConfig.VERSION_NAME)),
            "${AppUpdates.RELEASES_URL}/download/v${BuildConfig.VERSION_NAME}/phone-bridge-${BuildConfig.VERSION_NAME}-debug.apk", "",
        )
    }
}
