package io.github.hsd221.phonebridge

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Retained in memory across rotation. Never holds an Activity or writes the one-time code to disk. */
class PairingRequest(context: Context) {
    private val appContext = context.applicationContext
    var busy = false; private set
    var error: String? = null; private set
    var completed = false
    var changed: (() -> Unit)? = null

    fun start(url: String, code: String, insecure: Boolean) {
        if (busy) return
        busy = true; error = null; completed = false; changed?.invoke()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope.launch {
            try {
                val prefs = BridgePrefs(appContext)
                // Finish recording the returned identity even if the originating Activity is recreated.
                prefs.identity = BridgeNetwork.pair(url, code, prefs, insecure)
                BridgeService.start(appContext)
                completed = true
            } catch (failure: Exception) { error = failure.message ?: "无法连接 Bridge，请检查网络后重试" }
            finally { busy = false; changed?.invoke(); scope.cancel() }
        }
    }
}
