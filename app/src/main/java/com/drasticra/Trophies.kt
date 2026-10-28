package com.drasticra

import android.content.Context
import android.os.SystemClock
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

class Trophy(
    val id: Int, val name: String, val detail: String, val grade: String, val hidden: Boolean,
    val unlocked: Boolean, val time: Long, val icon: String
)

class TrophyGame(
    val key: String, val title: String, val console: String, val emulator: String, val commId: String,
    val icon: String, val lastPlayed: Long, val trophies: List<Trophy>
) {
    val got: Int get() = trophies.count { it.unlocked }
    fun count(g: String) = trophies.count { it.grade == g && it.unlocked }
    val lastUnlock: Long get() = trophies.filter { it.unlocked }.maxOfOrNull { it.time } ?: 0L
}

/** Colours and names of the PlayStation trophy grades. */
object Grades {
    fun color(g: String): Int = when (g) {
        "P" -> 0xFF9FD8FF.toInt()
        "G" -> 0xFFFFC94A.toInt()
        "S" -> 0xFFC9D1DC.toInt()
        "B" -> 0xFFD08A55.toInt()
        else -> 0xFF9AA0B5.toInt()
    }

    fun name(g: String): String = when (g) {
        "P" -> "Platinum"
        "G" -> "Gold"
        "S" -> "Silver"
        "B" -> "Bronze"
        else -> "Trophy"
    }

    /** edge glow colours for a trophy popup of this grade */
    fun glow(g: String): IntArray = when (g) {
        "P" -> intArrayOf(0xFF9FD8FF.toInt(), 0xFFE8F6FF.toInt(), 0xFFB9A7FF.toInt(), 0xFF7FE8FF.toInt())
        "G" -> intArrayOf(0xFFFFC94A.toInt(), 0xFFFFF0B0.toInt(), 0xFFFFA62B.toInt())
        "S" -> intArrayOf(0xFFC9D1DC.toInt(), 0xFFFFFFFF.toInt(), 0xFF9AA6B8.toInt())
        else -> intArrayOf(0xFFD08A55.toInt(), 0xFFF2C29B.toInt(), 0xFFA8643A.toInt())
    }
}

/**
 * PS3 (ARMSX3 / RPCS3), PS Vita (Vita3K, Vita3K Plus) and PS4 (Bachata S4, Bachata S4+, shadPS4) trophies.
 * With root, the emulators' private folders are searched for trophy files by their signature (so renamed forks
 * work too), the small files are mirrored into this app, and parsed:
 *  - RPCS3:   TROPCONF.SFM / TROP.SFM (XML) + TROPUSR.DAT (big-endian tables, times in microseconds since year 1)
 *  - Vita3K:  trophy/conf/ID/TROP.SFM (XML) + trophy/data/ID/TROPUSR.DAT (Vita3K's own little-endian bit flags)
 *  - shadPS4: ID/Xml/TROP.XML + home/USER/trophy/ID.xml (unlockstate / timestamp attributes; older builds in TROP.XML)
 */
object TrophyScanner {
    private const val RPCS3_MAGIC = 0x818F54AD.toInt()
    private const val VITA_MAGIC = 0x12D5819A
    private val packageWords = listOf("armsx3", "rpcs3", "vita3k", "bachata", "shadps4")

    @Volatile var scanning = false
        private set
    /** last known file states "path" -> "mtime|size" (watch mode only re-checks these) */
    private val known = HashMap<String, String>()
    private val sets = HashMap<String, SetFiles>()
    @Volatile var lastForeground = ""
        private set

    private class SetFiles(val key: String, val kind: String, val dir: String) {
        var conf: String? = null    // XML with names
        var usr: String? = null     // TROPUSR.DAT or save xml
        var iconDir: String? = null
        var mtime = 0L
    }

    private fun md5(s: String) =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.substring(0, 16)

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    fun mirrorDir(ctx: Context) = File(ctx.applicationContext.filesDir, "trophy_mirror")

    /** Installed trophy emulators (package names). */
    fun packages(): List<String> =
        rootSh("pm list packages 2>/dev/null").lines().map { it.removePrefix("package:").trim() }
            .filter { p -> p.isNotEmpty() && packageWords.any { p.contains(it, true) } }

