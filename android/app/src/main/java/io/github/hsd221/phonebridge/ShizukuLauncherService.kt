package io.github.hsd221.phonebridge

import android.os.Process
import java.util.concurrent.TimeUnit

/** Shizuku UserService: the only privileged action is a validated explicit app launch. */
class ShizukuLauncherService : IAppLauncher.Stub() {
    override fun uid(): Int = Process.myUid()
    override fun launch(packageName: String, activityName: String): Int {
        if (uid() != 2000) return -2 // Root is outside the tested/supported demo path.
        if (!packageName.matches(Regex("[A-Za-z][\\w]*(\\.[\\w]+)+")) ||
            !activityName.matches(Regex("[A-Za-z_][A-Za-z0-9_.$]*")) || activityName.length > 255) return -3
        // No shell interpreter, command text, user-supplied flags, or arbitrary executable.
        val process = ProcessBuilder("/system/bin/am", "start", "-n", "$packageName/$activityName").redirectErrorStream(true)
            .redirectOutput(java.io.File("/dev/null")).start()
        if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroy(); return -4 }
        return process.exitValue()
    }
}
