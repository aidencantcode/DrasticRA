package com.drasticra

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Environment
import android.os.IBinder
import android.os.ParcelFileDescriptor
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

class EngineService : Service() {
    companion object {
        const val ACTION_START = "com.drasticra.START"
        const val ACTION_STOP = "com.drasticra.STOP"
        @Volatile var running = false
    }

    private class HttpResult(val id: Int, val status: Int, val body: ByteArray)

    @Volatile private var stopFlag = false
    private var rootProc: Process? = null
    private var nativeReady = false
    private val http = Executors.newFixedThreadPool(3)
    private val done = ConcurrentLinkedQueue<HttpResult>()
    private val popup by lazy { Popup(applicationContext) }
    private var ua = "DrasticRA/0.4.0"
    @Volatile private var loginState = 0
    @Volatile private var gameState = 0
    @Volatile private var gameTitle = ""
    private var phraseIdx = 0
    private val phrases = arrayOf(
        "Peeping at your memory",
        "Spying on every achievement",
        "Fathoming DraStic's RAM",
        "Eavesdropping on the DS",
        "Keeping a watchful eye",
        "Sniffing out unlocks",
        "Lurking behind DraStic",
        "Squinting at your progress",
        "Staking out those trophies",
        "Watching over your save data"
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopFlag = true
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        startForeground(1, notification("Waking up...", null))
        AppLog.setStatus("Starting...", true)
        val folder = intent?.getStringExtra("folder") ?: ""
        val pkg = intent?.getStringExtra("pkg") ?: "com.dsemu.drastic"
        running = true
        stopFlag = false
        loginState = 0
        gameState = 0
        gameTitle = ""
        Thread {
            try {
                engine(folder, pkg)
            } catch (e: Throwable) {
                AppLog.add("ENGINE ERROR: $e")
            } finally {
                cleanup()
                running = false
                AppLog.add("stopped.")
                AppLog.setStatus("Idle", false)
                stopSelf()
            }
        }.start()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopFlag = true
        super.onDestroy()
    }

    private fun notification(text: String, sub: String?): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("dsra", "Drastic RA", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(this, "dsra")
            .setContentTitle("Drastic RA")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .setOngoing(true)
        if (sub != null) b.setSubText(sub)
        return b.build()
    }

    private fun rotatePhrase() {
        val t = phrases[phraseIdx % phrases.size]
        phraseIdx++
        val sub = if (gameTitle.isEmpty()) "Waiting for DraStic" else gameTitle
        getSystemService(NotificationManager::class.java).notify(1, notification("Drastic RA is $t...", sub))
    }

