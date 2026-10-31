package io.github.hsd221.phonebridge

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

/** Counts openings of the app UI, excluding rotation, internal navigation and remote unlock UI. */
class BridgeApplication : Application(), Application.ActivityLifecycleCallbacks {
    var foregroundGeneration = 0; private set
    private var startedActivities = 0
    private var changingConfiguration = false
    private var mainActivity = WeakReference<MainActivity>(null)

    override fun onCreate() { super.onCreate(); registerActivityLifecycleCallbacks(this) }
    override fun onActivityStarted(activity: Activity) {
        if (activity is UnlockActivity) return
        if (startedActivities++ == 0) {
            if (!changingConfiguration) foregroundGeneration++
            changingConfiguration = false
        }
    }
    override fun onActivityStopped(activity: Activity) {
        if (activity is UnlockActivity) return
        if (--startedActivities == 0) changingConfiguration = activity.isChangingConfigurations
    }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (activity is MainActivity) mainActivity = WeakReference(activity)
    }
    override fun onActivityResumed(activity: Activity) {
        // A settings subpage may be the page restored from Recents; check even while Main is stopped.
        if (activity !is UnlockActivity) mainActivity.get()?.checkForUpdates(foregroundGeneration)
    }
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) {
        if (mainActivity.get() === activity) mainActivity.clear()
    }
}
