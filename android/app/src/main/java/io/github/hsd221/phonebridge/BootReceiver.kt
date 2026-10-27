package io.github.hsd221.phonebridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) {
            val prefs = BridgePrefs(context)
            if (prefs.identity != null || prefs.pendingRevocation != null) BridgeService.start(context)
        }
    }
}
