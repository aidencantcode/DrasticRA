package com.drasticra

object AppLog {
    private val buf = StringBuilder()
    @Volatile var listener: ((String) -> Unit)? = null

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
