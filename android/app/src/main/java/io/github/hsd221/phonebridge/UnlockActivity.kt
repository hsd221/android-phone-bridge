package io.github.hsd221.phonebridge

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.util.UUID

/** Short-lived system-dismiss window. No credential is ever passed in an Intent. */
class UnlockActivity : Activity() {
    companion object {
        private class Request(val ensure: () -> Unit, val requestSystemDismiss: Boolean) {
            val id = UUID.randomUUID().toString()
            val ready = CompletableDeferred<UnlockActivity>()
            var activity: UnlockActivity? = null
        }
        private var pending: Request? = null
        internal var dismissStatus = "idle"; private set
        internal suspend fun open(accessibility: PhoneAccessibilityService, ensure: () -> Unit, requestSystemDismiss: Boolean = true): UnlockActivity {
            check(pending == null)
            val request = Request(ensure, requestSystemDismiss)
            dismissStatus = "opening"
            pending = request
            try {
                ensure()
                accessibility.startActivity(Intent(accessibility, UnlockActivity::class.java)
                    .putExtra("requestId", request.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS))
                return withTimeout(4000) { request.ready.await() }
            } catch (failure: Exception) {
                request.activity?.finishAndRemoveTask()
                throw failure
            } finally { pending = null }
        }
    }
    private var requestedDismiss = false
    private var ensureAccess: (() -> Unit)? = null
    private var dismissDeadline = 0L
    private var requestSystemDismiss = true
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = pending?.takeIf { it.id == intent.getStringExtra("requestId") }
        if (request == null) { finishAndRemoveTask(); return }
        try { request.ensure() } catch (_: Exception) { finishAndRemoveTask(); return }
        request.activity = this
        ensureAccess = request.ensure
        requestSystemDismiss = request.requestSystemDismiss
        dismissDeadline = SystemClock.elapsedRealtime() + 3000
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setContentView(TextView(this).apply {
            text = "正在按手机本地授权解锁…"; gravity = Gravity.CENTER
            setBackgroundColor(getColor(R.color.bridge_background)); setTextColor(getColor(R.color.bridge_ink))
        })
        request.ready.complete(this)
    }
    override fun onResume() {
        super.onResume()
        // A secure keyguard can keep window focus until it is dismissed. Waiting for
        // focus before requesting dismissal would leave that keyguard stuck forever.
        window.decorView.postDelayed(::requestDismiss, 250)
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.decorView.post(::requestDismiss)
    }
    private fun requestDismiss() {
        if (!requestSystemDismiss || isFinishing || isDestroyed || requestedDismiss || SystemClock.elapsedRealtime() >= dismissDeadline) return
        try { ensureAccess?.invoke() ?: return } catch (_: Exception) { finishAndRemoveTask(); return }
        requestedDismiss = true
        dismissStatus = "requested"
        getSystemService(KeyguardManager::class.java).requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() { dismissStatus = "succeeded" }
            override fun onDismissCancelled() { dismissStatus = "cancelled" }
            override fun onDismissError() {
                dismissStatus = "error"
                // Android may reject a request before the new activity has drawn.
                // Retrying this system dialog never submits a credential.
                requestedDismiss = false
                window.decorView.postDelayed(::requestDismiss, 250)
            }
        })
    }
}