    private fun searchDirs(ctx: Context): List<String> {
        val out = ArrayList<String>()
        for (p in packages()) {
            out.add("/data/data/$p")
            out.add("\$E/Android/data/$p")
        }
        for (d in listOf("Vita3K", "ARMSX3", "RPCS3", "rpcs3", "shadPS4", "BachataS4", "Bachata", "Bachata-S4")) out.add("\$E/$d")
        val extra = ctx.prefs().getString("trophy_dirs", "") ?: ""
        for (x in extra.split(',', '\n')) if (x.trim().isNotEmpty()) out.add(x.trim())
        return out
    }

    /** Full search for trophy files (slow-ish, run on start and about once a minute). */
    private fun findAll(ctx: Context): List<String> {
        val dirs = searchDirs(ctx).joinToString(" ") { if (it.startsWith("\$E")) "\"$it\"" else q(it) }
        val script = "E=/storage/emulated/0; [ -d \$E/Android ] || E=/data/media/0; " +
            "for d in $dirs; do [ -d \"\$d\" ] && find \"\$d\" -maxdepth 12 -type f \\( -name TROPUSR.DAT -o -name 'TROP*.SFM' " +
            "-o -iname 'TROP*.XML' -o -name 'NPWR*.xml' -o -name 'NPWR*.XML' \\) -exec stat -c '%Y|%s|%n' {} + 2>/dev/null; done; " +
            "echo \"FG|\$(dumpsys activity activities 2>/dev/null | grep -m1 -E 'mResumedActivity|topResumedActivity')\""
        return rootSh(script).lines()
    }

    private fun parseForeground(line: String) {
        // "...ActivityRecord{1234 u0 com.armsx3/.MainActivity t12}"
        val m = Regex("u\\d+ ([A-Za-z0-9._]+)/").find(line)
        lastForeground = m?.groupValues?.get(1) ?: ""
    }

    /** Re-stats only the files we already know (cheap, used every few seconds while watching). */
    private fun statKnown(): List<String> {
        if (known.isEmpty()) return emptyList()
        val script = "stat -c '%Y|%s|%n' " + known.keys.joinToString(" ") { q(it) } + " 2>/dev/null; " +
            "echo \"FG|\$(dumpsys activity activities 2>/dev/null | grep -m1 -E 'mResumedActivity|topResumedActivity')\""
        return rootSh(script).lines()
    }

    /** Groups found files into trophy sets (one per game). */
    private fun group(files: List<String>) {
        for (f in files) {
            val name = f.substringAfterLast('/')
            val dir = f.substringBeforeLast('/')
            val parent = dir.substringAfterLast('/')
            when {
                name == "TROPUSR.DAT" && dir.contains("/trophy/data/") -> {
                    // Vita3K: .../trophy/data/NPWRxxxxx_00/TROPUSR.DAT + .../trophy/conf/NPWRxxxxx_00/
                    val k = md5(dir)
                    val s = sets.getOrPut(k) { SetFiles(k, "vita", dir) }
                    s.usr = f
                    val confDir = dir.replace("/trophy/data/", "/trophy/conf/")
                    s.iconDir = confDir
                }
                name == "TROPUSR.DAT" -> {
                    val k = md5(dir)
                    val s = sets.getOrPut(k) { SetFiles(k, "ps3", dir) }
                    s.usr = f
                    s.iconDir = dir
                }
                (name == "TROPCONF.SFM" || name == "TROP.SFM") && dir.contains("/trophy/conf/") -> {
                    val k = md5(dir.replace("/trophy/conf/", "/trophy/data/"))
                    val s = sets.getOrPut(k) { SetFiles(k, "vita", dir.replace("/trophy/conf/", "/trophy/data/")) }
                    if (s.conf == null || name == "TROP.SFM") s.conf = f
                    s.iconDir = dir
                }
                name == "TROPCONF.SFM" || name == "TROP.SFM" -> {
                    val k = md5(dir)
                    val s = sets.getOrPut(k) { SetFiles(k, "ps3", dir) }
                    if (s.conf == null || name == "TROP.SFM") s.conf = f
                    s.iconDir = dir
                }
                name.equals("TROP.XML", true) || name.equals("TROP_01.XML", true) || name.equals("TROP_18.XML", true) -> {
                    // shadPS4: .../NPWRxxxxx_00/Xml/TROP.XML (older: .../TrophyFiles/trophy00/Xml/TROP.XML)
                    if (!parent.equals("Xml", true)) continue
                    val setDir = dir.substringBeforeLast('/')
                    val k = md5(setDir)
                    val s = sets.getOrPut(k) { SetFiles(k, "ps4", setDir) }
                    // prefer English (US) names, then the master file
                    if (s.conf == null || name.equals("TROP_01.XML", true) ||
                        (name.equals("TROP_18.XML", true) && !s.conf!!.endsWith("TROP_01.XML", true))) s.conf = f
                    s.iconDir = "$setDir/Icons"
                }
                name.startsWith("NPWR", true) && name.endsWith(".xml", true) && dir.contains("/trophy") -> {
                    // shadPS4 save: home/USER/trophy/NPWRxxxxx_00.xml -> belongs to the set folder named like it
                    val comm = name.substringBeforeLast('.')
                    val owner = sets.values.firstOrNull { it.kind == "ps4" && it.dir.endsWith("/$comm") }
                    if (owner != null) owner.usr = f else pendingSaves[comm] = f
                }
            }
        }
        // saves that were found before their set
        val it = pendingSaves.entries.iterator()
        while (it.hasNext()) {
            val (comm, f) = it.next()
            val owner = sets.values.firstOrNull { s -> s.kind == "ps4" && s.dir.endsWith("/$comm") } ?: continue
            owner.usr = f
            it.remove()
        }
    }

