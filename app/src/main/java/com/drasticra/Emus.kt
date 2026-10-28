package com.drasticra

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/** Emulators that use RetroAchievements themselves; Drastic RA sits between them and the server as a local proxy. */
object RaEmus {
    const val PORT = 47313
    const val HOST = "http://127.0.0.1:$PORT"

    class Emu(val key: String, val label: String, val words: List<String>)

    val all = listOf(
        Emu("retroarch", "RetroArch", listOf("retroarch")),
        Emu("ppsspp", "PPSSPP", listOf("ppsspp")),
        Emu("armsx2", "ARMSX2", listOf("armsx2"))
    )

    /** Installed packages for each emulator key. */
    fun installed(): Map<String, List<String>> {
        val pk = rootSh("pm list packages 2>/dev/null").lines().map { it.removePrefix("package:").trim() }.filter { it.isNotEmpty() }
        return all.associate { e -> e.key to pk.filter { p -> e.words.any { p.contains(it, true) } } }
    }

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** Config files of an emulator (RetroArch retroarch.cfg, PPSSPP ppsspp.ini, ARMSX2 PCSX2.ini). */
    fun configFiles(key: String, pkgs: List<String>): List<String> {
        val name = when (key) {
            "retroarch" -> "retroarch.cfg"
            "ppsspp" -> "ppsspp.ini"
            else -> "PCSX2.ini"
        }
        val dirs = ArrayList<String>()
        for (p in pkgs) {
            dirs.add("/data/data/$p")
            dirs.add("\$E/Android/data/$p")
        }
        when (key) {
            "retroarch" -> dirs.add("\$E/RetroArch")
            "ppsspp" -> dirs.add("\$E/PSP")
            else -> { dirs.add("\$E/ARMSX2"); dirs.add("\$E/PCSX2") }
        }
        val d = dirs.joinToString(" ") { if (it.startsWith("\$E")) "\"$it\"" else q(it) }
        val out = rootSh("E=/storage/emulated/0; [ -d \$E/Android ] || E=/data/media/0; for d in $d; do [ -d \"\$d\" ] && " +
            "find \"\$d\" -maxdepth 6 -type f -name '$name' 2>/dev/null; done")
        return out.lines().map { it.trim() }.filter { it.endsWith(name) }.distinct()
    }

    fun running(pkg: String): Boolean = rootSh("pidof $pkg 2>/dev/null").trim().isNotEmpty()

    /** Sets (or with [on] false removes) "key = value" in an INI section, keeping everything else as it was. */
    private fun setIni(text: String, section: String, key: String, value: String?): String {
        val lines = text.split('\n').toMutableList()
        var inSec = false
        var secAt = -1
        var done = false
        var i = 0
        while (i < lines.size) {
            val t = lines[i].trim()
            if (t.startsWith("[") && t.endsWith("]")) {
                if (inSec && !done && value != null) {
                    lines.add(i, "$key = $value")
                    done = true
                    i++
                }
                inSec = t.equals("[$section]", true)
                if (inSec) secAt = i
            } else if (inSec && t.substringBefore('=').trim().equals(key, true)) {
                if (value != null && !done) {
                    lines[i] = "$key = $value"
                    done = true
                } else {
                    lines.removeAt(i)
                    continue
                }
            }
            i++
        }
        if (!done && value != null) {
            if (secAt >= 0) lines.add(lines.size.coerceAtLeast(0), "$key = $value").also { }
            else {
                lines.add("")
                lines.add("[$section]")
                lines.add("$key = $value")
            }
        }
        return lines.joinToString("\n")
    }

    /** Sets a top-level "key = "value"" in retroarch.cfg. */
    private fun setCfg(text: String, key: String, value: String?): String {
        val kept = text.split('\n').filter { it.substringBefore('=').trim() != key }.toMutableList()
        while (kept.isNotEmpty() && kept.last().isBlank()) kept.removeAt(kept.size - 1)
        if (value != null) kept.add("$key = \"$value\"")
        return kept.joinToString("\n") + "\n"
    }

