package com.drasticra

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Knows whether the device has working internet (a network that Android validated). Listeners are told about
 * changes on the main thread. A successful request also counts as proof of being online.
 */
object NetMonitor {
    @Volatile var online = true
        private set
    private var inited = false
    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()
    private val ui = Handler(Looper.getMainLooper())
    private var cm: ConnectivityManager? = null

    fun init(ctx: Context) {
        synchronized(this) {
            if (inited) return
            inited = true
        }
        val c = try {
            ctx.applicationContext.getSystemService(ConnectivityManager::class.java)
        } catch (_: Throwable) {
            null
        } ?: return
        cm = c
        online = hasInternet(c, safeActive(c))
        try {
            c.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
                    set(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                }

                override fun onLost(n: Network) {
                    set(hasInternet(c, safeActive(c)))
                }
            })
        } catch (_: Throwable) {
        }
    }

    /** never throws: without network-state access (or on odd ROMs) we just assume we can try the network */
    private fun safeActive(c: ConnectivityManager): Network? = try {
        c.activeNetwork
    } catch (_: Throwable) {
        null
    }

    private fun hasInternet(c: ConnectivityManager, n: Network?): Boolean {
        if (n == null) return false
        return try {
            val caps = c.getNetworkCapabilities(n) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (_: Throwable) {
            true
        }
    }

    /** true when there is any network at all (worth trying a request even if Android has not validated it) */
    fun anyNetwork(): Boolean {
        val c = cm ?: return true
        return try {
            c.activeNetwork != null
        } catch (_: Throwable) {
            true
        }
    }

    fun reportSuccess() {
        if (!online) set(true)
    }

    private fun set(v: Boolean) {
        if (v == online) return
        online = v
        ui.post { for (l in listeners) l(v) }
    }

    fun addListener(l: (Boolean) -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: (Boolean) -> Unit) {
        listeners.remove(l)
    }
}

/** Small key -> text store on disk for everything offline play needs. */
object OfflineStore {
    @Volatile private var dir: File? = null

    fun init(ctx: Context) {
        if (dir != null) return
        val d = File(ctx.applicationContext.filesDir, "offline")
        d.mkdirs()
        dir = d
    }

    private fun f(key: String): File? {
        val d = dir ?: return null
        val safe = key.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        return File(d, safe)
    }

    fun put(key: String, text: String) {
        val file = f(key) ?: return
        try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            tmp.renameTo(file)
        } catch (_: Exception) {
        }
    }

    fun get(key: String): String? {
        val file = f(key) ?: return null
        return try {
            if (file.exists()) file.readText() else null
        } catch (_: Exception) {
            null
        }
    }

    fun has(key: String): Boolean = f(key)?.exists() == true

    fun count(prefix: String): Int = dir?.listFiles()?.count { it.name.startsWith(prefix) } ?: 0

    fun clear(prefix: String) {
        dir?.listFiles()?.forEach { if (it.name.startsWith(prefix)) it.delete() }
    }
}

/** An achievement unlocked while offline, waiting to be sent to RetroAchievements. */
class PendingUnlock(
    val achId: Int, val gameId: Int, val title: String, val desc: String, val badge: String, val points: Int,
    val game: String, val gameIcon: String, val at: Long
)

/**
 * The offline proxy (the RAOfflineProxy idea, built in): every rcheevos server call passes through [handle].
 * Online, calls go to the server and the useful answers (login, game id, achievement data, your unlocks) are cached.
 * Offline, the cache answers instead, the session start is put together locally, and unlocks are saved in a queue
 * that survives restarts. When the internet is back, [flush] sends each queued unlock re-signed with the real time
 * since it was earned, so RetroAchievements records the moment you actually got it.
 */
object OfflineProxy {
    const val HOST = "https://retroachievements.org/dorequest.php"
    private const val UA = "DrasticRA/0.9.0 (Android) rcheevos/11.6"
    private val lock = Any()
    private val flushing = AtomicInteger(0)
    private val ui = Handler(Looper.getMainLooper())
    /** told (main thread) whenever the number of unsynced unlocks changes */
    val queueListeners = CopyOnWriteArrayList<() -> Unit>()

