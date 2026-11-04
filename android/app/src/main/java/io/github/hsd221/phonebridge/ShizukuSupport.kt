package io.github.hsd221.phonebridge

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ShizukuSupport(private val context: Context) {
    fun status(): JSONObject {
        return try {
            val running = Shizuku.pingBinder()
            val granted = running && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            val uid = if (granted) Shizuku.getUid() else -1
            JSONObject().put("running", running).put("permissionGranted", granted).put("uid", uid)
                .put("available", granted && uid == 2000).put("enhancement", "explicit_app_launch_and_local_unlock")
                .put("restartRequiredAfterReboot", true)
        } catch (_: Exception) { JSONObject().put("running", false).put("permissionGranted", false).put("available", false) }
    }
    fun requestPermission() {
        if (!Shizuku.pingBinder()) throw ToolFailure("SHIZUKU_UNAVAILABLE", text(R.string.err_shizuku_start_required))
        Shizuku.requestPermission(101)
    }
    suspend fun launch(component: ComponentName, ensure: () -> Unit): JSONObject {
        if (!status().optBoolean("available")) throw ToolFailure("SHIZUKU_UNAVAILABLE", text(R.string.err_shizuku_unavailable))
        val args = Shizuku.UserServiceArgs(ComponentName(context, ShizukuLauncherService::class.java)).daemon(false).processNameSuffix("launcher").version(1)
        var connection: ServiceConnection? = null
        try {
            val launcher = withTimeout(8000) {
                suspendCancellableCoroutine<IAppLauncher> { continuation ->
                    connection = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                            if (continuation.isActive) continuation.resume(IAppLauncher.Stub.asInterface(binder))
                        }
                        override fun onServiceDisconnected(name: ComponentName?) {
                            if (continuation.isActive) continuation.resumeWithException(ToolFailure("SHIZUKU_UNAVAILABLE", text(R.string.err_shizuku_disconnected)))
                        }
                    }
                    Shizuku.bindUserService(args, connection!!)
                }
            }
            ensure()
            if (launcher.uid() != 2000) throw ToolFailure("SHIZUKU_UNAVAILABLE", text(R.string.err_shizuku_nonroot_only))
            val result = withContext(Dispatchers.IO) { ensure(); launcher.launch(component.packageName, component.className) }
            if (result != 0) throw ToolFailure("ACTION_REJECTED", text(R.string.err_shizuku_launch_failed, result), outcomeUnknown = result == -4)
            return JSONObject().put("accepted", true).put("path", "shizuku")
        } finally { connection?.let { runCatching { Shizuku.unbindUserService(args, it, true) } } }
    }

    /** Execute one fixed ADB-equivalent primitive through a shell-UID UserService. */
    suspend fun input(action: (IAdbTools) -> Int, ensure: () -> Unit): JSONObject? {
        if (!status().optBoolean("available")) return null
        val args = Shizuku.UserServiceArgs(ComponentName(context, ShizukuAdbService::class.java))
            .daemon(false).processNameSuffix("adb-input").version(2)
        var connection: ServiceConnection? = null
        try {
            val adb = withTimeout(5000) {
                suspendCancellableCoroutine<IAdbTools> { continuation ->
                    connection = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                            if (continuation.isActive) continuation.resume(IAdbTools.Stub.asInterface(binder))
                        }
                        override fun onServiceDisconnected(name: ComponentName?) {
                            if (continuation.isActive) continuation.resumeWithException(ToolFailure("SHIZUKU_UNAVAILABLE", text(R.string.err_shizuku_disconnected)))
                        }
                    }
                    Shizuku.bindUserService(args, connection!!)
                }
            }
            ensure()
            if (adb.uid() != 2000) throw ToolFailure("SHIZUKU_UNAVAILABLE", text(R.string.err_shizuku_nonroot_only))
            val result = withContext(Dispatchers.IO) { ensure(); action(adb) }
            if (result != 0) throw ToolFailure("ACTION_REJECTED", text(R.string.err_adb_input_failed, result), outcomeUnknown = result == -4)
            return JSONObject().put("accepted", true).put("path", "adb")
        } finally { connection?.let { runCatching { Shizuku.unbindUserService(args, it, true) } } }
    }
}