    /**
     * Points an emulator's achievement server at Drastic RA (on) or back at RetroAchievements (off).
     * [hideOwn] also turns off the emulator's own unlock popups so ours are not doubled. Returns a status line.
     */
    fun apply(ctx: Context, key: String, on: Boolean, hideOwn: Boolean): String {
        val label = all.first { it.key == key }.label
        val pkgs = installed()[key] ?: emptyList()
        if (pkgs.isEmpty()) return "$label: not installed"
        val busy = pkgs.filter { running(it) }
        if (busy.isNotEmpty()) {
            // a running emulator rewrites its settings on exit, so close it first (its game state is its own business)
            for (p in busy) rootSh("am force-stop $p")
            Thread.sleep(600)
        }
        val files = configFiles(key, pkgs)
        if (files.isEmpty()) return "$label: no settings file yet - open $label once, then try again"
        var n = 0
        for (f in files) {
            val tmp = File(ctx.cacheDir, "emu_cfg.tmp")
            val text = rootSh("cat '" + f.replace("'", "'\\''") + "'")
            if (text.startsWith("su failed")) continue
            val out = when (key) {
                "retroarch" -> {
                    var t = setCfg(text, "cheevos_custom_host", if (on) HOST else null)
                    if (on) t = setCfg(t, "cheevos_enable", "true")
                    t = setCfg(t, "cheevos_visibility_unlock", if (on && hideOwn) "false" else null)
                    t = setCfg(t, "cheevos_visibility_mastery", if (on && hideOwn) "false" else null)
                    t
                }
                "ppsspp" -> {
                    var t = setIni(text, "Achievements", "AchievementsHost", if (on) HOST else null)
                    if (on) t = setIni(t, "Achievements", "AchievementsEnable", "True")
                    t
                }
                else -> {
                    var t = setIni(text, "Achievements", "Host", if (on) HOST else null)
                    if (on) t = setIni(t, "Achievements", "Enabled", "true")
                    t = setIni(t, "Achievements", "Notifications", if (on && hideOwn) "false" else null)
                    t
                }
            }
            tmp.writeText(out)
            // "cat >" keeps the file's owner and permissions
            rootSh("cat '" + tmp.absolutePath + "' > '" + f.replace("'", "'\\''") + "'")
            tmp.delete()
            n++
        }
        ctx.prefs().edit().putBoolean("emu_on_$key", on).apply()
        return "$label: " + (if (on) "now uses Drastic RA (offline play + our popups)" else "back to RetroAchievements directly") +
            " ($n file${if (n == 1) "" else "s"})" + (if (busy.isNotEmpty()) " - it was closed to apply this" else "")
    }
}

/**
 * The local achievement server the emulators talk to (127.0.0.1 only). Every call goes through the offline proxy,
 * so these emulators get offline play and the unlock queue too, and Drastic RA sees what happens to show its popups.
 */
object ProxyServer {
    @Volatile private var server: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(4)
    private const val MEDIA_HOST = "https://media.retroachievements.org"

    val running: Boolean get() = server?.isClosed == false

    fun start(ctx: Context) {
        if (running) return
        val app = ctx.applicationContext
        try {
            val ss = ServerSocket(RaEmus.PORT, 16, InetAddress.getByName("127.0.0.1"))
            ss.reuseAddress = true
            server = ss
            Thread {
                AppLog.add("Achievement proxy listening on " + RaEmus.HOST)
                while (!ss.isClosed) {
                    val s = try { ss.accept() } catch (_: Exception) { break }
                    pool.execute { serve(app, s) }
                }
            }.start()
        } catch (e: Exception) {
            AppLog.add("Achievement proxy could not start: " + e.message)
        }
    }

    fun stop() {
        try {
            server?.close()
        } catch (_: Exception) {
        }
        server = null
    }

