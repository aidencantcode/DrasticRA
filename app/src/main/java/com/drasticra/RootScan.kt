package com.drasticra

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Runs as ROOT via: su -c "CLASSPATH=<apk> app_process /system/bin com.drasticra.RootScan <package>"
 * Finds DraStic's process, scans its writable memory for the Nintendo DS cartridge-header copy that
 * the DS boot sequence leaves at 0x027FFE00 (offset 0x3FFE00 inside the 4 MB main RAM), and prints
 * where the main RAM block lives. Prints everything to stdout so the app can show it.
 */
object RootScan {
    private const val RAM_SIZE = 0x400000L
    private const val HDR_OFF = 0x3FFE00L
    private const val CHUNK = 1 shl 20
    private const val MAX_MATCHES = 20

    private class Region(val start: Long, val end: Long, val perms: String, val path: String) {
        val size: Long get() = end - start
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val pkg = if (args.isNotEmpty()) args[0] else "com.dsemu.drastic"
        val live = args.size > 1 && args[1] == "live"
        println("Drastic RA scan v2 | device=" + android.os.Build.MODEL + " | sdk=" + android.os.Build.VERSION.SDK_INT)
        println("uid=" + android.os.Process.myUid() + " (must be 0 = root)")
        println("target package: $pkg")

        val pid = findPid(pkg)
        if (pid < 0) {
            println("ERROR: process '$pkg' not running. Start DraStic and load a game first.")
            listSimilar(pkg)
            System.exit(2)
            return
        }
        println("pid=$pid")

        val regions = readRegions(pid)
        val big = regions.filter { it.size >= RAM_SIZE }
        println("writable regions: ${regions.size} total, ${big.size} of at least 4 MiB")

        val t0 = System.currentTimeMillis()
        var scanned = 0L
        var matches = 0
        var firstBase = -1L
        val buf = ByteArray(CHUNK)
        try {
            RandomAccessFile("/proc/$pid/mem", "r").use { mem ->
                for (r in big) {
                    var off = 0L
                    while (off < r.size && matches < MAX_MATCHES) {
                        val want = minOf(CHUNK.toLong(), r.size - off).toInt()
                        val got = readAt(mem, r.start + off, buf, want)
                        scanned += got
                        var p = 0xE00
                        while (p + 0x20 <= got) {
                            if (looksLikeHeader(buf, p)) {
                                matches++
                                val b = report(r, r.start + off + p, buf, p)
                                if (firstBase < 0) firstBase = b
                                if (matches >= MAX_MATCHES) break
                            }
                            p += 0x1000
                        }
                        off += CHUNK
                    }
                }
            }
        } catch (e: IOException) {
            println("ERROR opening /proc/$pid/mem: ${e.message}")
            System.exit(3)
            return
        }

        val ms = System.currentTimeMillis() - t0
        println("scanned ${scanned / (1024 * 1024)} MiB in $ms ms")
        if (matches == 0) {
            println("RESULT: NO MATCH. Is a game actually running (not just the DraStic menu)?")
            println("Large regions seen (send me this list):")
            for (r in big.take(40)) {
                println("  %x-%x %s %d MiB %s".format(r.start, r.end, r.perms, r.size shr 20, r.path))
            }
        } else {
            println("RESULT: $matches candidate(s) for DS main RAM")
            if (live && firstBase >= 0) liveLoop(pid, firstBase)
        }
        System.exit(0)
    }

    /** Proves the block is the LIVE emulated RAM: counts bytes that change each second while the game runs. */
    private fun liveLoop(pid: Int, base: Long) {
        println("LIVE: sampling main RAM for 15 s - keep the game running (walk around, open menus)")
        val size = RAM_SIZE.toInt()
        var prev = ByteArray(size)
        var cur = ByteArray(size)
        try {
            RandomAccessFile("/proc/$pid/mem", "r").use { mem ->
                if (readAt(mem, base, prev, size) < size) {
                    println("LIVE: read failed")
                    return
                }
                for (sec in 1..15) {
                    Thread.sleep(1000)
                    if (readAt(mem, base, cur, size) < size) {
                        println("LIVE: read failed")
                        return
                    }
                    var changed = 0
                    for (i in 0 until size) if (prev[i] != cur[i]) changed++
                    println("LIVE t=" + sec + "s changedBytes=" + changed)
                    val t = prev
                    prev = cur
                    cur = t
                }
            }
        } catch (e: IOException) {
            println("LIVE: error ${e.message}")
        }
        println("LIVE: done. changedBytes>0 while playing = this is the live game RAM.")
    }