    private val pendingSaves = HashMap<String, String>()

    /**
     * Scans (full search when [full]), mirrors what changed and returns the parsed games.
     * Returns null when nothing changed since the last call.
     */
    fun scan(ctx: Context, full: Boolean): List<TrophyGame>? {
        if (scanning) return null
        scanning = true
        try {
            val lines = if (full || known.isEmpty()) findAll(ctx) else statKnown()
            val changed = ArrayList<String>()
            val files = ArrayList<String>()
            for (l in lines) {
                if (l.startsWith("FG|")) {
                    parseForeground(l)
                    continue
                }
                val parts = l.split('|', limit = 3)
                if (parts.size < 3) continue
                val path = parts[2].trim()
                val state = parts[0] + "|" + parts[1]
                files.add(path)
                if (known[path] != state) changed.add(path)
                known[path] = state
            }
            if (full) group(files)
            if (changed.isEmpty() && !full) return null
            mirror(ctx, changed, full)
            val games = ArrayList<TrophyGame>()
            for (s in sets.values) parse(ctx, s)?.let { games.add(it) }
            games.sortByDescending { it.lastPlayed }
            return games
        } catch (e: Exception) {
            AppLog.add("Trophy scan failed: " + e.message)
            return null
        } finally {
            scanning = false
        }
    }

    /** Copies changed metadata (and icons of new sets) into the app with one root call. */
    private fun mirror(ctx: Context, changed: List<String>, full: Boolean) {
        val root = mirrorDir(ctx)
        val sb = StringBuilder()
        for (s in sets.values) {
            val dst = File(root, s.key).absolutePath
            val dstIcons = File(root, s.key + "/icons")
            for (f in listOfNotNull(s.conf, s.usr)) {
                if (!changed.contains(f) && File(dst, mirrorName(s, f)).exists()) continue
                sb.append("mkdir -p ").append(q(dst)).append("; cat ").append(q(f)).append(" > ")
                    .append(q(dst + "/" + mirrorName(s, f))).append("; ")
            }
            val ic = s.iconDir
            if (ic != null && (full && !dstIcons.exists())) {
                sb.append("mkdir -p ").append(q(dstIcons.absolutePath)).append("; cp ").append(q(ic)).append("/*.PNG ")
                    .append(q(dstIcons.absolutePath)).append("/ 2>/dev/null; cp ").append(q(ic)).append("/*.png ")
                    .append(q(dstIcons.absolutePath)).append("/ 2>/dev/null; ")
            }
        }
        if (sb.isEmpty()) return
        val uid = android.os.Process.myUid()
        sb.append("chown -R $uid:$uid ").append(q(root.absolutePath)).append("; chmod -R 755 ").append(q(root.absolutePath))
        root.mkdirs()
        rootSh(sb.toString())
    }

