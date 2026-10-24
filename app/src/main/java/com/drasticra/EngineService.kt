package com.drasticra

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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
    private val ui = Handler(Looper.getMainLooper())
    private var overlay: View? = null
    private var ua = "DrasticRA/0.2.0"
    @Volatile private var loginState = 0
    @Volatile private var gameState = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopFlag = true
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        startForeground(1, notification("Starting..."))
        val user = intent?.getStringExtra("user") ?: ""
        val pass = intent?.getStringExtra("pass") ?: ""
        val folder = intent?.getStringExtra("folder") ?: ""
        val pkg = intent?.getStringExtra("pkg") ?: "com.dsemu.drastic"
        running = true
        stopFlag = false
        loginState = 0
        gameState = 0
        Thread {
            try {
                engine(user, pass, folder, pkg)
            } catch (e: Throwable) {
                AppLog.add("ENGINE ERROR: $e")
            } finally {
                cleanup()
                running = false
                AppLog.add("stopped.")
                stopSelf()
            }
        }.start()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopFlag = true
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("dsra", "Drastic RA", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, "dsra")
            .setContentTitle("Drastic RA")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    private fun setStatus(text: String) {
        getSystemService(NotificationManager::class.java).notify(1, notification(text))
    }

    private fun engine(user: String, pass: String, folder: String, pkg: String) {
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
        val out = sock.getOutputStream()
        val inp = DataInputStream(sock.getInputStream())
        out.write('H'.code)
        out.flush()
        val status = inp.readUnsignedByte()
        val hdr = ByteArray(0x200)
        inp.readFully(hdr)
        if (status != 1) {
            AppLog.add("ERROR: DS RAM not found. Start DraStic and load a game first, then press Start.")
            return
        }
        val title = String(hdr, 0, 12, Charsets.US_ASCII).trim { it == '\u0000' || it == ' ' }
        val code = String(hdr, 12, 4, Charsets.US_ASCII)
        AppLog.add("DraStic is running: $title ($code)")

        // 2) find the ROM file that matches
        val rom = findRom(folder, hdr)
        if (rom == null) {
            AppLog.add("ERROR: no .nds file in '$folder' matches $code. Check the ROM folder.")
            return
        }
        AppLog.add("ROM: " + rom.absolutePath)

        // 3) rcheevos
        Native.create()
        nativeReady = true
        ua = "DrasticRA/0.2.0 " + Native.userAgentClause()
        val savedToken = prefs.getString("token", "") ?: ""
        val savedUser = prefs.getString("user_ra", "") ?: ""
        if (pass.isNotEmpty()) {
            Native.login(user, pass)
        } else if (savedToken.isNotEmpty() && savedUser.equals(user, true)) {
            Native.loginToken(user, savedToken)
        } else {
            AppLog.add("ERROR: enter your RetroAchievements password.")
            return
        }
        AppLog.add("logging in...")
        if (!waitFor(30000) { loginState != 0 } || loginState != 1) {
            AppLog.add("ERROR: login failed or timed out.")
            return
        }

        val fd = ParcelFileDescriptor.fromSocket(sock).detachFd()
        Native.attach(fd)
        Native.loadGame(rom.absolutePath)
        AppLog.add("identifying game...")
        if (!waitFor(60000) { gameState != 0 } || gameState != 1) {
            AppLog.add("ERROR: game not loaded.")
            return
        }
        setStatus("Watching $title")
        AppLog.add("WATCHING. Switch to DraStic and play.")

        val period = 1_000_000_000L / 60
        var next = System.nanoTime()
        while (!stopFlag) {
            Native.doFrame()
            pump()
            next += period
            val sleepNs = next - System.nanoTime()
            if (sleepNs > 0) {
                Thread.sleep(sleepNs / 1_000_000, (sleepNs % 1_000_000).toInt())
            } else if (sleepNs < -period * 5) {
                next = System.nanoTime()
            }
        }
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
                getSharedPreferences("dsra", MODE_PRIVATE).edit()
                    .putString("token", p.getOrElse(3) { "" })
                    .putString("user_ra", p.getOrElse(2) { "" }).apply()
                AppLog.add("Logged in as " + p.getOrElse(1) { "?" })
                loginState = 1
            }
            "LOGIN_ERR" -> {
                AppLog.add("Login error: " + p.getOrElse(1) { "?" })
                loginState = -1
            }
            "GAME_OK" -> {
                val t = p.getOrElse(1) { "?" }
                val msg = p.getOrElse(2) { "0" } + " achievements, " + p.getOrElse(3) { "0" } + " unlocked"
                AppLog.add("Game: $t - $msg (" + p.getOrElse(4) { "0" } + " pts)")
                popup(t, msg, null, 4000)
                gameState = 1
            }
            "GAME_ERR" -> {
                AppLog.add("Game error: " + p.getOrElse(1) { "?" })
                gameState = -1
            }
            "UNLOCK" -> {
                AppLog.add("UNLOCKED: " + p.getOrElse(1) { "" } + " (" + p.getOrElse(3) { "0" } + " pts)")
                popup("Achievement Unlocked", p.getOrElse(1) { "" } + " - " + p.getOrElse(2) { "" } + " (" + p.getOrElse(3) { "0" } + ")", p.getOrElse(4) { "" }, 6000)
            }
            "PROGRESS" -> popup(p.getOrElse(1) { "" }, p.getOrElse(2) { "" }, p.getOrElse(3) { "" }, 2500)
            "MASTERED" -> {
                AppLog.add("Game mastered!")
                popup("Game complete", "You earned every achievement", null, 6000)
            }
            "SERVER_ERROR" -> AppLog.add("server error reported by rcheevos")
            "LOST" -> {
                AppLog.add("Lost connection to the RAM helper (DraStic closed?). Stopping.")
                stopFlag = true
            }
        }
    }

    private fun removeOverlay() {
        val v = overlay ?: return
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (_: Exception) {
        }
        overlay = null
    }

    private fun popup(title: String, desc: String, badge: String?, ms: Long) {
        ui.post {
            try {
                if (!Settings.canDrawOverlays(this)) throw IllegalStateException("no overlay permission")
                removeOverlay()
                val d = resources.displayMetrics.density
                val row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER_VERTICAL
                row.setPadding((12 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), (10 * d).toInt())
                val bg = GradientDrawable()
                bg.setColor(0xEE202020.toInt())
                bg.cornerRadius = 14 * d
                row.background = bg
                val img = ImageView(this)
                val ilp = LinearLayout.LayoutParams((44 * d).toInt(), (44 * d).toInt())
                ilp.rightMargin = (12 * d).toInt()
                row.addView(img, ilp)
                val col = LinearLayout(this)
                col.orientation = LinearLayout.VERTICAL
                val t1 = TextView(this)
                t1.text = title
                t1.setTextColor(Color.WHITE)
                t1.textSize = 15f
                t1.typeface = Typeface.DEFAULT_BOLD
                val t2 = TextView(this)
                t2.text = desc
                t2.setTextColor(0xFFCCCCCC.toInt())
                t2.textSize = 12f
                t2.maxWidth = (300 * d).toInt()
                col.addView(t1)
                col.addView(t2)
                row.addView(col)
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT
                )
                lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                lp.y = (20 * d).toInt()
                (getSystemService(WINDOW_SERVICE) as WindowManager).addView(row, lp)
                overlay = row
                ui.postDelayed({ if (overlay === row) removeOverlay() }, ms)
                if (!badge.isNullOrEmpty()) {
                    http.execute {
                        try {
                            val bmp = BitmapFactory.decodeStream(URL("https://media.retroachievements.org/Badge/$badge.png").openStream())
                            ui.post { img.setImageBitmap(bmp) }
                        } catch (_: Exception) {
                        }
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(this, "$title\n$desc", Toast.LENGTH_LONG).show()
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
        ui.post { removeOverlay() }
    }
}