    class Res(val status: Int, val body: ByteArray)

    // ---------- helpers ----------
    fun params(post: String?): Map<String, String> {
        val out = HashMap<String, String>()
        if (post.isNullOrEmpty()) return out
        for (kv in post.split('&')) {
            val i = kv.indexOf('=')
            if (i <= 0) continue
            try {
                out[kv.substring(0, i)] = URLDecoder.decode(kv.substring(i + 1), "UTF-8")
            } catch (_: Exception) {
            }
        }
        return out
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** Plain HTTP POST (or GET when [post] is null). Throws IOException when there is no connection. */
    fun http(url: String, post: String?, ctype: String = "application/x-www-form-urlencoded", ua: String = UA): Res {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 12000
        c.readTimeout = 20000
        c.setRequestProperty("User-Agent", ua)
        if (post != null) {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", ctype.ifEmpty { "application/x-www-form-urlencoded" })
            c.outputStream.use { it.write(post.toByteArray(Charsets.UTF_8)) }
        }
        val code = c.responseCode
        val body = (if (code >= 400) c.errorStream else c.inputStream)?.use { it.readBytes() } ?: ByteArray(0)
        return Res(code, body)
    }

    private fun ok(json: String) = Res(200, json.toByteArray(Charsets.UTF_8))
    private fun fail(msg: String) = Res(-1, msg.toByteArray(Charsets.UTF_8))

    private fun success(body: ByteArray): JSONObject? = try {
        val j = JSONObject(String(body, Charsets.UTF_8))
        if (j.optBoolean("Success")) j else null
    } catch (_: Exception) {
        null
    }

    // ---------- the proxy ----------
    /** Engine thread pool: answers one rcheevos request, from the server or (offline) from the cache. */
    fun handle(url: String, post: String?, ctype: String, ua: String): Res {
        val q = params(post)
        val r = q["r"] ?: ""
        // images and other non-dorequest calls are passed straight through
        if (!url.contains("dorequest.php")) {
            return try {
                http(url, post, ctype, ua)
            } catch (e: Exception) {
                fail(e.message ?: "network error")
            }
        }
        if (NetMonitor.anyNetwork()) {
            try {
                val res = http(url, post, ctype, ua)
                NetMonitor.reportSuccess()
                if (res.status in 200..299) remember(r, q, res.body)
                // something is still waiting in the queue: this is a good moment to send it
                if (pendingCount() > 0) flushAsync()
                return res
            } catch (_: IOException) {
                // fall through to the offline answer
            } catch (_: Exception) {
            }
        }
        return offline(r, q, url, post)
    }

    /** Online answers worth keeping for offline play. */
    private fun remember(r: String, q: Map<String, String>, body: ByteArray) {
        val j = success(body) ?: return
        val text = String(body, Charsets.UTF_8)
        when (r) {
            "login2", "login" -> OfflineStore.put("login_" + (q["u"] ?: "").lowercase(), text)
            "gameid" -> OfflineStore.put("gameid_" + (q["m"] ?: ""), text)
            "patch" -> OfflineStore.put("patch_" + (q["g"] ?: ""), text)
            "startsession" -> {
                val g = q["g"]?.toIntOrNull() ?: return
                val soft = HashSet<Int>()
                val hard = HashSet<Int>()
                j.optJSONArray("Unlocks")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.optInt("ID")?.let { soft.add(it) } }
                j.optJSONArray("HardcoreUnlocks")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.optInt("ID")?.let { hard.add(it) } }
                saveUnlocks(g, soft, hard)
            }
            "awardachievement" -> {
                val a = q["a"]?.toIntOrNull() ?: return
                val g = gameIdForHash(q["m"] ?: "") ?: return
                addUnlock(g, a)
            }
        }
    }

    private fun offline(r: String, q: Map<String, String>, url: String, post: String?): Res {
        when (r) {
            "login2", "login" -> {
                val c = OfflineStore.get("login_" + (q["u"] ?: "").lowercase())
                return if (c != null) ok(c) else fail("Offline and this account has never logged in online on this device.")
            }
            "gameid" -> {
                val c = OfflineStore.get("gameid_" + (q["m"] ?: ""))
                return if (c != null) ok(c) else fail("Offline and this game is not cached yet (Settings -> Offline Play).")
            }
            "patch" -> {
                val c = OfflineStore.get("patch_" + (q["g"] ?: ""))
                return if (c != null) ok(c) else fail("Offline and this game's achievements are not cached yet.")
            }
            "startsession" -> {
                val g = q["g"]?.toIntOrNull() ?: 0
                val (soft, hard) = loadUnlocks(g)
                synchronized(lock) {
                    val qa = readQueue()
                    for (i in 0 until qa.length()) qa.optJSONObject(i)?.let { if (it.optInt("g") == g) soft.add(it.optInt("a")) }
                }
                val now = System.currentTimeMillis() / 1000L
                val o = JSONObject()
                o.put("Success", true)
                o.put("Unlocks", JSONArray().also { a -> for (id in soft) a.put(JSONObject().put("ID", id).put("When", now)) })
                o.put("HardcoreUnlocks", JSONArray().also { a -> for (id in hard) a.put(JSONObject().put("ID", id).put("When", now)) })
                o.put("ServerNow", now)
                return ok(o.toString())
            }
            "awardachievement" -> {
                val a = q["a"]?.toIntOrNull() ?: return fail("bad request")
                enqueue(url, q)
                val score = cachedScore(q["u"] ?: "")
                val o = JSONObject().put("Success", true).put("AchievementID", a)
                if (score != null) o.put("Score", score.first).put("SoftcoreScore", score.second)
                return ok(o.toString())
            }
            "ping" -> return ok("{\"Success\":true}")
        }
        // leaderboards and the rest: report a retryable failure, rcheevos keeps them until it can send
        return fail("offline")
    }

    // ---------- unlock cache ----------
    private fun saveUnlocks(g: Int, soft: Set<Int>, hard: Set<Int>) {
        val o = JSONObject()
        o.put("s", JSONArray(soft.toList()))
        o.put("h", JSONArray(hard.toList()))
        OfflineStore.put("unlocks_$g", o.toString())
    }

    fun loadUnlocks(g: Int): Pair<HashSet<Int>, HashSet<Int>> {
        val soft = HashSet<Int>()
        val hard = HashSet<Int>()
        try {
            val t = OfflineStore.get("unlocks_$g")
            if (t != null) {
                val o = JSONObject(t)
                o.optJSONArray("s")?.let { for (i in 0 until it.length()) soft.add(it.optInt(i)) }
                o.optJSONArray("h")?.let { for (i in 0 until it.length()) hard.add(it.optInt(i)) }
            }
        } catch (_: Exception) {
        }
        soft.addAll(hard)
        return Pair(soft, hard)
    }

    private fun addUnlock(g: Int, a: Int) {
        synchronized(lock) {
            val (s, h) = loadUnlocks(g)
            s.add(a)
            saveUnlocks(g, s, h)
        }
    }

    private fun gameIdForHash(hash: String): Int? = try {
        OfflineStore.get("gameid_$hash")?.let { JSONObject(it).optInt("GameID").takeIf { id -> id > 0 } }
    } catch (_: Exception) {
        null
    }

    private fun cachedScore(user: String): Pair<Int, Int>? = try {
        OfflineStore.get("login_" + user.lowercase())?.let { val j = JSONObject(it); Pair(j.optInt("Score"), j.optInt("SoftcoreScore")) }
    } catch (_: Exception) {
        null
    }

    /** Title, description, badge and points of an achievement, read from the cached game data. */
    private fun achInfo(g: Int, a: Int): JSONObject? = try {
        val pd = JSONObject(OfflineStore.get("patch_$g") ?: "").optJSONObject("PatchData")
        var found: JSONObject? = null
        val arr = pd?.optJSONArray("Achievements")
        if (arr != null) for (i in 0 until arr.length()) {
            val x = arr.optJSONObject(i) ?: continue
            if (x.optInt("ID") == a) {
                found = x
                break
            }
        }
        found?.put("_game", pd?.optString("Title") ?: "")?.put("_icon", pd?.optString("ImageIcon") ?: "")
    } catch (_: Exception) {
        null
    }

    // ---------- queue ----------
    private fun readQueue(): JSONArray = try {
        JSONArray(OfflineStore.get("queue.json") ?: "[]")
    } catch (_: Exception) {
        JSONArray()
    }

    private fun writeQueue(a: JSONArray) {
        OfflineStore.put("queue.json", a.toString())
        ui.post { for (l in queueListeners) l() }
    }

    private fun enqueue(url: String, q: Map<String, String>) {
        val a = q["a"]?.toIntOrNull() ?: return
        val g = gameIdForHash(q["m"] ?: "") ?: 0
        val info = if (g > 0) achInfo(g, a) else null
        val o = JSONObject()
        o.put("url", url)
        o.put("u", q["u"] ?: "")
        o.put("t", q["t"] ?: "")
        o.put("a", a)
        o.put("h", q["h"] ?: "0")
        o.put("m", q["m"] ?: "")
        o.put("o", q["o"]?.toLongOrNull() ?: 0L)
        o.put("at", System.currentTimeMillis() / 1000L)
        o.put("g", g)
        o.put("title", info?.optString("Title") ?: "Achievement $a")
        o.put("desc", info?.optString("Description") ?: "")
        o.put("badge", info?.optString("BadgeName") ?: "")
        o.put("pts", info?.optInt("Points") ?: 0)
        o.put("game", info?.optString("_game") ?: "")
        o.put("icon", info?.optString("_icon") ?: "")
        synchronized(lock) {
            val arr = readQueue()
            for (i in 0 until arr.length()) if (arr.optJSONObject(i)?.optInt("a") == a) return // already queued
            arr.put(o)
            writeQueue(arr)
        }
        AppLog.add("Offline: saved unlock '" + o.optString("title") + "' - it will be sent when the internet is back.")
    }

    fun pendingCount(): Int = synchronized(lock) { readQueue().length() }

    fun pending(): List<PendingUnlock> {
        val arr = synchronized(lock) { readQueue() }
        val out = ArrayList<PendingUnlock>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(PendingUnlock(o.optInt("a"), o.optInt("g"), o.optString("title"), o.optString("desc"),
                o.optString("badge"), o.optInt("pts"), o.optString("game"), o.optString("icon"), o.optLong("at")))
        }
        return out.sortedByDescending { it.at }
    }

    fun pendingIds(gameId: Int): Set<Int> = pending().filter { it.gameId == gameId }.map { it.achId }.toSet()

    fun isPending(achId: Int): Boolean = pending().any { it.achId == achId }

    fun flushAsync() {
        if (flushing.get() > 0) return
        Thread { flush() }.start()
    }

    /** Sends every queued unlock (oldest first). Stops at the first network problem; returns how many were sent. */
    fun flush(): Int {
        if (!flushing.compareAndSet(0, 1)) return 0
        var sent = 0
        try {
            while (true) {
                val o = synchronized(lock) { readQueue().optJSONObject(0) } ?: break
                val a = o.optInt("a")
                val u = o.optString("u")
                val h = o.optString("h", "0")
                // real time since the unlock = seconds it had already waited inside rcheevos + time spent in our queue
                val o2 = (o.optLong("o") + (System.currentTimeMillis() / 1000L - o.optLong("at"))).coerceAtLeast(1L)
                val sig = md5("$a$u$h$a$o2")
                val sb = StringBuilder("r=awardachievement&u=").append(enc(u)).append("&t=").append(enc(o.optString("t")))
                    .append("&a=").append(a).append("&h=").append(h)
                if (o.optString("m").isNotEmpty()) sb.append("&m=").append(enc(o.optString("m")))
                sb.append("&o=").append(o2).append("&v=").append(sig)
                val res = try {
                    http(o.optString("url", HOST).ifEmpty { HOST }, sb.toString())
                } catch (_: Exception) {
                    break // still offline
                }
                NetMonitor.reportSuccess()
                val text = String(res.body, Charsets.UTF_8)
                val j = try { JSONObject(text) } catch (_: Exception) { null }
                val done = j != null && (j.optBoolean("Success") || j.optString("Error").contains("already", true))
                if (!done && res.status in 500..599) break // server trouble: try again later
                synchronized(lock) {
                    val arr = readQueue()
                    val keep = JSONArray()
                    for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { if (it.optInt("a") != a) keep.put(it) }
                    writeQueue(keep)
                }
                if (done) {
                    sent++
                    if (o.optInt("g") > 0) addUnlock(o.optInt("g"), a)
                    AppLog.add("Synced offline unlock: " + o.optString("title"))
                } else {
                    AppLog.add("Server refused offline unlock '" + o.optString("title") + "': " + (j?.optString("Error") ?: text.take(80)))
                }
            }
        } finally {
            flushing.set(0)
        }
        return sent
    }

    // ---------- offline game page ----------
    /** Builds a game's achievement list from the cached game data + unlocks (for game pages while offline). */
    fun detailFromCache(gameId: Int): RaGameDetail? {
        return try {
            val pd = JSONObject(OfflineStore.get("patch_$gameId") ?: return null).optJSONObject("PatchData") ?: return null
            val (soft, _) = loadUnlocks(gameId)
            val pend = pendingIds(gameId)
            val list = ArrayList<RaAchDetail>()
            val arr = pd.optJSONArray("Achievements") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val x = arr.optJSONObject(i) ?: continue
                if (x.optInt("Flags", 3) != 3) continue // core set only
                val id = x.optInt("ID")
                val got = soft.contains(id) || pend.contains(id)
                list.add(RaAchDetail(id, x.optString("Title"), x.optString("Description"), x.optInt("Points"),
                    x.optString("BadgeName"), got, if (got) "cached" else "", i))
            }
            val icon = pd.optString("ImageIcon").ifEmpty { "" }
            RaGameDetail(gameId, pd.optString("Title"), icon, "Nintendo DS", list.size, list.count { it.earned }, list)
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Offline Play: hashes every .nds in a folder exactly like rcheevos does and caches, per game, the id, the full
 * achievement data and your unlocks, so any of them can be played (and earned) later without internet.
 * No limits on how many; runs a few at a time.
 */
object RomCacher {
    class Progress(
        val total: Int, val done: Int, val cached: Int, val unsupported: Int, val failed: Int,
        val current: String, val etaMs: Long, val running: Boolean, val message: String
    )

    @Volatile var progress: Progress? = null
        private set
    val listeners = CopyOnWriteArrayList<(Progress) -> Unit>()
    @Volatile private var cancel = false
    private val ui = Handler(Looper.getMainLooper())

    val running: Boolean get() = progress?.running == true

    fun cancel() {
        cancel = true
    }

    private fun publish(p: Progress) {
        progress = p
        ui.post { for (l in listeners) l(p) }
    }

    fun start(ctx: Context, folder: String, force: Boolean) {
        if (running) return
        val app = ctx.applicationContext
        OfflineStore.init(app)
        val prefs = app.prefs()
        val user = prefs.getString("user_ra", "") ?: ""
        val token = prefs.getString("token", "") ?: ""
        cancel = false
        if (user.isEmpty() || token.isEmpty()) {
            publish(Progress(0, 0, 0, 0, 0, "", 0, false, "Log in first (Settings -> Account)."))
            return
        }
        publish(Progress(0, 0, 0, 0, 0, "Looking for ROMs...", 0, true, ""))
        Thread {
            val root = File(folder)
            val roms = if (root.exists()) root.walkTopDown().maxDepth(10)
                .filter { it.isFile && it.name.endsWith(".nds", true) && it.length() >= 0x200 }.toList() else emptyList()
            if (roms.isEmpty()) {
                publish(Progress(0, 0, 0, 0, 0, "", 0, false, "No .nds files found in $folder"))
                return@Thread
            }
            val total = roms.size
            val done = AtomicInteger(0)
            val cached = AtomicInteger(0)
            val unsupported = AtomicInteger(0)
            val failed = AtomicInteger(0)
            val t0 = SystemClock.elapsedRealtime()
            val pool = Executors.newFixedThreadPool(3)
            fun report(name: String) {
                val d = done.get()
                val el = SystemClock.elapsedRealtime() - t0
                val eta = if (d > 0) el / d * (total - d) else 0L
                publish(Progress(total, d, cached.get(), unsupported.get(), failed.get(), name, eta, true, ""))
            }
            report("Starting...")
            for (f in roms) {
                pool.execute {
                    if (cancel) return@execute
                    val name = f.nameWithoutExtension
                    try {
                        when (cacheOne(f, user, token, force)) {
                            1 -> cached.incrementAndGet()
                            0 -> unsupported.incrementAndGet()
                            else -> failed.incrementAndGet()
                        }
                    } catch (_: Exception) {
                        failed.incrementAndGet()
                    }
                    done.incrementAndGet()
                    report(name)
                }
            }
            pool.shutdown()
            while (!pool.isTerminated) {
                if (cancel) pool.shutdownNow()
                Thread.sleep(200)
            }
            val msg = (if (cancel) "Stopped. " else "Done! ") + "${cached.get()} games ready for offline play" +
                (if (unsupported.get() > 0) ", ${unsupported.get()} have no achievements" else "") +
                (if (failed.get() > 0) ", ${failed.get()} failed (try again online)" else "") + "."
            publish(Progress(total, done.get(), cached.get(), unsupported.get(), failed.get(), "", 0, false, msg))
            AppLog.add("Offline cache: $msg")
        }.start()
    }

    /** 1 = cached, 0 = not on RetroAchievements, -1 = failed */
    private fun cacheOne(f: File, user: String, token: String, force: Boolean): Int {
        val hash = Native.hashFile(f.absolutePath)
        if (hash.isEmpty()) return -1
        OfflineStore.put("rom_$hash", f.nameWithoutExtension)
        var gid = 0
        val cachedId = OfflineStore.get("gameid_$hash")
        if (cachedId != null && !force) {
            gid = try { JSONObject(cachedId).optInt("GameID") } catch (_: Exception) { 0 }
        } else {
            val r = OfflineProxy.http(OfflineProxy.HOST, "r=gameid&m=$hash")
            val j = try { JSONObject(String(r.body, Charsets.UTF_8)) } catch (_: Exception) { return -1 }
            if (!j.optBoolean("Success")) return -1
            OfflineStore.put("gameid_$hash", j.toString())
            gid = j.optInt("GameID")
        }
        if (gid <= 0) return 0
        val auth = "&u=" + URLEncoder.encode(user, "UTF-8") + "&t=" + URLEncoder.encode(token, "UTF-8") + "&g=" + gid
        if (force || !OfflineStore.has("patch_$gid")) {
            val r = OfflineProxy.http(OfflineProxy.HOST, "r=patch$auth")
            val t = String(r.body, Charsets.UTF_8)
            if (!(try { JSONObject(t).optBoolean("Success") } catch (_: Exception) { false })) return -1
            OfflineStore.put("patch_$gid", t)
        }
        // your unlocks (softcore + hardcore) without starting a session on the site
        val soft = HashSet<Int>()
        val hard = HashSet<Int>()
        for (h in 0..1) {
            val r = OfflineProxy.http(OfflineProxy.HOST, "r=unlocks$auth&h=$h")
            val j = try { JSONObject(String(r.body, Charsets.UTF_8)) } catch (_: Exception) { return -1 }
            if (!j.optBoolean("Success")) return -1
            val arr = j.optJSONArray("UserUnlocks") ?: JSONArray()
            for (i in 0 until arr.length()) (if (h == 1) hard else soft).add(arr.optInt(i))
        }
        val o = JSONObject().put("s", JSONArray(soft.toList())).put("h", JSONArray(hard.toList()))
        OfflineStore.put("unlocks_$gid", o.toString())
        return 1
    }

    fun cachedGames(): Int = OfflineStore.count("patch_")
}