    private fun mirrorName(s: SetFiles, f: String): String = when (f) {
        s.usr -> if (s.kind == "ps4") "save.xml" else "TROPUSR.DAT"
        else -> "conf.xml"
    }

    private fun emulatorFor(path: String): Pair<String, String> {
        val p = path.lowercase()
        return when {
            p.contains("vita3k") -> Pair("PS Vita", if (p.contains("plus")) "Vita3K Plus" else "Vita3K")
            p.contains("armsx3") -> Pair("PS3", "ARMSX3")
            p.contains("rpcs3") -> Pair("PS3", "RPCS3")
            p.contains("bachata") -> Pair("PS4", if (p.contains("plus") || p.contains("s4p")) "Bachata S4+" else "Bachata S4")
            p.contains("shadps4") -> Pair("PS4", "shadPS4")
            else -> Pair("PlayStation", "")
        }
    }

    private class Conf(val title: String, val comm: String, val list: List<Array<String>>)

    /** trophyconf XML -> title + entries [id, grade, hidden, name, detail, unlockstate, timestamp] */
    private fun readConf(text: String): Conf? {
        val start = text.indexOf('<')
        if (start < 0) return null
        val p = Xml.newPullParser()
        p.setInput(StringReader(text.substring(start)))
        var title = ""
        var comm = ""
        val list = ArrayList<Array<String>>()
        var cur: Array<String>? = null
        var tag = ""
        while (true) {
            val ev = try { p.next() } catch (_: Exception) { break }
            if (ev == XmlPullParser.END_DOCUMENT) break
            when (ev) {
                XmlPullParser.START_TAG -> {
                    tag = p.name
                    if (tag == "trophy") {
                        cur = arrayOf(
                            p.getAttributeValue(null, "id") ?: "0", p.getAttributeValue(null, "ttype") ?: "B",
                            p.getAttributeValue(null, "hidden") ?: "no", "", "",
                            p.getAttributeValue(null, "unlockstate") ?: "", p.getAttributeValue(null, "timestamp") ?: ""
                        )
                    }
                }
                XmlPullParser.TEXT -> {
                    val t = p.text ?: ""
                    val c = cur
                    when {
                        c != null && tag == "name" -> c[3] += t
                        c != null && tag == "detail" -> c[4] += t
                        c == null && tag == "title-name" -> title += t
                        c == null && tag == "npcommid" -> comm += t
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (p.name == "trophy") {
                        cur?.let { list.add(it) }
                        cur = null
                    }
                    tag = ""
                }
            }
        }
        return Conf(title.trim(), comm.trim(), list)
    }

    private fun parse(ctx: Context, s: SetFiles): TrophyGame? {
        val dir = File(mirrorDir(ctx), s.key)
        val confF = File(dir, "conf.xml")
        if (!confF.exists()) return null
        val conf = try { readConf(confF.readText()) } catch (_: Exception) { null } ?: return null
        if (conf.list.isEmpty()) return null
        val unlocked = HashMap<Int, Long>()
        val usrF = File(dir, if (s.kind == "ps4") "save.xml" else "TROPUSR.DAT")
        try {
            when (s.kind) {
                "ps3", "vita" -> if (usrF.exists()) readUsr(usrF.readBytes(), unlocked)
                "ps4" -> {
                    // older shadPS4 keeps the unlocks in TROP.XML itself
                    for (e in conf.list) if (e[5] == "true") unlocked[e[0].toIntOrNull() ?: -1] = e[6].toLongOrNull() ?: 0L
                    if (usrF.exists()) readConf(usrF.readText())?.list?.forEach { e ->
                        if (e[5] == "true") unlocked[e[0].toIntOrNull() ?: -1] = e[6].toLongOrNull() ?: 0L
                    }
                }
            }
        } catch (_: Exception) {
        }
        val icons = File(dir, "icons")
        fun icon(name: String): String {
            val a = File(icons, name)
            if (a.exists()) return a.absolutePath
            val b = File(icons, name.lowercase())
            return if (b.exists()) b.absolutePath else ""
        }
        val list = conf.list.map { e ->
            val id = e[0].toIntOrNull() ?: 0
            Trophy(id, e[3].trim(), e[4].trim(), e[1].uppercase().take(1), e[2] == "yes",
                unlocked.containsKey(id), unlocked[id] ?: 0L, icon(String.format("TROP%03d.PNG", id)))
        }
        val (console, emu) = emulatorFor(s.dir)
        val played = maxOf(File(dir, "conf.xml").lastModified(), usrF.lastModified()) / 1000L
        val mtime = known.filterKeys { it == s.usr || it == s.conf }.values.mapNotNull { it.substringBefore('|').toLongOrNull() }.maxOrNull()
        return TrophyGame(s.key, conf.title.ifEmpty { s.dir.substringAfterLast('/') }, console, emu,
            conf.comm.ifEmpty { s.dir.substringAfterLast('/') }, icon("ICON0.PNG"), mtime ?: played, list)
    }

    /** TROPUSR.DAT of RPCS3 (big-endian tables) or Vita3K (little-endian flags). */
    private fun readUsr(b: ByteArray, out: HashMap<Int, Long>) {
        if (b.size < 48) return
        val be = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN)
        val le = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        if (be.getInt(0) == RPCS3_MAGIC) {
            val tables = be.getInt(8)
            for (t in 0 until tables.coerceAtMost(16)) {
                val h = 48 + t * 32
                if (h + 32 > b.size) break
                val type = be.getInt(h)
                val count = be.getInt(h + 12)
                val off = be.getLong(h + 16).toInt()
                if (type != 6) continue
                for (i in 0 until count) {
                    val e = off + i * 0x70
                    if (e + 0x30 > b.size) break
                    val id = be.getInt(e + 16)
                    val state = be.getInt(e + 20)
                    val tick = be.getLong(e + 40)
                    // CellRtcTick: microseconds since 0001-01-01
                    val unix = if (tick > 62135596800000000L) tick / 1000000L - 62135596800L else 0L
                    if (state != 0) out[id] = unix
                }
            }
        } else if (le.getInt(0) == VITA_MAGIC) {
            val count = le.getInt(40).coerceIn(0, 128)
            for (i in 0 until count) {
                val word = le.getInt(4 + (i shr 5) * 4)
                if ((word ushr (i and 31)) and 1 == 1) {
                    val tsOff = 112 + i * 8
                    out[i] = if (tsOff + 8 <= b.size) le.getLong(tsOff) else 0L
                }
            }
        }
    }
}