    private fun readLine(i: InputStream): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = i.read()
            if (c < 0) return if (b.size() == 0) null else b.toString("UTF-8")
            if (c == '\n'.code) break
            if (c != '\r'.code) b.write(c)
        }
        return b.toString("UTF-8")
    }

    private fun serve(ctx: Context, s: Socket) {
        s.use { sock ->
            try {
                sock.soTimeout = 30000
                val inp = BufferedInputStream(sock.getInputStream())
                val first = readLine(inp) ?: return
                val parts = first.split(' ')
                if (parts.size < 2) return
                val method = parts[0]
                val target = parts[1]
                var len = 0
                var ctype = "application/x-www-form-urlencoded"
                var ua = "DrasticRA proxy"
                while (true) {
                    val h = readLine(inp) ?: break
                    if (h.isEmpty()) break
                    val k = h.substringBefore(':').trim().lowercase()
                    val v = h.substringAfter(':', "").trim()
                    when (k) {
                        "content-length" -> len = v.toIntOrNull() ?: 0
                        "content-type" -> ctype = v
                        "user-agent" -> ua = v
                    }
                }
                val body = ByteArray(len)
                var got = 0
                while (got < len) {
                    val r = inp.read(body, got, len - got)
                    if (r < 0) break
                    got += r
                }
                val path = target.substringBefore('?')
                val query = target.substringAfter('?', "")
                val res: OfflineProxy.Res
                var mime = "application/json"
                if (path.endsWith("dorequest.php")) {
                    val post = listOf(query, String(body, 0, got, Charsets.UTF_8)).filter { it.isNotEmpty() }.joinToString("&")
                    res = OfflineProxy.handle(OfflineProxy.HOST, post, ctype, ua)
                    try {
                        ProxyGames.after(ctx, OfflineProxy.params(post), res)
                    } catch (_: Exception) {
                    }
                } else {
                    // badges, game icons, avatars: from the media server, kept on disk so they show offline too
                    res = media(ctx, path)
                    mime = if (path.endsWith(".png", true)) "image/png" else "application/octet-stream"
                }
                val status = if (res.status <= 0) 503 else res.status
                val head = "HTTP/1.1 $status ${if (status == 200) "OK" else "Error"}\r\nContent-Type: $mime\r\n" +
                    "Content-Length: ${res.body.size}\r\nConnection: close\r\n\r\n"
                val out = sock.getOutputStream()
                out.write(head.toByteArray(Charsets.US_ASCII))
                if (method != "HEAD") out.write(res.body)
                out.flush()
            } catch (_: Exception) {
            }
        }
    }

    private fun media(ctx: Context, path: String): OfflineProxy.Res {
        val f = File(ctx.cacheDir, "ramedia/" + path.trimStart('/').replace('/', '_'))
        if (f.exists() && f.length() > 0) return OfflineProxy.Res(200, f.readBytes())
        return try {
            val r = OfflineProxy.http(MEDIA_HOST + path, null)
            if (r.status == 200 && r.body.isNotEmpty()) {
                f.parentFile?.mkdirs()
                f.writeBytes(r.body)
            }
            r
        } catch (e: Exception) {
            OfflineProxy.Res(-1, ByteArray(0))
        }
    }
}

/**
 * Follows the game an emulator plays through the proxy: game-loaded popup, unlock popups, the overlay's list,
 * session / total play time, and the "caught" celebration next time the app opens.
 */
object ProxyGames {
    @Volatile var popup: Popup? = null
    @Volatile private var gameId = 0
    @Volatile private var lastSeen = 0L
    private var ticker: Thread? = null
    private var pendingPlay = 0L
    private var lastFlush = 0L

    private fun ok(res: OfflineProxy.Res): JSONObject? = try {
        val j = JSONObject(String(res.body, Charsets.UTF_8))
        if (j.optBoolean("Success")) j else null
    } catch (_: Exception) {
        null
    }

    private class Patch(val title: String, val icon: String, val ach: List<JSONObject>)

    private fun patch(g: Int): Patch? = try {
        val pd = JSONObject(OfflineStore.get("patch_$g") ?: "").getJSONObject("PatchData")
        val arr = pd.optJSONArray("Achievements")
        val list = ArrayList<JSONObject>()
        if (arr != null) for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { if (it.optInt("Flags", 3) == 3) list.add(it) }
        val icon = pd.optString("ImageIcon").substringAfterLast('/').substringBeforeLast('.')
        Patch(pd.optString("Title"), icon, list)
    } catch (_: Exception) {
        null
    }

