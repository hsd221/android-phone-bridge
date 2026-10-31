package io.github.hsd221.phonebridge

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Retains automatic/manual checks and pending prompts across rotation without holding an Activity. */
internal class UpdateCheckRequest(
    currentVersion: String = BuildConfig.VERSION_NAME,
    private val load: suspend () -> AppRelease = { AppUpdates.latest() },
) {
    private val current = AppVersion.parse(currentVersion)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastForeground = -1
    var busy = false; private set
    var checked = false; private set
    var error: String? = null; private set
    var release: AppRelease? = null; private set
    var pendingPrompt: AppRelease? = null; private set
    var changed: (() -> Unit)? = null

    fun onForeground(generation: Int) {
        if (lastForeground == generation) return
        lastForeground = generation
        start(automatic = true)
    }

    fun start(automatic: Boolean = false) {
        if (busy) return
        busy = true; checked = false; error = null; release = null
        if (!automatic) pendingPrompt = null
        changed?.invoke()
        scope.launch {
            try {
                val installed = current ?: throw UpdateCheckException("当前版本无法识别，请查看发布页面。")
                release = load().takeIf { it.version > installed }
                if (automatic) pendingPrompt = release
                checked = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "检查更新失败，请稍后重试。"
            } finally {
                busy = false; changed?.invoke()
            }
        }
    }

    fun dismissPrompt() { pendingPrompt = null }
    fun close() { changed = null; scope.cancel() }
}
