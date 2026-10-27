package com.drasticra

object AppLog {
    private val buf = StringBuilder()
    @Volatile var listener: ((String) -> Unit)? = null
    @Volatile var status = "Idle"
    @Volatile var active = false
    @Volatile var statusListener: (() -> Unit)? = null

    fun setStatus(s: String, a: Boolean) {
        status = s
        active = a
        statusListener?.invoke()
    }

    @Synchronized
    fun add(s: String) {
        buf.append(s).append('\n')
        if (buf.length > 60000) buf.delete(0, 20000)
        listener?.invoke(s)
    }

    @Synchronized
    fun all(): String = buf.toString()

    @Synchronized
    fun clear() {
        buf.setLength(0)
    }
}

fun rootSh(cmd: String): String {
    return try {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val o = p.inputStream.bufferedReader().readText()
        p.waitFor()
        o
    } catch (e: Exception) {
        "su failed: " + e.message
    }
}

/**
 * Catches a crash, writes the full error to a file, then lets Android close the app as usual. The next launch shows
 * it in Tools and log (Share log sends it), so a crash is never a mystery.
 */
object CrashLog {
    @Volatile private var installed = false

    fun install(ctx: android.content.Context) {
        if (installed) return
        installed = true
        val file = java.io.File(ctx.applicationContext.filesDir, "last_crash.txt")
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                val ver = try {
                    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
                } catch (_: Throwable) {
                    "?"
                }
                file.writeText("Drastic RA $ver crashed on thread '${t.name}' (Android ${android.os.Build.VERSION.RELEASE}, " +
                    "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL})\n\n$sw")
            } catch (_: Throwable) {
            }
            prev?.uncaughtException(t, e)
        }
    }

    /** The crash report from the last run (removed once read), or null. */
    fun take(ctx: android.content.Context): String? {
        val file = java.io.File(ctx.applicationContext.filesDir, "last_crash.txt")
        if (!file.exists()) return null
        return try {
            val t = file.readText()
            file.delete()
            t
        } catch (_: Throwable) {
            null
        }
    }
}
