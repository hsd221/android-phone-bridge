package io.github.hsd221.phonebridge

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.window.OnBackInvokedDispatcher
import android.widget.Button
import android.widget.LinearLayout
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
        internal var current: UnlockActivity? = null; private set
        internal var dismissStatus = "idle"; private set
        internal suspend fun open(accessibility: PhoneAccessibilityService, ensure: () -> Unit, requestSystemDismiss: Boolean): UnlockActivity {
            check(pending == null)
            val request = Request(ensure, requestSystemDismiss)
            dismissStatus = if (requestSystemDismiss) "opening" else "wake_only"
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
    private var cancelledByUser = false
    private var releasedToKeyguard = false
    internal val usesSystemDismiss get() = requestSystemDismiss

    internal fun ensureNotCancelled() {
        if (cancelledByUser) throw ToolFailure("USER_UNLOCK_REQUIRED", "本次解锁已在手机上取消")
        if (!releasedToKeyguard && (isFinishing || isDestroyed)) throw ToolFailure("UNLOCK_INTERRUPTED", "唤醒窗口已关闭，已停止本地解锁")
    }
    internal fun releaseToKeyguard() {
        ensureNotCancelled()
        releasedToKeyguard = true
        finishAndRemoveTask()
    }
    private fun cancelFromUser() {
        cancelledByUser = true
        finishAndRemoveTask()
    }
    @Suppress("DEPRECATION")
    override fun onBackPressed() { cancelFromUser() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = pending?.takeIf { it.id == intent.getStringExtra("requestId") }
        if (request == null) { finishAndRemoveTask(); return }
        try { request.ensure() } catch (_: Exception) { finishAndRemoveTask(); return }
        request.activity = this
        current = this
        ensureAccess = request.ensure
        requestSystemDismiss = request.requestSystemDismiss
        dismissDeadline = SystemClock.elapsedRealtime() + 3000
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(getColor(R.color.bridge_background))
            addView(TextView(this@UnlockActivity).apply {
                text = "正在按手机本地授权解锁…"; gravity = Gravity.CENTER
                setTextColor(getColor(R.color.bridge_ink))
            })
            addView(Button(this@UnlockActivity).apply {
                text = "取消本次解锁"
                setOnClickListener { cancelFromUser() }
            })
        })
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { cancelFromUser() }
        }
        request.ready.complete(this)
    }
    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
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
            override fun onDismissSucceeded() { if (current === this@UnlockActivity) dismissStatus = "succeeded" }
            override fun onDismissCancelled() { if (current === this@UnlockActivity) dismissStatus = "cancelled" }
            override fun onDismissError() {
                if (current !== this@UnlockActivity || isFinishing || isDestroyed) return
                dismissStatus = "error"
                // Android may reject a request before the new activity has drawn.
                // Retrying this system dialog never submits a credential.
                requestedDismiss = false
                window.decorView.postDelayed(::requestDismiss, 250)
            }
        })
    }
}