    fun after(ctx: Context, q: Map<String, String>, res: OfflineProxy.Res) {
        when (q["r"]) {
            "startsession" -> if (ok(res) != null) begin(ctx, q["g"]?.toIntOrNull() ?: 0)
            "ping" -> {
                val g = q["g"]?.toIntOrNull() ?: 0
                lastSeen = SystemClock.elapsedRealtime()
                if (g != 0 && g != gameId) begin(ctx, g)
            }
            "awardachievement" -> {
                val j = ok(res) ?: return
                val a = q["a"]?.toIntOrNull() ?: j.optInt("AchievementID")
                unlock(ctx, a, j.optLong("Score") + j.optLong("SoftcoreScore"))
            }
        }
    }

    private fun begin(ctx: Context, g: Int) {
        if (g <= 0) return
        val p = patch(g) ?: return
        gameId = g
        lastSeen = SystemClock.elapsedRealtime()
        val unlocked = OfflineProxy.loadUnlocks(g).first + OfflineProxy.pendingIds(g)
        LiveGame.begin(g, p.title, p.icon)
        publish(p, unlocked, emptySet())
        var pts = 0
        var ptsGot = 0
        for (a in p.ach) {
            pts += a.optInt("Points")
            if (unlocked.contains(a.optInt("ID"))) ptsGot += a.optInt("Points")
        }
        val got = p.ach.count { unlocked.contains(it.optInt("ID")) }
        AppLog.add("Game (proxy): ${p.title} - $got / ${p.ach.size}")
        AppLog.setStatus("Watching: ${p.title}", true)
        popup?.game(p.title, p.icon, got, p.ach.size, "$ptsGot / $pts pts")
        startTicker(ctx)
    }

    private fun publish(p: Patch, unlocked: Set<Int>, fresh: Set<Int>) {
        LiveGame.list = p.ach.map {
            val id = it.optInt("ID")
            LiveAch(id, it.optString("Title"), it.optString("Description"), it.optString("BadgeName"), it.optInt("Points"),
                unlocked.contains(id), "", 0f, 0L, if (unlocked.contains(id)) 2 else 1, fresh.contains(id))
        }
        LiveGame.version++
    }

    private val sessionFresh = HashSet<Int>()

    private fun unlock(ctx: Context, a: Int, total: Long) {
        val g = gameId
        val p = patch(g)
        val info = p?.ach?.firstOrNull { it.optInt("ID") == a }
        val title = info?.optString("Title") ?: "Achievement $a"
        val desc = info?.optString("Description") ?: ""
        val badge = info?.optString("BadgeName") ?: ""
        val pts = info?.optInt("Points") ?: 0
        Unseen.add(ctx, UnseenItem(title, desc, pts, badge, p?.title ?: ""))
        AppLog.add("UNLOCKED (proxy): $title ($pts pts)")
        popup?.unlock(title, desc, pts.toString(), badge, p?.title ?: "", total.toInt())
        if (p != null) {
            sessionFresh.add(a)
            val unlocked = OfflineProxy.loadUnlocks(g).first + OfflineProxy.pendingIds(g) + a
            publish(p, unlocked, sessionFresh)
            if (p.ach.isNotEmpty() && p.ach.all { unlocked.contains(it.optInt("ID")) }) popup?.mastered()
        }
    }

    /** session / play time while the emulator keeps talking to us (it pings every couple of minutes) */
    private fun startTicker(ctx: Context) {
        if (ticker != null) return
        val t = Thread {
            while (true) {
                Thread.sleep(1000L)
                val g = gameId
                if (g == 0) continue
                if (SystemClock.elapsedRealtime() - lastSeen > 6 * 60 * 1000L) {
                    if (LiveGame.id == g) LiveGame.clear()
                    gameId = 0
                    sessionFresh.clear()
                    continue
                }
                if (LiveGame.id == g) {
                    LiveGame.sessionMs += 1000L
                    pendingPlay += 1000L
                    LiveGame.pendingMs = pendingPlay
                    if (SystemClock.elapsedRealtime() - lastFlush > 15000L) {
                        lastFlush = SystemClock.elapsedRealtime()
                        val sp = ctx.prefs()
                        sp.edit().putLong("play_ms_$g", sp.getLong("play_ms_$g", 0L) + pendingPlay).apply()
                        pendingPlay = 0
                        LiveGame.pendingMs = 0
                    }
                }
            }
        }
        t.isDaemon = true
        ticker = t
        t.start()
    }
}
