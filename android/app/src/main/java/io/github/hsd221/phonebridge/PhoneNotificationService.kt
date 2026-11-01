package io.github.hsd221.phonebridge

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject

/** Optional, user-controlled notification access. Nothing is persisted. */
class PhoneNotificationService : NotificationListenerService() {
    companion object {
        @Volatile var instance: PhoneNotificationService? = null
        private val lock = Any()
        private val entries = LinkedHashMap<String, StatusBarNotification>()
    }

    override fun onListenerConnected() {
        instance = this
        synchronized(lock) {
            entries.clear()
            activeNotifications?.forEach { entries[it.key] = it }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = synchronized(lock) { entries[sbn.key] = sbn }
    override fun onNotificationRemoved(sbn: StatusBarNotification) { synchronized(lock) { entries.remove(sbn.key) } }
    override fun onDestroy() { if (instance === this) instance = null; synchronized(lock) { entries.clear() }; super.onDestroy() }

    fun snapshot(maxItems: Int, allowedPackages: Set<String>): JSONArray = synchronized(lock) {
        JSONArray(entries.values.asSequence().filter { it.packageName in allowedPackages }.take(maxItems).map { sbn ->
            val extras = sbn.notification.extras
            JSONObject().put("key", sbn.key).put("packageName", sbn.packageName)
                .put("postTime", sbn.postTime).put("id", sbn.id).put("tag", sbn.tag ?: JSONObject.NULL)
                .put("title", extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.take(1000) ?: JSONObject.NULL)
                .put("text", extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.take(4000) ?: JSONObject.NULL)
                .put("category", sbn.notification.category ?: JSONObject.NULL)
                .put("ongoing", sbn.isOngoing).put("clearable", sbn.isClearable)
        }.toList())
    }
}