    private fun engine(folder: String, pkg: String) {
        val prefs = getSharedPreferences("dsra", MODE_PRIVATE)
        if (!Environment.isExternalStorageManager()) {
            AppLog.add("ERROR: 'All files access' is not enabled for Drastic RA.")
            return
        }
        // 1) root helper that serves DraStic's RAM
        val ss = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val cmd = "CLASSPATH='" + applicationInfo.sourceDir + "' app_process /system/bin com.drasticra.RootServer '" +
            pkg + "' " + ss.localPort
        val proc = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        rootProc = proc
        Thread {
            try {
                proc.inputStream.bufferedReader().forEachLine { AppLog.add("root: $it") }
            } catch (_: Exception) {
            }
        }.start()
        ss.soTimeout = 10000
        val sock = try {
            ss.accept()
        } catch (e: SocketTimeoutException) {
            AppLog.add("ERROR: root helper did not connect. Is root granted to Drastic RA?")
            ss.close()
            return
        }
        ss.close()
        sock.tcpNoDelay = true

        // 2) rcheevos + login
        Native.create()
        nativeReady = true
        ua = "DrasticRA/0.4.0 " + Native.userAgentClause()
        val token = prefs.getString("token", "") ?: ""
        val user = prefs.getString("user_ra", "") ?: ""
        if (token.isEmpty() || user.isEmpty()) {
            AppLog.add("ERROR: log in first (open the menu -> Settings -> Account).")
            return
        }
        Native.loginToken(user, token)
        AppLog.add("logging in...")
        if (!waitFor(30000) { loginState != 0 } || loginState != 1) {
            AppLog.add("ERROR: login failed. If your session expired, log out and log in again in Settings.")
            return
        }
        Native.attach(ParcelFileDescriptor.fromSocket(sock).detachFd())

        // 3) supervise DraStic: wait for a game, track it, notice when it changes
        val hdr = ByteArray(0x200)
        var current: ByteArray? = null
        var gameLoaded = false
        var nextHdr = 0L
        var nextPhrase = 0L
        val period = 1_000_000_000L / 60
        var next = System.nanoTime()
        AppLog.setStatus("Waiting for DraStic...", true)
        AppLog.add("Ready. Launch a game in DraStic - I'll pick it up automatically.")
        while (!stopFlag) {
            val now = System.currentTimeMillis()
            if (now >= nextPhrase) {
                rotatePhrase()
                nextPhrase = now + 12000
            }
            if (now >= nextHdr) {
                val r = Native.readHeader(hdr)
                if (r < 0) {
                    AppLog.add("Lost connection to the RAM helper.")
                    break
                }
                nextHdr = now + (if (gameLoaded) 2000 else 3000)
                val same = current != null && java.util.Arrays.equals(current, hdr.copyOf(16))
                if (r == 1 && !same) {
                    if (gameLoaded) {
                        Native.unload()
                        gameLoaded = false
                    }
                    current = hdr.copyOf(16)
                    gameTitle = ""
                    gameLoaded = loadGameFor(hdr, folder)
                    next = System.nanoTime()
                    if (!gameLoaded) AppLog.setStatus("Waiting for DraStic...", true)
                } else if (r == 0 && current != null) {
                    if (gameLoaded) Native.unload()
                    gameLoaded = false
                    current = null
                    gameTitle = ""
                    AppLog.setStatus("Waiting for DraStic...", true)
                }
            }
            if (gameLoaded) {
                Native.doFrame()
                pump()
                next += period
                val sleepNs = next - System.nanoTime()
                if (sleepNs > 0) {
                    Thread.sleep(sleepNs / 1_000_000, (sleepNs % 1_000_000).toInt())
                } else if (sleepNs < -period * 5) {
                    next = System.nanoTime()
                }
            } else {
                Native.idle()
                pump()
                Thread.sleep(40)
            }
        }
    }

    private fun loadGameFor(hdr: ByteArray, folder: String): Boolean {
        val title = String(hdr, 0, 12, Charsets.US_ASCII).trim { it == '\u0000' || it == ' ' }
        val code = String(hdr, 12, 4, Charsets.US_ASCII)
        AppLog.add("DraStic is running: $title ($code)")
        val rom = findRom(folder, hdr)
        if (rom == null) {
            AppLog.add("ERROR: no .nds file in '$folder' matches $code. Check the ROM folder.")
            return false
        }
        AppLog.add("ROM: " + rom.absolutePath)
        gameState = 0
        Native.loadGame(rom.absolutePath)
        AppLog.add("identifying game...")
        if (!waitFor(60000) { gameState != 0 } || gameState != 1) {
            AppLog.add("ERROR: game not loaded (unknown to RetroAchievements?).")
            return false
        }
        return true
    }

    private fun findRom(folder: String, hdr: ByteArray): File? {
        val root = File(folder)
        if (!root.exists()) return null
        val buf = ByteArray(16)
        for (f in root.walkTopDown().maxDepth(6)) {
            if (!f.isFile || !f.name.endsWith(".nds", true) || f.length() < 0x200) continue
            try {
                FileInputStream(f).use { it.read(buf) }
            } catch (_: Exception) {
                continue
            }
            var same = true
            for (i in 0 until 16) if (buf[i] != hdr[i]) { same = false; break }
            if (same) return f
        }
        return null
    }

