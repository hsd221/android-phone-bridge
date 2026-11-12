package io.github.hsd221.phonebridge

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.Process

/** Only the paired app process may request bounded text from its own user's clipboard. */
@SuppressLint("PrivateApi") // Non-SDK Binder access stays inside the shell-UID Shizuku UserService.
class ShizukuClipboardService(context: Context) : IClipboardReader.Stub() {
    private val ownerUid = context.applicationInfo.uid
    override fun uid() = Process.myUid()

    @Synchronized override fun readText(userId: Int): String? {
        if (uid() != 2000 || Binder.getCallingUid() != ownerUid || userId != ownerUid / 100000) return null
        val identity = Binder.clearCallingIdentity()
        return try {
            val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
                .invoke(null, "clipboard") as? IBinder ?: return null
            val clipboard = Class.forName("android.content.IClipboard\$Stub").getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder) ?: return null
            val contract = Class.forName("android.content.IClipboard")
            val integer = Integer.TYPE
            // Android 11, 12+, and 15+ use these exact signatures. Unknown OEM contracts fail closed.
            val signatures = listOf(
                arrayOf<Class<*>>(String::class.java, String::class.java, integer, integer) to arrayOf<Any?>("com.android.shell", null, userId, 0),
                arrayOf<Class<*>>(String::class.java, String::class.java, integer) to arrayOf<Any?>("com.android.shell", null, userId),
                arrayOf<Class<*>>(String::class.java, integer) to arrayOf<Any?>("com.android.shell", userId),
            )
            val (method, args) = signatures.firstNotNullOfOrNull { (types, args) ->
                runCatching { contract.getMethod("getPrimaryClip", *types) }.getOrNull()?.let { it to args }
            } ?: return null
            val clip = method.invoke(clipboard, *args) as? ClipData ?: return null
            clipboardContent(clip, "shizuku").toString()
        } catch (_: Exception) { null }
        finally { Binder.restoreCallingIdentity(identity) }
    }
}
