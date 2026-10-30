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
        if (!Shizuku.pingBinder()) throw ToolFailure("SHIZUKU_UNAVAILABLE", "请先在 Shizuku 中启动服务；非 Root 设备重启后需重新启动")
        Shizuku.requestPermission(101)
    }
    suspend fun launch(component: ComponentName, ensure: () -> Unit): JSONObject {
        if (!status().optBoolean("available")) throw ToolFailure("SHIZUKU_UNAVAILABLE", "Shizuku 未启动、未授权或不是非 Root 模式")
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
                            if (continuation.isActive) continuation.resumeWithException(ToolFailure("SHIZUKU_UNAVAILABLE", "Shizuku 服务已断开"))
                        }
                    }
                    Shizuku.bindUserService(args, connection!!)
                }
            }
            ensure()
            if (launcher.uid() != 2000) throw ToolFailure("SHIZUKU_UNAVAILABLE", "仅支持非 Root Shizuku")
            val result = withContext(Dispatchers.IO) { ensure(); launcher.launch(component.packageName, component.className) }
            if (result != 0) throw ToolFailure("ACTION_REJECTED", "Shizuku 应用启动失败：$result", outcomeUnknown = result == -4)
            return JSONObject().put("accepted", true).put("path", "shizuku")
        } finally { connection?.let { runCatching { Shizuku.unbindUserService(args, it, true) } } }
    }
}
