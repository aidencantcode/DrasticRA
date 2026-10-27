package com.drasticra

/** Thin JNI wrapper around rcheevos (rc_client). All calls must come from ONE thread (the engine thread). */
object Native {
    init {
        System.loadLibrary("dsra")
    }

    external fun create(): Int
    external fun userAgentClause(): String
    external fun login(user: String, pass: String)
    external fun loginToken(user: String, token: String)
    external fun attach(fd: Int)
    external fun loadGame(path: String)
    external fun doFrame()
    external fun idle()
    external fun nextRequest(): Array<String>?
    external fun respond(id: Int, status: Int, body: ByteArray)
    external fun nextEvent(): String?
    external fun readHeader(out: ByteArray): Int
    external fun unload()
    /** UTF-8 list of every core achievement with live progress (see dsra_jni.cpp for the layout). */
    external fun achievementList(): ByteArray?
    /** RetroAchievements hash of a .nds file (empty on failure). Safe from any thread. */
    external fun hashFile(path: String): String
    external fun shutdown()
}