/** Parsed trophy games, kept on disk so the Trophies tab opens instantly (and offline). */
object TrophyStore {
    @Volatile var games: List<TrophyGame> = emptyList()
        private set
    private var loaded = false

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, "trophies.json")

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        try {
            val f = file(ctx)
            if (!f.exists()) return
            val arr = JSONArray(f.readText())
            val out = ArrayList<TrophyGame>()
            for (i in 0 until arr.length()) {
                val g = arr.getJSONObject(i)
                val ta = g.getJSONArray("t")
                val list = ArrayList<Trophy>()
                for (j in 0 until ta.length()) {
                    val t = ta.getJSONObject(j)
                    list.add(Trophy(t.optInt("i"), t.optString("n"), t.optString("d"), t.optString("g"), t.optBoolean("h"),
                        t.optBoolean("u"), t.optLong("w"), t.optString("c")))
                }
                out.add(TrophyGame(g.optString("k"), g.optString("title"), g.optString("console"), g.optString("emu"),
                    g.optString("comm"), g.optString("icon"), g.optLong("played"), list))
            }
            games = out
        } catch (_: Exception) {
        }
    }

    fun set(ctx: Context, list: List<TrophyGame>) {
        games = list
        try {
            val arr = JSONArray()
            for (g in list) {
                val ta = JSONArray()
                for (t in g.trophies) ta.put(JSONObject().put("i", t.id).put("n", t.name).put("d", t.detail).put("g", t.grade)
                    .put("h", t.hidden).put("u", t.unlocked).put("w", t.time).put("c", t.icon))
                arr.put(JSONObject().put("k", g.key).put("title", g.title).put("console", g.console).put("emu", g.emulator)
                    .put("comm", g.commId).put("icon", g.icon).put("played", g.lastPlayed).put("t", ta))
            }
            file(ctx).writeText(arr.toString())
        } catch (_: Exception) {
        }
    }

    /** Newest unlocks over every game. */
    fun recent(n: Int): List<Pair<TrophyGame, Trophy>> =
        games.flatMap { g -> g.trophies.filter { it.unlocked }.map { Pair(g, it) } }.sortedByDescending { it.second.time }.take(n)

    fun byKey(k: String) = games.firstOrNull { it.key == k }
}