    private fun waitFor(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (!stopFlag && System.currentTimeMillis() < end) {
            Native.idle()
            pump()
            if (cond()) return true
            Thread.sleep(20)
        }
        return cond()
    }

    private fun pump() {
        while (true) {
            val r = Native.nextRequest() ?: break
            submit(r[0].toInt(), r[1], if (r[4] == "1") r[2] else null, r[3])
        }
        while (true) {
            val d = done.poll() ?: break
            Native.respond(d.id, d.status, d.body)
        }
        while (true) {
            val e = Native.nextEvent() ?: break
            handleEvent(e)
        }
    }

    private fun submit(id: Int, url: String, post: String?, ctype: String) {
        http.execute {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 20000
                c.setRequestProperty("User-Agent", ua)
                if (post != null) {
                    c.requestMethod = "POST"
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", if (ctype.isEmpty()) "application/x-www-form-urlencoded" else ctype)
                    c.outputStream.use { it.write(post.toByteArray(Charsets.UTF_8)) }
                }
                val code = c.responseCode
                val stream = if (code >= 400) c.errorStream else c.inputStream
                val body = stream?.readBytes() ?: ByteArray(0)
                done.add(HttpResult(id, code, body))
            } catch (e: Exception) {
                done.add(HttpResult(id, -1, (e.message ?: "network error").toByteArray()))
            }
        }
    }

    private fun handleEvent(e: String) {
        val p = e.split('\u001f')
        when (p[0]) {
            "LOGIN_OK" -> {
                AppLog.add("Logged in as " + p.getOrElse(1) { "?" })
                loginState = 1
            }
            "LOGIN_ERR" -> {
                AppLog.add("Login error: " + p.getOrElse(1) { "?" })
                loginState = -1
            }
            "GAME_OK" -> {
                val t = p.getOrElse(1) { "?" }
                val total = p.getOrElse(2) { "0" }.toIntOrNull() ?: 0
                val got = p.getOrElse(3) { "0" }.toIntOrNull() ?: 0
                val ptsTotal = p.getOrElse(4) { "0" }
                val pts = p.getOrElse(5) { "0" }
                gameTitle = t
                AppLog.add("Game: $t - $got / $total achievements ($pts / $ptsTotal pts)")
                AppLog.setStatus("Watching: $t", true)
                popup.game(t, p.getOrElse(6) { "" }, got, total, "$pts / $ptsTotal pts")
                gameState = 1
            }
            "GAME_ERR" -> {
                AppLog.add("Game error: " + p.getOrElse(1) { "?" })
                gameState = -1
            }
            "UNLOCK" -> {
                AppLog.add("UNLOCKED: " + p.getOrElse(1) { "" } + " (" + p.getOrElse(3) { "0" } + " pts)")
                popup.unlock(p.getOrElse(1) { "" }, p.getOrElse(2) { "" }, p.getOrElse(3) { "0" }, p.getOrElse(4) { "" })
            }
            "PROGRESS" -> popup.progress(p.getOrElse(1) { "" }, p.getOrElse(2) { "" }, p.getOrElse(3) { "" })
            "MASTERED" -> {
                AppLog.add("Game mastered!")
                popup.mastered()
            }
            "SERVER_ERROR" -> AppLog.add("server error reported by rcheevos")
            "LOST" -> {
                AppLog.add("Lost connection to the RAM helper. Stopping.")
                stopFlag = true
            }
        }
    }

    private fun cleanup() {
        try {
            if (nativeReady) Native.shutdown()
        } catch (_: Throwable) {
        }
        nativeReady = false
        http.shutdownNow()
        try {
            rootProc?.destroy()
        } catch (_: Exception) {
        }
        popup.hide()
    }
}
