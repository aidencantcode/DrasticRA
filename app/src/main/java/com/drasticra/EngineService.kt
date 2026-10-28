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
import android.os.SystemClock
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
        /** tap on the ongoing notification / popup button: show or hide the in-game achievement overlay */
        const val ACTION_OVERLAY = "com.drasticra.OVERLAY"
        /** how long a newly seen game must stay put before it is tracked */
        const val SETTLE_MS = 6000L
        @Volatile var running = false
    }

    private class HttpResult(val id: Int, val status: Int, val body: ByteArray)

    @Volatile private var stopFlag = false
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private var rootProc: Process? = null
    private var hotkeys: HotkeyWatcher? = null
    /** online / offline changes while you play: shown as popups over the game (the app shows its own when in front) */
    private val netListener: (Boolean) -> Unit = { on ->
        if (!MainActivity.foreground) {
            if (on) popup.status("Online mode", "Internet Established Online mode!", "Syncing your offline unlocks...",
                null, "Online", R.drawable.ic_star, 0)
            else popup.status("Offline mode", "No internet running offline mode!", "Unlocks are saved and sent later",
                NetNotice.YELLOW, "Offline", R.drawable.ic_lock, NetNotice.YELLOW[0])
        }
        if (on) OfflineProxy.flushAsync()
    }
    private var nativeReady = false
    private val http = Executors.newFixedThreadPool(3)
    private val done = ConcurrentLinkedQueue<HttpResult>()
    private val popup by lazy {
        val p = Popup(applicationContext)
        p.onOpenOverlay = { GameOverlay.show(applicationContext) }
        p
    }
    private var ua = "DrasticRA/0.9.0"
    @Volatile private var loginState = 0
    @Volatile private var gameState = 0
    @Volatile private var gameTitle = ""
    @Volatile private var totalPoints = 0

    // play time: engine thread only. Counts while a known game is loaded AND DraStic is on screen.
    private var playId = 0
    private var playLast = 0L
    private var playPending = 0L
    private var playFlushAt = 0L
    private var visible = true
    private var channelMade = false
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
        if (intent?.action == ACTION_OVERLAY) {
            if (running && GameOverlay.available()) {
                GameOverlay.toggle(applicationContext)
            } else {
                // nothing is being played: the notification just opens the app
                try {
                    startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {
                }
                if (!running) stopSelf()
            }
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        CrashLog.install(applicationContext)
        NetMonitor.init(applicationContext)
        OfflineStore.init(applicationContext)
        startForeground(1, notification("Waking up...", null))
        AppLog.setStatus("Starting...", true)
        val folder = intent?.getStringExtra("folder") ?: ""
        val pkg = intent?.getStringExtra("pkg") ?: "com.dsemu.drastic"
        running = true
        stopFlag = false
        NetMonitor.addListener(netListener)
        if (!NetMonitor.online && !MainActivity.foreground) {
            popup.status("Offline mode", "No internet running offline mode!", "Unlocks are saved and sent later",
                NetNotice.YELLOW, "Offline", R.drawable.ic_lock, NetNotice.YELLOW[0])
        } else if (OfflineProxy.pendingCount() > 0) {
            OfflineProxy.flushAsync()
        }
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

    private fun playTick(loaded: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val dt = now - playLast
        playLast = now
        if (loaded && playId != 0 && visible && dt in 1L..5000L) {
            playPending += dt
            LiveGame.sessionMs += dt
        }
        if (playPending > 0 && now - playFlushAt >= 15000) playFlush()
        LiveGame.pendingMs = playPending
    }

    private fun playFlush() {
        playFlushAt = SystemClock.elapsedRealtime()
        if (playId == 0 || playPending <= 0) return
        val sp = getSharedPreferences("dsra", MODE_PRIVATE)
        val key = "play_ms_$playId"
        sp.edit().putLong(key, sp.getLong(key, 0L) + playPending).apply()
        playPending = 0
        LiveGame.pendingMs = 0
    }

    private fun playStop() {
        playFlush()
        playId = 0
    }

    private fun notification(text: String, sub: String?): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (!channelMade) {
            nm.createNotificationChannel(NotificationChannel("dsra", "Drastic RA", NotificationManager.IMPORTANCE_LOW))
            channelMade = true
        }
        val app = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        // tapping the notification while a game runs opens the achievement overlay over the game
        val ov = PendingIntent.getService(
            this, 1, Intent(this, EngineService::class.java).setAction(ACTION_OVERLAY),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val playing = GameOverlay.available()
        val b = Notification.Builder(this, "dsra")
            .setContentTitle("Drastic RA")
            .setContentText(if (playing) "$text  -  tap for achievements" else text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(if (playing) ov else app)
            .setOngoing(true)
        if (playing) {
            @Suppress("DEPRECATION")
            b.addAction(Notification.Action.Builder(0, "Achievements", ov).build())
            @Suppress("DEPRECATION")
            b.addAction(Notification.Action.Builder(0, "Open app", app).build())
        }
        if (sub != null) b.setSubText(sub)
        return b.build()
    }

    private fun rotatePhrase() {
        val t = phrases[phraseIdx % phrases.size]
        phraseIdx++
        val sub = if (gameTitle.isEmpty()) "Waiting for DraStic" else gameTitle
        getSystemService(NotificationManager::class.java).notify(1, notification("Drastic RA is $t...", sub))
    }

    private var trophies: TrophyWatcher? = null
    /** set by the burst guard: the engine thread reloads the game with the real progress */
    @Volatile private var reloadGame = false

    /**
     * Everything the watcher does: the local achievement proxy (RetroArch / PPSSPP / ARMSX2), the trophy watcher
     * (ARMSX3 / Vita3K / Bachata S4) and DraStic. If DraStic cannot be watched, the others keep running.
     */
    private fun engine(folder: String, pkg: String) {
        BurstGuard.load(applicationContext)
        BurstGuard.onTrip = { n ->
            reloadGame = true
            popup.status("Unlocks paused", "$n achievements at once - held back, nothing sent",
                "Looks like a save state or memory jump. Review them in Settings -> Offline Play.",
                NetNotice.YELLOW, "Held", R.drawable.ic_lock, NetNotice.YELLOW[0])
        }
        ProxyGames.popup = popup
        ProxyServer.start(applicationContext)
        val tw = TrophyWatcher(applicationContext, popup)
        trophies = tw
        tw.start()
        try {
            engineDs(folder, pkg)
        } catch (e: Throwable) {
            AppLog.add("DraStic watcher error: $e")
        }
        if (!stopFlag) {
            AppLog.add("DraStic is not being watched - RetroArch, PPSSPP, ARMSX2 and trophy emulators still are.")
            AppLog.setStatus("Watching other emulators", true)
            while (!stopFlag) Thread.sleep(500)
        }
    }

    private fun engineDs(folder: String, pkg: String) {
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

        // controller combo (Button mapping page) that toggles the achievement overlay while you play
        val hk = HotkeyWatcher(applicationContext) {
            if (GameOverlay.available()) GameOverlay.toggle(applicationContext) else GameOverlay.hide()
        }
        hotkeys = hk
        hk.start()
        if (Hotkeys.overlayCombo.isNotEmpty()) AppLog.add("Overlay combo: " + Hotkeys.label(Hotkeys.overlayCombo))

        // 2) rcheevos + login
        Native.create()
        nativeReady = true
        ua = "DrasticRA/0.9.0 " + Native.userAgentClause()
        val token = prefs.getString("token", "") ?: ""
        val user = prefs.getString("user_ra", "") ?: ""
        if (token.isEmpty() || user.isEmpty()) {
            AppLog.add("ERROR: log in first (open the menu -> Settings -> Account).")
            return
        }
        Native.loginToken(user, token)
        Thread {
            val sc = Ra.score(user, token)
            if (sc != null) totalPoints = sc
        }.start()
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
        var nextList = 0L
        // a new header is only loaded once it has stayed the same for SETTLE_MS (DraStic's "Continue" loads the
        // save state a moment after the game appears; tracking during that jump is what fired fake unlocks)
        var pendingHdr: ByteArray? = null
        var pendingSince = 0L
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
                nextHdr = now + (if (pendingHdr != null) 1000 else if (gameLoaded) 2000 else 3000)
                // the root helper stores "is DraStic on screen" in the last header byte (see RootServer)
                if (r == 1) visible = hdr[0x1FF].toInt() != 2
                val head16 = hdr.copyOf(16)
                val same = current != null && java.util.Arrays.equals(current, head16)
                if (r == 1 && !same) {
                    if (current != null || gameLoaded) {
                        // the game changed or restarted: stop tracking right away
                        playStop()
                        LiveGame.clear()
                        if (gameLoaded) Native.unload()
                        gameLoaded = false
                        current = null
                        gameTitle = ""
                    }
                    val pend = pendingHdr
                    if (pend == null || !java.util.Arrays.equals(pend, head16)) {
                        pendingHdr = head16
                        pendingSince = now
                        AppLog.setStatus("Game starting - letting it settle...", true)
                    } else if (now - pendingSince >= SETTLE_MS && visible) {
                        pendingHdr = null
                        current = head16
                        BurstGuard.reset()
                        gameLoaded = loadGameFor(hdr, folder)
                        next = System.nanoTime()
                        if (!gameLoaded) AppLog.setStatus("Waiting for DraStic...", true)
                    }
                } else if (r == 1 && same && reloadGame) {
                    // after a burst: unload and load again, so rcheevos takes your real progress from the server and
                    // treats everything that is already true in memory as "seen" instead of firing it
                    reloadGame = false
                    AppLog.add("Reloading the game after the unlock burst...")
                    playStop()
                    LiveGame.clear()
                    if (gameLoaded) Native.unload()
                    gameLoaded = false
                    current = null
                    pendingHdr = head16
                    pendingSince = now
                } else if (r == 0 && (current != null || pendingHdr != null)) {
                    pendingHdr = null
                    playStop()
                    LiveGame.clear()
                    if (gameLoaded) Native.unload()
                    gameLoaded = false
                    current = null
                    gameTitle = ""
                    rotatePhrase()
                    AppLog.setStatus("Waiting for DraStic...", true)
                }
            }
            playTick(gameLoaded)
            if (gameLoaded) {
                Native.doFrame()
                pump()
                // live achievement list for the overlay: often while it is open, rarely otherwise
                if (LiveGame.refreshWanted || now >= nextList) {
                    LiveGame.refreshWanted = false
                    try {
                        LiveGame.setFromNative(Native.achievementList())
                    } catch (_: Throwable) {
                    }
                    nextList = now + (if (GameOverlay.showing) 1500L else 8000L)
                }
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
            // offline proxy: real server when online (and caches what offline play needs), the cache when not
            try {
                val res = OfflineProxy.handle(url, post, ctype, ua)
                done.add(HttpResult(id, res.status, res.body))
                return@execute
            } catch (_: Exception) {
            }
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
                // start timing this game (RetroAchievements game id is the 8th field)
                playStop()
                playId = p.getOrElse(7) { "0" }.toIntOrNull() ?: 0
                playLast = SystemClock.elapsedRealtime()
                playFlushAt = playLast
                LiveGame.begin(playId, t, p.getOrElse(6) { "" })
                rotatePhrase()
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
                val pts = p.getOrElse(3) { "0" }
                val ptsN = pts.toIntOrNull() ?: 0
                val id = p.getOrElse(5) { "0" }.toIntOrNull() ?: 0
                val title = p.getOrElse(1) { "" }
                val desc = p.getOrElse(2) { "" }
                val badge = p.getOrElse(4) { "" }
                val game = gameTitle
                LiveGame.refreshWanted = true
                // the popup waits for the burst guard's verdict, so a flood of fake unlocks never shows (or celebrates)
                ui.postDelayed({
                    if (BurstGuard.blocked.contains(id) || BurstGuard.tripped()) return@postDelayed
                    Unseen.add(this, UnseenItem(title, desc, ptsN, badge, game))
                    if (totalPoints > 0) totalPoints += ptsN
                    popup.unlock(title, desc, pts, badge, game, totalPoints)
                }, BurstGuard.HOLD_MS + 700L)
            }
            "PROGRESS" -> {
                LiveGame.refreshWanted = true
                popup.progress(p.getOrElse(1) { "" }, p.getOrElse(2) { "" }, p.getOrElse(3) { "" })
            }
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
            playStop()
        } catch (_: Throwable) {
        }
        LiveGame.clear()
        NetMonitor.removeListener(netListener)
        try {
            ProxyServer.stop()
            trophies?.stop()
        } catch (_: Throwable) {
        }
        trophies = null
        TrophyLive.current = null
        try {
            hotkeys?.stop()
        } catch (_: Throwable) {
        }
        hotkeys = null
        android.os.Handler(android.os.Looper.getMainLooper()).post { GameOverlay.hide() }
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