/** The trophy game being played right now (emulator in front + its newest trophy set), for the overlay. */
object TrophyLive {
    @Volatile var current: TrophyGame? = null
    @Volatile var sessionStart = 0L

    fun snap(): OverlaySnap? {
        val g = current ?: return null
        val list = g.trophies.map {
            LiveAch(it.id, if (it.hidden && !it.unlocked) "Hidden trophy" else it.name,
                if (it.hidden && !it.unlocked) "Keep playing to reveal it." else it.detail, "", 0, it.unlocked, "", 0f,
                it.time, if (it.unlocked) 2 else 1, it.unlocked && it.time * 1000L >= System.currentTimeMillis() - (SystemClock.elapsedRealtime() - sessionStart),
                it.grade, it.icon)
        }
        return OverlaySnap(-1, g.title + "  -  " + g.console, g.icon, SystemClock.elapsedRealtime() - sessionStart, 0L,
            list, g.got * 1000 + g.trophies.size)
    }
}

/**
 * Watches the trophy emulators while the engine runs: a cheap re-check every few seconds, a full search about once a
 * minute. New unlocks become trophy popups (grade-coloured glow) and are celebrated in the app later.
 */
class TrophyWatcher(private val ctx: Context, private val popup: Popup) {
    @Volatile private var stop = false

    fun start() {
        TrophyStore.load(ctx)
        Thread {
            var lastFull = 0L
            var baseline: Map<String, Set<Int>>? = null
            if (TrophyStore.games.isNotEmpty()) baseline = snapshot(TrophyStore.games)
            while (!stop) {
                val now = SystemClock.elapsedRealtime()
                val full = now - lastFull > 180000L
                if (full) lastFull = now
                val games = try { TrophyScanner.scan(ctx, full) } catch (_: Throwable) { null }
                if (games != null) {
                    val prev = baseline
                    if (prev != null) {
                        // only fresh unlocks pop up (time unknown counts as fresh), at most 3 per check
                        val nowSec = System.currentTimeMillis() / 1000L
                        var shown = 0
                        for (g in games) {
                            val before = prev[g.key] ?: emptySet()
                            for (t in g.trophies) {
                                if (!t.unlocked || before.contains(t.id)) continue
                                if (t.time != 0L && nowSec - t.time > 3600L) continue
                                if (shown < 3) announce(g, t) else AppLog.add("TROPHY: " + t.name + " - " + g.title)
                                shown++
                            }
                        }
                    }
                    baseline = snapshot(games)
                    TrophyStore.set(ctx, games)
                }
                updateCurrent()
                Thread.sleep(5000L)
            }
        }.start()
    }

    fun stop() {
        stop = true
    }

    private fun snapshot(g: List<TrophyGame>): Map<String, Set<Int>> =
        g.associate { it.key to it.trophies.filter { t -> t.unlocked }.map { t -> t.id }.toSet() }

    private fun updateCurrent() {
        val fg = TrophyScanner.lastForeground
        val emuInFront = fg.isNotEmpty() && listOf("armsx3", "rpcs3", "vita3k", "bachata", "shadps4").any { fg.contains(it, true) }
        if (!emuInFront) {
            if (TrophyLive.current != null && !GameOverlay.showing) TrophyLive.current = null
            return
        }
        val newest = TrophyStore.games.maxByOrNull { it.lastPlayed } ?: return
        if (TrophyLive.current?.key != newest.key) {
            TrophyLive.sessionStart = SystemClock.elapsedRealtime()
            AppLog.add("Trophy game: " + newest.title + " (" + newest.console + ")")
        }
        TrophyLive.current = TrophyStore.byKey(newest.key)
    }

    private fun announce(g: TrophyGame, t: Trophy) {
        AppLog.add("TROPHY (" + Grades.name(t.grade) + "): " + t.name + " - " + g.title)
        Unseen.add(ctx, UnseenItem(t.name, t.detail, 0, t.icon, g.title + " (" + g.console + ")"))
        popup.trophy(t.name, t.detail, t.grade, t.icon, g.title, g.console, g.got, g.trophies.size)
    }
}
