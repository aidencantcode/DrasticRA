package com.drasticra

import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.Socket

/**
 * Runs as ROOT: su -c "CLASSPATH=<apk> app_process /system/bin com.drasticra.RootServer <pkg> <port>"
 * Connects to the app over loopback and serves pieces of DraStic's DS main RAM.
 *   'H'            -> 1 status byte + 0x200 bytes (cartridge header copy from RAM)
 *   'P' n idx[n]   -> n * 4096 bytes (4 KiB pages of main RAM, in the order requested)
 *
 * The very last header byte (0x1FF) is not part of what the app checks, so the helper uses it to say whether
 * DraStic is on screen right now: 0 = unknown, 1 = visible, 2 = hidden / in the background. That is how play
 * time only counts while the game is really in front of you.
 */
object RootServer {
    private const val PAGE = 4096
    private const val NPAGES = 1024
    private var base = -1L
    private var mem: RandomAccessFile? = null
    private var lastLocate = 0L
    private var procPid = -1

    /** 1 = DraStic is on screen, 2 = it is in the background, 0 = could not tell. Cheap: reads one /proc file. */
    private fun visibleFlag(): Int {
        val p = procPid
        if (p < 0) return 0
        return try {
            val adj = File("/proc/$p/oom_score_adj").readText().trim().toInt()
            if (adj <= 100) 1 else 2
        } catch (e: Exception) {
            0
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val pkg = args[0]
        val port = args[1].toInt()
        println("root helper up, uid=" + android.os.Process.myUid())
        val sock = Socket("127.0.0.1", port)
        sock.tcpNoDelay = true
        val inp = DataInputStream(sock.getInputStream().buffered())
        val out = sock.getOutputStream()
        val outBuf = ByteArray(NPAGES * PAGE)
        try {
            while (true) {
                val cmd = inp.read()
                if (cmd < 0) break
                if (cmd == 'H'.code) {
                    val h = ByteArray(0x200)
                    var st = 0
                    var ok = ensure(pkg) && readRange(base + RootScan.HDR_OFF, h, 0, 0x200) == 0x200 &&
                        RootScan.looksLikeHeader(h, 0)
                    if (!ok) {
                        val now = System.currentTimeMillis()
                        if (now - lastLocate > 1000) {
                            lastLocate = now
                            base = -1L
                            ok = ensure(pkg) && readRange(base + RootScan.HDR_OFF, h, 0, 0x200) == 0x200 &&
                                RootScan.looksLikeHeader(h, 0)
                        }
                    }
                    if (ok) {
                        st = 1
                        h[0x1FF] = visibleFlag().toByte()
                    }
                    out.write(st)
                    out.write(h)
                    out.flush()
                } else if (cmd == 'P'.code) {
                    val n = inp.readInt()
                    if (n < 0 || n > NPAGES) break
                    val idx = IntArray(n) { inp.readInt() }
                    if (!fill(pkg, idx, outBuf)) {
                        val now = System.currentTimeMillis()
                        if (now - lastLocate > 1000) {
                            lastLocate = now
                            base = -1L
                            if (ensure(pkg)) fill(pkg, idx, outBuf)
                        }
                    }
                    out.write(outBuf, 0, n * PAGE)
                    out.flush()
                } else break
            }
        } catch (e: IOException) {
        }
        System.exit(0)
    }

    private fun fill(pkg: String, idx: IntArray, out: ByteArray): Boolean {
        java.util.Arrays.fill(out, 0, idx.size * PAGE, 0.toByte())
        if (!ensure(pkg)) return false
        var ok = true
        var i = 0
        while (i < idx.size) {
            var j = i
            while (j + 1 < idx.size && idx[j + 1] == idx[j] + 1) j++
            if (idx[i] >= 0 && idx[j] < NPAGES) {
                val len = (j - i + 1) * PAGE
                if (readRange(base + idx[i].toLong() * PAGE, out, i * PAGE, len) < len) ok = false
            }
            i = j + 1
        }
        return ok
    }

    private fun readRange(addr: Long, dst: ByteArray, off: Int, len: Int): Int {
        val m = mem ?: return 0
        var got = 0
        try {
            m.seek(addr)
            while (got < len) {
                val k = m.read(dst, off + got, len - got)
                if (k <= 0) break
                got += k
            }
        } catch (e: IOException) {
        }
        return got
    }

    private fun ensure(pkg: String): Boolean {
        if (base >= 0 && mem != null) return true
        try {
            mem?.close()
        } catch (e: IOException) {
        }
        mem = null
        base = -1L
        val pid = RootScan.findPid(pkg)
        if (pid < 0) return false
        val m = try {
            RandomAccessFile("/proc/$pid/mem", "r")
        } catch (e: IOException) {
            return false
        }
        val regions = RootScan.readRegions(pid)
            .filter { it.size >= RootScan.RAM_SIZE }
            .sortedBy { if (it.path.contains("drastic_mapped_memory")) 0 else 1 }
        val buf = ByteArray(RootScan.CHUNK)
        for (r in regions) {
            var off = 0L
            while (off < r.size) {
                val want = minOf(RootScan.CHUNK.toLong(), r.size - off).toInt()
                val got = RootScan.readAt(m, r.start + off, buf, want)
                var q = 0xE00
                while (q + 0x20 <= got) {
                    if (RootScan.looksLikeHeader(buf, q)) {
                        base = r.start + off + q - RootScan.HDR_OFF
                        mem = m
                        procPid = pid
                        println("RAM located at 0x" + java.lang.Long.toHexString(base))
                        return true
                    }
                    q += 0x1000
                }
                off += RootScan.CHUNK
            }
        }
        m.close()
        return false
    }
}
