package io.github.hsd221.phonebridge

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.math.min
import kotlin.random.Random

class BridgeService : Service() {
    companion object {
        @Volatile var connected = false; private set
        @Volatile var linkStatus = "尚未启动"; private set
        @Volatile var activityVisible = false
        fun start(context: Context, action: String? = null) {
            try { context.startForegroundService(Intent(context, BridgeService::class.java).setAction(action)) }
            catch (_: Exception) { linkStatus = "系统阻止后台启动，请打开 APK 后重试" }
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var prefs: BridgePrefs
    private lateinit var tools: PhoneTools
    private var loop: Job? = null
    private var socket: WebSocket? = null
    private var gate: RequestGate? = null
    private var gateDeviceId: String? = null
    private val epoch = AtomicLong(0)
    private val jobs = mutableMapOf<String, Job>()
    private val mutex = Mutex()
    private var defaultNetwork: Network? = null
    var locationForeground = false; private set
    private val changes = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        epoch.incrementAndGet()
        cancelRequests("用户已更改授权或连接身份")
        PhoneAccessibilityService.instance?.invalidate()
        if (key == "identity") socket?.cancel()
        updateNotification()
    }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { scope.launch {
            if (defaultNetwork != null && defaultNetwork != network) socket?.cancel()
            defaultNetwork = network
        } }
        override fun onLost(network: Network) { scope.launch {
            if (defaultNetwork == network) { defaultNetwork = null; socket?.cancel() }
        } }
    }
    override fun onCreate() {
        super.onCreate()
        prefs = BridgePrefs(this); tools = PhoneTools(this, prefs)
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("bridge", "手机工具桥连接", NotificationManager.IMPORTANCE_LOW))
        prefs.preferences.registerOnSharedPreferenceChangeListener(changes)
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "pause_all" -> prefs.setFlag("pausedAll", true)
            "pause_operations" -> prefs.setFlag("pausedOperations", true)
            "reconnect" -> socket?.cancel()
        }
        val locationAllowed = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val backgroundLocation = checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        var types = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else 0
        locationForeground = locationAllowed && (activityVisible || backgroundLocation)
        if (locationForeground) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        try { startForeground(1, notification(), types) }
        catch (_: SecurityException) {
            locationForeground = false
            startForeground(1, notification(), if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else 0)
        }
        if (prefs.identity == null && prefs.pendingRevocation == null) { stopSelf(); return START_NOT_STICKY }
        if (loop?.isActive != true) loop = scope.launch { connectionLoop() }
        return START_STICKY
    }
    private suspend fun connectionLoop() {
        var attempt = 0
        while (scope.isActive) {
            prefs.pendingRevocation?.let { pending -> if (BridgeNetwork.revoke(pending)) prefs.pendingRevocation = null }
            val identity = prefs.identity
            if (identity == null) {
                connected = false
                if (prefs.pendingRevocation == null) { linkStatus = "未配对"; stopSelf(); return }
                linkStatus = "已本机解绑，等待网络撤销 Bridge 身份"; updateNotification(); delay(10000); continue
            }
            if (gateDeviceId != identity.deviceId) { gate = RequestGate(identity.deviceId); gateDeviceId = identity.deviceId }
            linkStatus = "正在连接"; updateNotification()
            try { runSession(identity); attempt = if (connected) 0 else attempt + 1 }
            catch (_: Exception) { attempt++ }
            finally { connected = false; epoch.incrementAndGet(); cancelRequests("连接中断，不重放请求"); PhoneAccessibilityService.instance?.invalidate() }
            if (prefs.identity == null) continue
            linkStatus = "连接中断，自动重连中"; updateNotification()
            delay(min(30000L, 1000L shl attempt.coerceIn(0, 5)) + Random.nextLong(500))
        }
    }
    private suspend fun runSession(identity: PhoneIdentity) = suspendCancellableCoroutine { continuation ->
        val url = identity.url.replaceFirst("https:", "wss:").replaceFirst("http:", "ws:") + "/device"
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { scope.launch {
                if (!continuation.isActive || prefs.identity != identity) { webSocket.cancel(); return@launch }
                linkStatus = "正在验证连接"; updateNotification()
                webSocket.send(JSONObject().put("type", "hello").put("protocol", 1).put("deviceId", identity.deviceId).toString())
            } }
            override fun onMessage(webSocket: WebSocket, text: String) { scope.launch {
                if (!continuation.isActive || webSocket !== socket) return@launch
                if (text.length > 65536) { webSocket.close(1008, "Request too large"); return@launch }
                try {
                    val message = JSONObject(text)
                    when (message.getString("type")) {
                        "ready" -> {
                            if (message.getInt("protocol") != 1 || message.getString("deviceId") != identity.deviceId) {
                                webSocket.close(1008, "Identity mismatch"); return@launch
                            }
                            connected = true; linkStatus = "已连接"; updateNotification()
                        }
                        "request" -> accept(message, webSocket, identity)
                        "cancel" -> jobs[message.getString("id")]?.cancel(CancellationException("Bridge 已取消请求"))
                        else -> webSocket.close(1008, "Unknown message")
                    }
                } catch (_: Exception) { webSocket.close(1008, "Invalid request") }
            } }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { scope.launch {
                if (code == 4001 && reason == "REVOKED" && prefs.identity == identity) prefs.identity = null
                if (continuation.isActive) continuation.resume(Unit)
            } }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { scope.launch {
                if (response?.code == 401 && prefs.identity == identity) { prefs.identity = null; linkStatus = "连接身份无效，请重新配对" }
                if (continuation.isActive) continuation.resume(Unit)
            } }
        }
        val current = BridgeNetwork.client.newWebSocket(Request.Builder().url(url).header("Authorization", "Bearer ${identity.token}").build(), listener)
        socket = current
        continuation.invokeOnCancellation { current.cancel() }
    }
    private fun accept(message: JSONObject, webSocket: WebSocket, identity: PhoneIdentity) {
        val id = message.optString("id")
        val tool = message.optString("tool")
        try {
            gate!!.accept(id, message.getString("deviceId"), message.getInt("protocol"), message.getLong("issuedAt"), message.getLong("expiresAt"))
            if (jobs.size >= 8) throw ToolFailure("BUSY", "手机请求队列已满")
            prefs.policy().requireTool(tool)
        } catch (failure: ToolFailure) { sendResult(webSocket, identity, id, error(failure.code, failure.message)); return }
        val acceptedEpoch = epoch.get()
        val deadline = message.getLong("expiresAt")
        val args = message.getJSONObject("args")
        var started = false
        val operation = tool in setOf("phone_open_app", "phone_tap", "phone_swipe", "phone_input", "phone_back")
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val ensure = {
                coroutineContext.ensureActive()
                if (epoch.get() != acceptedEpoch || socket !== webSocket || !connected) throw ToolFailure("CANCELLED", "连接或授权状态已改变")
                if (System.currentTimeMillis() >= deadline) throw ToolFailure("EXPIRED", "请求已过期")
                prefs.policy().requireTool(tool)
            }
            val result = try {
                withTimeout((deadline - System.currentTimeMillis()).coerceAtLeast(1)) {
                    mutex.withLock {
                        ensure(); started = true
                        JSONObject().put("ok", true).put("data", tools.execute(tool, args, ensure))
                    }
                }
            } catch (failure: ToolFailure) { error(failure.code, failure.message, failure.outcomeUnknown) }
            catch (_: kotlinx.coroutines.TimeoutCancellationException) { error("TIMEOUT", "请求或系统能力获取超时，已派发的操作可能已执行", operation && started) }
            catch (_: CancellationException) { error("CANCELLED", "请求已取消；已交给系统的动作可能已执行", operation && started) }
            catch (_: SecurityException) { error("PERMISSION_REQUIRED", "Android 系统拒绝访问，请检查权限", operation && started) }
            catch (_: Exception) { error("EXECUTION_FAILED", "工具执行失败，请检查系统与应用状态", operation && started) }
            sendResult(webSocket, identity, id, result)
        }
        jobs[id] = job
        job.invokeOnCompletion { jobs.remove(id) }
        job.start()
    }
    private fun error(code: String, message: String, unknown: Boolean = false) = JSONObject().put("ok", false)
        .put("error", JSONObject().put("code", code).put("message", message).apply { if (unknown) put("outcomeUnknown", true) })
    private fun sendResult(webSocket: WebSocket, identity: PhoneIdentity, id: String, result: JSONObject) {
        if (webSocket === socket && connected && prefs.identity == identity) webSocket.send(JSONObject().put("type", "result").put("id", id).put("deviceId", identity.deviceId).put("result", result).toString())
    }
    private fun cancelRequests(reason: String) { jobs.values.toList().forEach { it.cancel(CancellationException(reason)) } }
    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun pause(action: String, code: Int) = PendingIntent.getService(this, code, Intent(this, BridgeService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val policy = prefs.policy()
        val state = when { policy.pausedAll -> "全部访问已暂停"; policy.pausedOperations -> "操作已暂停 · $linkStatus"; else -> linkStatus }
        return Notification.Builder(this, "bridge").setSmallIcon(R.drawable.ic_bridge).setContentTitle("手机工具桥").setContentText(state)
            .setContentIntent(open).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "暂停操作", pause("pause_operations", 1)).build())
            .addAction(Notification.Action.Builder(null, "暂停全部", pause("pause_all", 2)).build()).build()
    }
    private fun updateNotification() { getSystemService(NotificationManager::class.java).notify(1, notification()) }
    override fun onDestroy() {
        connected = false; linkStatus = "服务已停止，打开 APK 可恢复"
        prefs.preferences.unregisterOnSharedPreferenceChangeListener(changes)
        getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
        socket?.cancel(); scope.cancel(); super.onDestroy()
    }
}
