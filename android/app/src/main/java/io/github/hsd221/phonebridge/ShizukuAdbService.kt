package io.github.hsd221.phonebridge

import android.os.Process
import java.util.concurrent.TimeUnit

/**
 * The narrow ADB-equivalent path.  It deliberately exposes only fixed input
 * primitives; there is no shell string, executable, or user supplied flag.
 */
class ShizukuAdbService : IAdbTools.Stub() {
    override fun uid(): Int = Process.myUid()

    override fun tap(x: Int, y: Int): Int = runInput("tap", x.toString(), y.toString())

    override fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Int): Int =
        runInput("swipe", fromX.toString(), fromY.toString(), toX.toString(), toY.toString(), durationMs.toString())

    override fun inputText(text: String): Int {
        if (text.length > 10_000 || text.any { it == '\n' || it == '\r' }) return -3
        // `input text` uses %s for spaces. ProcessBuilder still avoids a shell;
        // percent is escaped so a caller cannot create an input escape sequence.
        val encoded = text.replace("%", "%25").replace(" ", "%s")
        return runInput("text", encoded)
    }

    override fun back(): Int = runInput("keyevent", "4")

    private fun runInput(vararg args: String): Int {
        if (uid() != 2000) return -2
        val process = try {
            ProcessBuilder(listOf("/system/bin/input") + args.toList())
                .redirectErrorStream(true).redirectOutput(java.io.File("/dev/null")).start()
        } catch (_: Exception) { return -4 }
        return try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroy(); -4 }
            else process.exitValue()
        } catch (_: InterruptedException) { process.destroy(); -4 }
    }
}
