package io.github.hsd221.phonebridge

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

internal class ShizukuCredentialInput private constructor(
    private val remote: ILocalKeyguard,
    private val release: () -> Unit,
) : LocalCredentialInput {
    companion object {
        suspend fun open(context: Context, ensure: () -> Unit): LocalCredentialInput? {
            ensure()
            if (!ShizukuSupport(context).status().optBoolean("available")) return null
            val args = Shizuku.UserServiceArgs(ComponentName(context, ShizukuUnlockService::class.java))
                .daemon(false).processNameSuffix("keyguard").version(1)
            var connection: ServiceConnection? = null
            var retained = false
            val release = { connection?.let { runCatching { Shizuku.unbindUserService(args, it, true) } }; Unit }
            try {
                val service = withTimeoutOrNull(6000) {
                    suspendCancellableCoroutine<ILocalKeyguard?> { pending ->
                        connection = object : ServiceConnection {
                            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                                if (pending.isActive) pending.resume(ILocalKeyguard.Stub.asInterface(binder))
                            }
                            override fun onServiceDisconnected(name: ComponentName?) {
                                if (pending.isActive) pending.resume(null)
                            }
                        }
                        Shizuku.bindUserService(args, connection!!)
                    }
                } ?: throw LocalInputUnavailable()
                ensure()
                val ready = withContext(Dispatchers.IO) { service.uid() == 2000 && service.prepare() == ShellUnlockResult.OK }
                if (!ready) throw LocalInputUnavailable()
                ensure()
                retained = true
                return ShizukuCredentialInput(service, release)
            } catch (failure: CancellationException) { throw failure }
            catch (failure: ToolFailure) { throw failure }
            catch (_: Exception) { throw LocalInputUnavailable() }
            finally { if (!retained) release() }
        }
    }

    override suspend fun preflight(credential: UnlockCredential, ensure: () -> Unit): PinSubmission? {
        ensure()
        val (result, reason) = call {
            val result = remote.preflight(credential.kind.ordinal)
            result to if (result < 0) remote.reason() else null
        }
        ensure()
        return when (result) {
            ShellUnlockResult.PIN_EXPLICIT -> PinSubmission.EXPLICIT_ENTER
            ShellUnlockResult.PIN_AUTO -> PinSubmission.AUTO_CONFIRM
            else -> { checkResult(result, reason); null }
        }
    }

    override suspend fun enter(credential: UnlockCredential, submission: PinSubmission?, ensure: () -> Unit, isUnlocked: () -> Boolean) {
        suspend fun action(block: () -> Int) {
            ensure()
            try {
                val (result, reason) = call {
                    val result = block()
                    result to if (result < 0) remote.reason() else null
                }
                checkResult(result, reason)
            }
            catch (_: LocalInputUnavailable) { throw ToolFailure("UNLOCK_INTERRUPTED", text(R.string.err_shizuku_input_disconnected), true) }
        }
        when (credential.kind) {
            UnlockKind.PIN -> {
                var first = true
                enterPinOnce(credential.value, checkNotNull(submission), ensure, isUnlocked,
                    clickDigit = { digit ->
                        action { remote.digit(digit - '0', first) }
                        first = false
                    },
                    confirm = { action { remote.confirmPin() } })
            }
            UnlockKind.PASSWORD -> {
                if (isUnlocked()) return
                action { remote.password(credential.value) }
                ensure()
                if (!isUnlocked()) action { remote.confirmPassword() }
            }
            UnlockKind.PATTERN -> { if (!isUnlocked()) action { remote.pattern(credential.value) } }
        }
    }
    private suspend fun <T> call(action: () -> T): T = withContext(Dispatchers.IO) {
        try { action() } catch (_: RemoteException) { throw LocalInputUnavailable() }
    }
    private fun checkResult(result: Int, reasonOrdinal: Int?) {
        if (result == ShellUnlockResult.UNAVAILABLE) throw LocalInputUnavailable()
        if (result == ShellUnlockResult.OK) return
        val reason = reasonOrdinal?.let { UnlockDiagnostics.Reason.entries.getOrNull(it) }
        if (reason != null) UnlockDiagnostics.refused(reason)
        when (result) {
            ShellUnlockResult.UNSUPPORTED -> throw ToolFailure("UNLOCK_UNSUPPORTED", text(R.string.err_unlock_unsupported))
            ShellUnlockResult.USER_REQUIRED -> throw ToolFailure("USER_UNLOCK_REQUIRED", text(R.string.err_lock_input_unverified))
            else -> throw ToolFailure("UNLOCK_INTERRUPTED", text(R.string.err_keyguard_shizuku_changed))
        }
    }
    override fun close() = release()
}
