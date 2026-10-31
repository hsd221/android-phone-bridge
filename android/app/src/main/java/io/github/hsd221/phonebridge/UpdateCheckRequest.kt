package io.github.hsd221.phonebridge

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Retains a manual check and its result across rotation without holding an Activity. */
internal class UpdateCheckRequest(
    currentVersion: String = BuildConfig.VERSION_NAME,
    private val load: suspend () -> AppRelease = { AppUpdates.latest() },
) {
    private val current = AppVersion.parse(currentVersion)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var busy = false; private set
    var checked = false; private set
    var error: String? = null; private set
    var release: AppRelease? = null; private set
    var changed: (() -> Unit)? = null

    fun start() {
        if (busy) return
        busy = true; checked = false; error = null; release = null; changed?.invoke()
        scope.launch {
            try {
                val installed = current ?: throw UpdateCheckException("当前版本无法识别，请查看发布页面。")
                release = load().takeIf { it.version > installed }
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

    fun close() { changed = null; scope.cancel() }
}