    private fun report(r: Region, hdrAddr: Long, buf: ByteArray, p: Int): Long {
        val base = hdrAddr - HDR_OFF
        val title = String(buf, p, 12, Charsets.US_ASCII).trim { it == '\u0000' || it == ' ' }
        val code = String(buf, p + 12, 4, Charsets.US_ASCII)
        val maker = String(buf, p + 16, 2, Charsets.US_ASCII)
        val inside = base >= r.start && base + RAM_SIZE <= r.end
        println("MATCH title='$title' gamecode=$code maker=$maker")
        println("  ramBase=0x%x  headerAt=0x%x".format(base, hdrAddr))
        println("  region=0x%x-0x%x perms=%s path=%s".format(r.start, r.end, r.perms, r.path))
        println("  ram block fully inside region: $inside")
        return base
    }

    /** Real DS headers: ASCII title, uppercase game code + maker code, reserved bytes at 0x15..0x1B are zero. */
    private fun looksLikeHeader(b: ByteArray, p: Int): Boolean {
        var letters = 0
        var i = 0
        while (i < 12) {
            val c = b[p + i].toInt() and 0xFF
            if (c == 0) {
                for (j in i until 12) if (b[p + j].toInt() != 0) return false
                break
            }
            if (c < 0x20 || c > 0x7E) return false
            if ((c in 0x41..0x5A) || (c in 0x61..0x7A)) letters++
            i++
        }
        if (letters < 2) return false
        for (k in 12 until 18) {
            val c = b[p + k].toInt() and 0xFF
            if (!((c in 0x41..0x5A) || (c in 0x30..0x39))) return false
        }
        val unit = b[p + 0x12].toInt() and 0xFF
        if (unit != 0 && unit != 2 && unit != 3) return false
        if ((b[p + 0x14].toInt() and 0xFF) > 0x0F) return false
        for (k in 0x15..0x1B) if (b[p + k].toInt() != 0) return false
        return true
    }

    private fun readAt(mem: RandomAccessFile, addr: Long, buf: ByteArray, len: Int): Int {
        var got = 0
        try {
            mem.seek(addr)
            while (got < len) {
                val k = mem.read(buf, got, len - got)
                if (k <= 0) break
                got += k
            }
        } catch (e: IOException) {
            // unreadable pages: use whatever we got
        }
        return got
    }

    private fun readRegions(pid: Int): List<Region> {
        val out = ArrayList<Region>()
        File("/proc/$pid/maps").forEachLine { line ->
            val f = line.trim().split(Regex("\\s+"), 6)
            if (f.size < 5 || f[1].length < 2 || f[1][0] != 'r' || f[1][1] != 'w') return@forEachLine
            val path = if (f.size >= 6) f[5].trim() else ""
            val okPath = path.isEmpty() ||
                (path.startsWith("[") && !path.startsWith("[stack")) ||
                path.startsWith("/dev/ashmem") || path.startsWith("/memfd")
            if (!okPath) return@forEachLine
            val range = f[0].split("-")
            if (range.size != 2) return@forEachLine
            try {
                val s = java.lang.Long.parseUnsignedLong(range[0], 16)
                val e = java.lang.Long.parseUnsignedLong(range[1], 16)
                if (s >= 0 && e > s) out.add(Region(s, e, f[1], path))
            } catch (_: NumberFormatException) {
            }
        }
        return out
    }

    private fun findPid(pkg: String): Int {
        val dirs = File("/proc").listFiles() ?: return -1
        for (d in dirs) {
            val pid = d.name.toIntOrNull() ?: continue
            try {
                val name = File("/proc/$pid/cmdline").readText().substringBefore('\u0000')
                if (name == pkg) return pid
            } catch (_: Exception) {
            }
        }
        return -1
    }

    private fun listSimilar(pkg: String) {
        val key = pkg.substringAfterLast('.').lowercase()
        val dirs = File("/proc").listFiles() ?: return
        for (d in dirs) {
            val pid = d.name.toIntOrNull() ?: continue
            try {
                val name = File("/proc/$pid/cmdline").readText().substringBefore('\u0000')
                if (name.lowercase().contains(key)) println("  similar process: $name (pid $pid)")
            } catch (_: Exception) {
            }
        }
    }
}
