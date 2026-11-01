package io.github.hsd221.phonebridge

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat

enum class LiveUpdateAvailability(val messageResource: Int) {
    AVAILABLE(R.string.live_updates_available),
    UNSUPPORTED(R.string.live_updates_unsupported),
    NOTIFICATIONS_DISABLED(R.string.live_updates_notifications_disabled),
    CHANNEL_DISABLED(R.string.live_updates_channel_disabled),
    PROMOTION_DISABLED(R.string.live_updates_promotion_disabled),
}

/** Keep the existing connection channel so an upgrade preserves the user's channel settings. */
object BridgeNotificationChannels {
    const val CONNECTION = "bridge"
    const val TOOL_CALLS = "bridge_tools"
    const val CONNECTION_ID = 1
    const val TOOL_CALL_ID = 2

    fun ensure(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(
            NotificationChannel(CONNECTION, context.getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.connection_channel_description)
                setShowBadge(false)
            },
            NotificationChannel(TOOL_CALLS, context.getString(R.string.tool_call_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.tool_call_channel_description)
                setSound(null, null); enableVibration(false); setShowBadge(false)
            },
        ))
    }

    fun liveUpdateAvailability(context: Context): LiveUpdateAvailability {
        if (Build.VERSION.SDK_INT < 36) return LiveUpdateAvailability.UNSUPPORTED
        val manager = context.getSystemService(NotificationManager::class.java)
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ||
            !manager.areNotificationsEnabled()) return LiveUpdateAvailability.NOTIFICATIONS_DISABLED
        if ((manager.getNotificationChannel(TOOL_CALLS)?.importance ?: NotificationManager.IMPORTANCE_DEFAULT) <= NotificationManager.IMPORTANCE_MIN)
            return LiveUpdateAvailability.CHANNEL_DISABLED
        if (!manager.canPostPromotedNotifications()) return LiveUpdateAvailability.PROMOTION_DISABLED
        return LiveUpdateAvailability.AVAILABLE
    }
}

/** Accepted calls include calls waiting for the execution mutex; completing one must not hide another. */
internal class ToolCallStatus {
    private val calls = linkedMapOf<String, String>()
    val count: Int get() = calls.size
    val currentTool: String? get() = calls.values.firstOrNull()
    fun begin(id: String, tool: String) { calls[id] = tool }
    fun finish(id: String) { calls.remove(id) }
    fun clear() { calls.clear() }
}

/** Only tool names and counts are shown. Arguments, results and device data never enter notifications. */
class ToolCallNotifications(private val context: Context, private val prefs: BridgePrefs) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val calls = ToolCallStatus()

    fun begin(id: String, tool: String) { calls.begin(id, tool); refresh() }
    fun finish(id: String) { calls.finish(id); refresh() }
    fun clear() { calls.clear(); manager.cancel(BridgeNotificationChannels.TOOL_CALL_ID) }

    fun refresh() {
        val tool = calls.currentTool ?: return clear()
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun pause(action: String, code: Int) = PendingIntent.getService(context, code,
            Intent(context, BridgeService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = NotificationCompat.Builder(context, BridgeNotificationChannels.TOOL_CALLS)
            .setSmallIcon(R.drawable.ic_bridge).setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.tool_call_running, tool))
            .setSubText(context.resources.getQuantityString(R.plurals.tool_call_count, calls.count, calls.count))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(0, context.getString(R.string.pause_operations), pause("pause_operations", 1))
            .addAction(0, context.getString(R.string.pause_all_action), pause("pause_all", 2))
        if (prefs.liveUpdatesEnabled && BridgeNotificationChannels.liveUpdateAvailability(context) == LiveUpdateAvailability.AVAILABLE) {
            builder.setRequestPromotedOngoing(true).setShortCriticalText(context.getString(R.string.tool_call_short))
        }
        // Notification permission can be revoked while a call is running; this must not fail that call.
        try { manager.notify(BridgeNotificationChannels.TOOL_CALL_ID, builder.build()) }
        catch (_: SecurityException) { }
    }
}
