package io.github.hsd221.phonebridge

import android.Manifest
import android.app.Notification
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
        @Volatile var linkStatusResource = R.string.foreground_not_started; private set
        val linkStatus: String get() = I18n.get(linkStatusResource)
        fun linkStatus(context: Context) = context.getString(linkStatusResource)
        @Volatile var activityVisible = false
        fun start(context: Context, action: String? = null) {
            try { context.startForegroundService(Intent(context, BridgeService::class.java).setAction(action)) }
            catch (_: Exception) { linkStatusResource = R.string.background_start_blocked }
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var prefs: BridgePrefs
    private lateinit var tools: PhoneTools
    private lateinit var callNotifications: ToolCallNotifications
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
        if (key == BridgePrefs.LIVE_UPDATES_ENABLED) {
            callNotifications.refresh()
            return@OnSharedPreferenceChangeListener
        }
        epoch.incrementAndGet()
        cancelRequests(getString(R.string.authorization_changed))
        PhoneAccessibilityService.instance?.invalidate()
        if (key == "identity") socket?.cancel()
        updateNotification()
    }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { scope.launch {
            if (defaultNetwork != null && defaultNetwork != network) {
                BridgeNetwork.client.connectionPool.evictAll()
                socket?.cancel()
            }
            defaultNetwork = network
        } }
        override fun onLost(network: Network) { scope.launch {
            if (defaultNetwork == network) {
                defaultNetwork = null
                BridgeNetwork.client.connectionPool.evictAll()
                socket?.cancel()
            }
        } }
    }
    override fun onCreate() {
        super.onCreate()
        prefs = BridgePrefs(this); tools = PhoneTools(this, prefs)
        BridgeNotificationChannels.ensure(this)
        callNotifications = ToolCallNotifications(this, prefs).also { it.clear() }
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
        callNotifications.refresh()
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
                if (prefs.pendingRevocation == null) { linkStatusResource = R.string.unpaired; stopSelf(); return }
                linkStatusResource = R.string.unpaired_locally; updateNotification(); delay(10000); continue
            }
            if (gateDeviceId != identity.deviceId) { gate = RequestGate(identity.deviceId); gateDeviceId = identity.deviceId }
            linkStatusResource = R.string.connecting; updateNotification()
            try { runSession(identity); attempt = if (connected) 0 else attempt + 1 }
            catch (_: Exception) { attempt++ }
            finally { connected = false; epoch.incrementAndGet(); cancelRequests(getString(R.string.connection_interrupted)); PhoneAccessibilityService.instance?.invalidate() }
            if (prefs.identity == null) continue
            linkStatusResource = R.string.reconnecting; updateNotification()
            delay(min(30000L, 1000L shl attempt.coerceIn(0, 5)) + Random.nextLong(500))
        }
    }
    private suspend fun runSession(identity: PhoneIdentity) = suspendCancellableCoroutine { continuation ->
        val url = identity.url.replaceFirst("https:", "wss:").replaceFirst("http:", "ws:") + "/device"
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { scope.launch {
                if (!continuation.isActive || prefs.identity != identity) { webSocket.cancel(); return@launch }
                linkStatusResource = R.string.verifying_connection; updateNotification()
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
                            connected = true; linkStatusResource = R.string.connected; updateNotification()
                        }
                        "request" -> accept(message, webSocket, identity)
                        "cancel" -> jobs[message.getString("id")]?.cancel(CancellationException(getString(R.string.bridge_cancelled)))
                        else -> webSocket.close(1008, "Unknown message")
                    }
                } catch (_: Exception) { webSocket.close(1008, "Invalid request") }
            } }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { scope.launch {
                if (code == 4001 && reason == "REVOKED" && prefs.identity == identity) prefs.identity = null
                if (continuation.isActive) continuation.resume(Unit)
            } }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { scope.launch {
                if (response?.code == 401 && prefs.identity == identity) { prefs.identity = null; linkStatusResource = R.string.connection_identity_invalid }
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
            if (jobs.size >= 8) throw ToolFailure("BUSY", getString(R.string.request_queue_full))
            prefs.policy().requireTool(tool)
        } catch (failure: ToolFailure) { sendResult(webSocket, identity, id, error(failure.code, failure.message)); return }
        val acceptedEpoch = epoch.get()
        val deadline = message.getLong("expiresAt")
        val args = message.getJSONObject("args")
        var started = false
        val operation = tool in BridgeTools.operations
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val ensure = {
                coroutineContext.ensureActive()
                if (epoch.get() != acceptedEpoch || socket !== webSocket || !connected) throw ToolFailure("CANCELLED", getString(R.string.connection_or_auth_changed))
                if (System.currentTimeMillis() >= deadline) throw ToolFailure("EXPIRED", getString(R.string.request_expired))
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
            catch (_: kotlinx.coroutines.TimeoutCancellationException) { error("TIMEOUT", getString(R.string.request_timed_out), operation && started) }
            catch (_: CancellationException) { error("CANCELLED", getString(R.string.request_cancelled), operation && started) }
            catch (_: SecurityException) { error("PERMISSION_REQUIRED", getString(R.string.android_permission_denied), operation && started) }
            catch (_: Exception) { error("EXECUTION_FAILED", getString(R.string.tool_execution_failed), operation && started) }
            sendResult(webSocket, identity, id, result)
        }
        jobs[id] = job
        job.invokeOnCompletion { jobs.remove(id); callNotifications.finish(id) }
        callNotifications.begin(id, tool)
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
        val state = when { policy.pausedAll -> getString(R.string.pause_all_status); policy.pausedOperations -> getString(R.string.paused_operations_status, getString(linkStatusResource)); else -> getString(linkStatusResource) }
        return Notification.Builder(this, BridgeNotificationChannels.CONNECTION).setSmallIcon(R.drawable.ic_bridge).setContentTitle(getString(R.string.app_name)).setContentText(state)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, getString(R.string.pause_operations), pause("pause_operations", 1)).build())
            .addAction(Notification.Action.Builder(null, getString(R.string.pause_all_action), pause("pause_all", 2)).build()).build()
    }
    private fun updateNotification() { getSystemService(NotificationManager::class.java).notify(1, notification()) }
    override fun onDestroy() {
        connected = false; linkStatusResource = R.string.service_stopped
        callNotifications.clear()
        prefs.preferences.unregisterOnSharedPreferenceChangeListener(changes)
        getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
        socket?.cancel(); scope.cancel(); super.onDestroy()
    }
}
